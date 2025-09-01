package kd.trading.bot.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.OrderSide;
import kd.trading.bot.enums.PositionSide;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.enums.TradeStatus;
import kd.trading.bot.mapper.OrderMapper;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.OrderTradeUpdateDto;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.model.TradeDto;
import kd.trading.bot.util.TradeServiceHelper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    @Getter
    public static final List<OrderDto> orderDtoList = new CopyOnWriteArrayList<>();
    @Getter
    public static final List<OrderDto> activeOrderList = new CopyOnWriteArrayList<>();

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
    public void openTrade(Signal signal, BigDecimal lastPrice, SymbolInfo info) {
        tradeQueue.offer(() -> makeTrade(signal, lastPrice, info));
    }

    private void makeTrade(Signal signal, BigDecimal lastPrice, SymbolInfo info) {
        if (orderDtoList.size() >= tradingConfig.maxTrade()) {
            log.info("Max trades reached, skipping {}", info.getSymbol());
            return;
        }

        if (checkIfContainsSymbol(info.getSymbol()) ) {
            log.info("Symbol {} is in activeTrades or OpenTrades, skipping", info.getSymbol());
            return;
        }

        //todo lehet kell ez a dto
        TradeDto trade = helper.generateTradeDto(signal, lastPrice, info);

        OrderDto orderDto = restClient.placeOrder(
                info.getSymbol(),
                trade.getQuantity(),
                trade.getEntryPrice(),
                signal
        );

        if(orderDto != null) orderDtoList.add(orderDto);

        // ha 1 perc múlva sincs update, akkor töröljük
        scheduler.schedule(() -> {
            if (orderDtoList.contains(orderDto)) {
                log.info("Order {} timed out -> attempting cancel on Binance", orderDto.getSymbol());

                boolean canceled = restClient.cancelOrdersForSymbol(orderDto.getSymbol());
                if (canceled) {
                    removeFromOrders(orderDto.getSymbol());
                } else {
                    log.warn("Failed to cancel order {} on Binance -> keeping in list", orderDto.getSymbol());
                }
            }
        }, 2, TimeUnit.MINUTES);
    }


    public void moveOrderToActive(OrderDto orderDto) {
        activeOrderList.add(orderDto);
        orderDtoList.removeIf(o -> o.getOrderId().equals(orderDto.getOrderId()));
        log.info("Order {} moved to active trades.", orderDto.getSymbol());
    }

    public void removeFromOrders(String symbol) {
        orderDtoList.removeIf(o -> o.getSymbol().equals(symbol));
        log.info("Order {} removed from order list.", symbol);
    }

    /**
     * Központi handler, amit az AccountProfitService hív
     */
    public void handleOrderUpdate(OrderTradeUpdateDto order) {
        String status = order.o.X;   // Order státusz
        String type = order.o.o; // Order Type
        String symbol = order.o.s;   // pl. BTCUSDT

        //activeba kerül
        // S=SELL ez mindig az ellenkezője ha vételköz longoltunk buy volt akkor eladáskor S=SELL lesz.
        if (status.equals("FILLED")) {
            if ("MARKET".equals(type)) {

                OrderTradeUpdateDto.Order binanceOrder = order.o;
                OrderDto myOrder = OrderMapper.fromBinanceOrder(binanceOrder);

                moveOrderToActive(myOrder);


            } else if ("TRADE".equals(type)) {
                // Short záródik
                removeFromActiveBySymbol(order.o.s); // csak itt törlünk
            }
        } else {
            log.debug("Unhandled status {} for {}", status, symbol);
        }
    }

    private void removeFromActiveBySymbol(String symbol) {
        activeOrderList.removeIf(o -> o.getSymbol().equals(symbol));
    }

    private Boolean checkIfContainsSymbol(String symbol) {
        return orderDtoList.stream() .map(OrderDto::getSymbol).anyMatch(symbol::equals)
                || activeOrderList.stream().map(OrderDto::getSymbol).anyMatch(symbol::equals);
    }

    public void closeOrder(OrderDto order) {
        try {
            BigDecimal qty = order.getExecutedQty().signum() > 0
                    ? order.getExecutedQty()
                    : order.getOrigQty();

            SymbolInfo info = helper.getSymbolInfo(order.getSymbol()); // pl. cache-ből, ha van
            Signal signal = order.getSide() == OrderSide.BUY ? Signal.LONG : Signal.SHORT;

            boolean ok = restClient.closeMarketOrder(info, qty, signal);
            if (ok) {
                log.info("Closed order {} on {}", order.getOrderId(), order.getSymbol());
                activeOrderList.remove(order);
            } else {
                log.warn("Failed to close order {} on {}", order.getOrderId(), order.getSymbol());
            }
        } catch (Exception e) {
            log.error("Exception while closing order {}", order.getSymbol(), e);
        }
    }
}
