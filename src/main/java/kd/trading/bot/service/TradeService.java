package kd.trading.bot.service;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.model.Signal;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.model.TradeDto;
import kd.trading.bot.model.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeService {

    private final BinanceRestClient restClient;
    private final TradingConfig tradingConfig;

    public static final List<TradeDto> activeTrades = new CopyOnWriteArrayList<>();
    public static final Map<String, Instant> badTrades = new ConcurrentHashMap<>();
    public static final Set<String> openTrades = ConcurrentHashMap.newKeySet();

    // --- új queue + worker thread ---
    private final BlockingQueue<Runnable> tradeQueue = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void initWorker() {
        worker.submit(this::processTrades);
    }

    private void processTrades() {
        while (true) {
            try {
                Runnable task = tradeQueue.take();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Trade worker interrupted, exiting.");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in trade worker", e);
            }
        }
    }

    // --- Trade nyitása ---
    public void openTrade(Signal signal, BigDecimal lastPrice, SymbolInfo info) {
        tradeQueue.offer(() -> handleOpenTrade(signal, lastPrice, info));
    }

    private void handleOpenTrade(Signal signal, BigDecimal lastPrice, SymbolInfo info) {
        if (activeTrades.size() >= tradingConfig.maxTrade()) {
            log.info("Max trades reached, skipping {}", info.getSymbol());
            return;
        }
        if (badTrades.containsKey(info.getSymbol())) {
            log.info("Symbol {} is in badTrades, skipping", info.getSymbol());
            return;
        }

        int priceScale = info.getPriceScaleOrDefault();
        int qtyScale = info.getQuantityScaleOrDefault();

        BigDecimal normalizedPrice = lastPrice.setScale(priceScale, RoundingMode.DOWN);
        BigDecimal normalizedQty = calculateQtyForSymbol(info, lastPrice)
                .setScale(qtyScale, RoundingMode.DOWN);

        double stopLimit;
        double winLimit;
        if (signal == Signal.LONG) {
            stopLimit = lastPrice.doubleValue() * (1 - Double.parseDouble(tradingConfig.stopLimit()) / 100.0);
            winLimit  = lastPrice.doubleValue() * (1 + Double.parseDouble(tradingConfig.winLimit()) / 100.0);
        } else {
            stopLimit = lastPrice.doubleValue() * (1 + Double.parseDouble(tradingConfig.stopLimit()) / 100.0);
            winLimit  = lastPrice.doubleValue() * (1 - Double.parseDouble(tradingConfig.winLimit()) / 100.0);
        }

        TradeDto trade = new TradeDto();
        trade.setSymbol(info);
        trade.setSignal(signal);
        trade.setEntryPrice(lastPrice);
        trade.setStopLimit(stopLimit);
        trade.setWinLimit(winLimit);
        trade.setOpenedAt(Instant.now());
        trade.setQuantity(normalizedQty);

        try {
            TradeStatus status = restClient.placeOrder(
                    info.getSymbol(),
                    normalizedQty,
                    normalizedPrice,
                    signal
            );

            if (status == TradeStatus.SUCCESS) {
                activeTrades.add(trade);
                log.info("Opened trade: {} {} @{} SL={} TP={}",
                        signal, info.getSymbol(), normalizedPrice, stopLimit, winLimit);
            } else if (status == TradeStatus.OPEN) {
                openTrades.add(info.getSymbol());
                log.warn("Trade still OPEN for {}", info.getSymbol());
            } else {
                log.warn("Trade failed for {} {}", signal, info.getSymbol());
            }

        } catch (Exception e) {
            log.error("Failed to place order for {}", info.getSymbol(), e);
        }
    }

    // --- Open trade-ek lezárása ---
    public void closeOpenTrades() {
        tradeQueue.offer(this::handleCloseOpenTrades);
    }

    private void handleCloseOpenTrades() {
        if (!openTrades.isEmpty()) {
            List<String> toRemove = new ArrayList<>();
            for (String symbol : openTrades) {
                boolean success = restClient.cancelOrdersForSymbol(symbol);
                if (success) {
                    toRemove.add(symbol);
                }
            }
            openTrades.removeAll(toRemove);
        }
    }

    // --- Trade zárása ---
    public void closeTrade(TradeDto trade, boolean bad) {
        tradeQueue.offer(() -> handleCloseTrade(trade, bad));
    }

    private void handleCloseTrade(TradeDto trade, boolean bad) {
        try {
            BigDecimal stopPrice;
            if (trade.getSignal() == Signal.LONG) {
                stopPrice = trade.getEntryPrice().multiply(BigDecimal.valueOf(0.98));
            } else {
                stopPrice = trade.getEntryPrice().multiply(BigDecimal.valueOf(1.02));
            }

            restClient.placeStopOrder(
                    trade.getSymbol(),
                    trade.getQuantity(),
                    stopPrice,
                    trade.getSignal()
            );

            activeTrades.remove(trade);
            log.info("Closed trade: {} {}", trade.getSignal(), trade.getSymbol());

            if (bad) {
                badTrades.put(trade.getSymbol().getSymbol(), Instant.now());
                log.info("Added {} to badTrades", trade.getSymbol());
            }
        } catch (Exception e) {
            log.error("Failed to close trade {}", trade.getSymbol(), e);
        }
    }

    @Scheduled(fixedRate = 60_000)
    public void cleanupBadTrades() {
        Instant now = Instant.now();
        badTrades.entrySet().removeIf(entry ->
                now.isAfter(entry.getValue().plusSeconds(5 * 3600))
        );
    }

    public List<TradeDto> getActiveTrades() {
        return List.copyOf(activeTrades);
    }

    // --- Qty számítás szűrőkkel ---
    private BigDecimal calculateQtyForSymbol(SymbolInfo info, BigDecimal lastPrice) {
        if (info == null) {
            throw new IllegalArgumentException("SymbolInfo is null");
        }
        SymbolInfo.Filter lotSize = info.getFilter("LOT_SIZE");
        BigDecimal minQty   = new BigDecimal(lotSize.getMinQty());
        BigDecimal maxQty   = new BigDecimal(lotSize.getMaxQty());
        BigDecimal stepSize = new BigDecimal(lotSize.getStepSize());

        SymbolInfo.Filter notionalFilter = info.getFilter("MIN_NOTIONAL");
        BigDecimal minNotional = notionalFilter != null && notionalFilter.getNotional() != null
                ? new BigDecimal(notionalFilter.getNotional())
                : BigDecimal.ZERO;

        BigDecimal usdtBalance = BigDecimal.valueOf(50.0);
        BigDecimal rawQty = usdtBalance.divide(lastPrice, 8, RoundingMode.DOWN);

        BigDecimal orderValue = rawQty.multiply(lastPrice);
        if (orderValue.compareTo(minNotional) < 0) {
            rawQty = minNotional.divide(lastPrice, 8, RoundingMode.UP);
        }

        int precision = stepSize.stripTrailingZeros().scale();
        BigDecimal adjusted = rawQty.setScale(precision, RoundingMode.DOWN);

        if (adjusted.compareTo(minQty) < 0) adjusted = minQty;
        if (adjusted.compareTo(maxQty) > 0) adjusted = maxQty;

        return adjusted;
    }
}
