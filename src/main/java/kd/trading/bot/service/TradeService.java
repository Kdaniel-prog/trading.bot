package kd.trading.bot.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.OrderSide;
import kd.trading.bot.enums.PositionSide;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.enums.TradeStatus;
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
                assert orderDto != null;
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
        orderDtoList.remove(orderDto);
        activeOrderList.add(orderDto);
        log.info("Order {} moved to active trades.", orderDto.getSymbol());
    }

    public void removeFromOrders(String symbol) {
        orderDtoList.removeIf(o -> o.getSymbol().equals(symbol));
        log.info("Order {} removed from order list.", symbol);
    }

    /**
     * Központi handler, amit az AccountProfitService hív
     */
    public void handleOrderUpdate(OrderTradeUpdateDto orderUpdate) {
        String status = orderUpdate.o.X;   // order status (NEW, FILLED, stb.)
        String symbol = orderUpdate.o.s;   // pl. BTCUSDT
        Long orderId = orderUpdate.o.i;    // Binance orderId
        String side = orderUpdate.o.S;     // BUY vagy SELL
        String positionSide = orderUpdate.o.ps != null ? orderUpdate.o.ps : "BOTH"; // ha nincs ps, default BOTH
        double realizedProfit = parseProfit(orderUpdate.o.rp);

        boolean isOpening = isIsOpening(positionSide, side, realizedProfit);

        switch (status) {
            case "NEW":
                log.info("New Order {} ({}) status: {}", symbol, orderId, status);
                break;

            case "PARTIALLY_FILLED":
                log.info("Partially filled Order {} ({}) status: {} side: {}", symbol, orderId, status, side);
                break;

            case "FILLED":
                log.info("Order {} ({}) FILLED side={} posSide={}", symbol, orderId, side, positionSide);

                // ha pending listában volt → aktívba tesszük
                Optional<OrderDto> dto = orderDtoList.stream()
                        .filter(o -> o.getOrderId().equals(orderId))
                        .findFirst();

                if (isOpening) {
                    if (dto.isPresent()) {
                        moveOrderToActive(dto.get());
                        log.info("Order {} -> moved to ACTIVE trades (opened position)", symbol);
                    } else {
                        log.warn("Opening order {} not found in pending list", symbol);
                    }
                } else {
                    // Closing: remove from active by symbol
                    removeFromActiveBySymbol(symbol);
                    log.info("Order {} -> REMOVED from ACTIVE trades (closed position)", symbol);

                    if (dto.isPresent()) {
                        // If closing was somehow in pending, remove it
                        removeFromOrdersById(orderId);
                        log.info("Closing order {} removed from pending list", symbol);
                    }
                }
                break;

            case "CANCELED":
            case "EXPIRED":
                removeFromOrdersById(orderId);
                log.info("Order {} ({}) removed due to {}", symbol, orderId, status);
                break;

            default:
                log.warn("Unhandled order status {} for {} ({})", status, symbol, orderId);
        }
    }

    private static boolean isIsOpening(String positionSide, String side, double realizedProfit) {
        boolean isHedgeMode = !"BOTH".equals(positionSide);
        boolean isOpening;
        String tradeType; // "LONG" or "SHORT" - not used here but calculated for consistency

        if (isHedgeMode) {
            // Hedge mode logic
            if ("LONG".equals(positionSide)) {
                if ("BUY".equals(side)) {
                    isOpening = true;
                    tradeType = "LONG";
                } else { // SELL
                    isOpening = false;
                    tradeType = "LONG";
                }
            } else { // SHORT
                if ("SELL".equals(side)) {
                    isOpening = true;
                    tradeType = "SHORT";
                } else { // BUY
                    isOpening = false;
                    tradeType = "SHORT";
                }
            }
        } else {
            // One-way mode logic
            boolean isReducing = realizedProfit != 0.0;
            if (isReducing) {
                if ("BUY".equals(side)) {
                    isOpening = false;
                    tradeType = "SHORT"; // closing short
                } else {
                    isOpening = false;
                    tradeType = "LONG"; // closing long
                }
            } else {
                if ("BUY".equals(side)) {
                    isOpening = true;
                    tradeType = "LONG";
                } else {
                    isOpening = true;
                    tradeType = "SHORT";
                }
            }
        }
        return isOpening;
    }

    private double parseProfit(String rp) {
        return (rp != null && !rp.isEmpty()) ? Double.parseDouble(rp) : 0.0;
    }

    private void removeFromOrdersById(Long orderId) {
        orderDtoList.removeIf(o -> o.getOrderId().equals(orderId));
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
                // Note: We don't add closing orders to orderDtoList, but if you change that, adjust accordingly
            } else {
                log.warn("Failed to close order {} on {}", order.getOrderId(), order.getSymbol());
            }
        } catch (Exception e) {
            log.error("Exception while closing order {}", order.getSymbol(), e);
        }
    }
}
