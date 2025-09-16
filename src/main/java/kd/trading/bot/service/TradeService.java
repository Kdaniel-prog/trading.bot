package kd.trading.bot.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.util.TradeServiceHelper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradeService {
    BinanceRestClient restClient;
    TradingConfig tradingConfig;
    TradeServiceHelper helper;
    static ModelMapper modelMapper;

    @Getter
    public static final List<OrderDto> orderDtoList = new CopyOnWriteArrayList<>();
    @Getter
    public static final List<OrderDto> activeOrderList = new CopyOnWriteArrayList<>();

    public static final Set<BadSymbolsDto> BAD_SYMBOL_LIST = new HashSet<>();
    public static final Set<String> BANNED_SYMBOL = new HashSet<>();

    // --- queue + worker thread ---
    private final BlockingQueue<Runnable> tradeQueue = new LinkedBlockingQueue<>();
    private final ExecutorService workerPool  = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @PostConstruct
    public void initWorker() {
        for (int i = 0; i < 4; i++) {
            workerPool.submit(this::processTrades);
        }
    }

    @PreDestroy
    public void shutdownWorker() {
        log.info("Shutting down TradeService worker...");
        workerPool.shutdownNow();
        scheduler.shutdownNow();
    }

    /**
     * 30 percenként megnézük.
     */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void refreshBadTrades() {
        LocalDateTime now = LocalDateTime.now();

        BAD_SYMBOL_LIST.removeIf(dto ->
                Duration.between(dto.getStamp(), now).toHours() >= 2);

        log.info("Bad symbol list refreshed, current size: {}", BAD_SYMBOL_LIST.size());
    }

    private void processTrades() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Runnable task = tradeQueue.take();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Trade worker interrupted, exiting.");
            } catch (Exception e) {
                log.error("Unexpected error in trade worker", e);
            }
        }
    }

    // --- Trade nyitása ---
    public void openTrade(CoinAnalysis coinAnalysis, SymbolInfo symbol) {
        boolean added = tradeQueue.offer(() -> makeTrade(coinAnalysis, symbol));
        if (!added) {
            log.info("Trade queue is full, trade not queued!");
        }
    }

    private void makeTrade(CoinAnalysis coinAnalysis, SymbolInfo symbol) {
        log.warn("list size: {} | max trade : {}", getListSize(), tradingConfig.maxTrade());
        if (getListSize() > tradingConfig.maxTrade()) {
            log.info("Max trades reached, skipping {}", coinAnalysis.getSymbol());
            return;
        }

        if (checkIfContainsSymbol(coinAnalysis.getSymbol()) ) {
            log.info("Symbol {} is in activeTrades or OpenTrades, skipping", coinAnalysis.getSymbol());
            return;
        }

        TradeDto trade = helper.generateTradeDto(
                coinAnalysis.getSignal(),
                BigDecimal.valueOf(coinAnalysis.getLastPrice()),
                symbol);

        restClient.changeLeverage(coinAnalysis.getSymbol());


        restClient.placeMarketOrder(
             coinAnalysis.getSymbol(),
             trade.getQuantity(),
             coinAnalysis.getSignal()
        );

        /**
        restClient.placeOrder(
                coinAnalysis.getSymbol(),
                trade.getQuantity(),
                trade.getEntryPrice(),
                coinAnalysis.getSignal()
        );
         */
    }

    public Integer getListSize() {
        return activeOrderList.size() + orderDtoList.size();
    }

    public void moveOrderToActive(OrderDto orderDto) {
        orderDto.setStarted(LocalDateTime.now());
        activeOrderList.add(orderDto);
        orderDtoList.removeIf(o -> o.getOrderId() != null && o.getOrderId().equals(orderDto.getOrderId()));
        log.info("Order {} moved to active trades.", orderDto.getSymbol());
    }

    public void removeFromActiveBySymbol(String symbol) {
        activeOrderList.removeIf(o -> o.getSymbol().equals(symbol));
    }

    private Boolean checkIfContainsSymbol(String symbol) {
        return orderDtoList.stream() .map(OrderDto::getSymbol).anyMatch(symbol::equals)
                || activeOrderList.stream().map(OrderDto::getSymbol).anyMatch(symbol::equals);
    }

    public void closeOrder(OrderDto order) {
        try {
            SymbolInfo info = helper.getSymbolInfo(order.getSymbol());

            // 1. Lekérdezzük a tényleges pozíció méretet Binance-től
            BigDecimal positionAmt = restClient.getOpenPositionQty(info.getSymbol());
            if (positionAmt.signum() == 0) {
                log.info("No open position on {}, nothing to close.", info.getSymbol());
                activeOrderList.remove(order);
                return;
            }

            // 2. Signal irány eldöntése a pozíció alapján
            Signal signal = positionAmt.signum() > 0 ? Signal.LONG : Signal.SHORT;

            // 3. Pozíció zárása
            restClient.closeMarketOrder(info, positionAmt.abs(), signal);
        } catch (Exception e) {
            log.error("Exception while closing order {}", order.getSymbol(), e);
        }
    }

    @Scheduled(fixedRate = 3 * 60 * 1000)
    public void checkOpenOrdes() {
        orderDtoList.removeIf(orderDto -> {
            if (orderDto.getStarted() != null) {
                Duration openDuration = Duration.between(orderDto.getStarted(), LocalDateTime.now());
                if (openDuration.toMinutes() >= 5) {
                    closeOrder(orderDto);
                    return true; // töröljük a listából
                }
            }
            return false;
        });
    }


    public void loadActiveOrdersOnStartup() {
        try {
            // Aktív orderek lekérése a BinanceRestClient segítségével
            List<OrderDto> activeOrdersFromApi = restClient.getActiveOrders();

            if (activeOrdersFromApi != null && !activeOrdersFromApi.isEmpty()) {
                activeOrderList.clear();
                activeOrderList.addAll(activeOrdersFromApi);

                log.info("Aktív orderek betöltve: {}", activeOrdersFromApi.size());
            } else {
                log.info("Nincsenek aktív orderek induláskor.");
            }

        } catch (Exception e) {
            log.info("Hiba történt az aktív orderek betöltésekor: {}", e.getMessage());
        }
    }
}
