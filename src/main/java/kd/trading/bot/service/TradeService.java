package kd.trading.bot.service;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.model.Signal;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.model.TradeDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeService {

    private final BinanceRestClient restClient;
    private final TradingConfig tradingConfig;

    private final static List<TradeDto> activeTrades = new ArrayList<>();
    private final static Map<String, Instant> badTrades = new HashMap<>();

    private static final int MAX_TRADES = 5;

    public synchronized void openTrade(Signal signal, BigDecimal lastPrice, SymbolInfo info) {
        if (activeTrades.size() >= MAX_TRADES) {
            log.info("Max trades reached, skipping {}", info.getSymbol());
            return;
        }
        if (badTrades.containsKey(info.getSymbol())) {
            log.info("Symbol {} is in badTrades, skipping", info.getSymbol());
            return;
        }

        int priceScale = info.getPriceScaleOrDefault();
        int qtyScale = info.getQuantityScaleOrDefault();

        // ár normalizálása
        BigDecimal normalizedPrice = lastPrice.setScale(priceScale, RoundingMode.DOWN);

        // fix order érték $-ban (kívülről is paraméterezhető)
        BigDecimal orderValue = BigDecimal.valueOf(50.0);
        BigDecimal rawQty = orderValue.divide(lastPrice, qtyScale + 5, RoundingMode.DOWN);

        // végleges mennyiség
        BigDecimal normalizedQty = rawQty.setScale(qtyScale, RoundingMode.DOWN);

        // stop és take profit számítás
        double stopLimit;
        double winLimit;
        if (signal == Signal.LONG) {
            stopLimit = lastPrice.doubleValue() * (1 - Double.parseDouble(tradingConfig.stopLimit()) / 100.0);
            winLimit  = lastPrice.doubleValue() * (1 + Double.parseDouble(tradingConfig.winLimit()) / 100.0);
        } else {
            stopLimit = lastPrice.doubleValue() * (1 + Double.parseDouble(tradingConfig.stopLimit()) / 100.0);
            winLimit  = lastPrice.doubleValue() * (1 - Double.parseDouble(tradingConfig.winLimit()) / 100.0);
        }

        // trade objektum
        TradeDto trade = new TradeDto();
        trade.setSymbol(info);
        trade.setSignal(signal);
        trade.setEntryPrice(lastPrice);
        trade.setStopLimit(stopLimit);
        trade.setWinLimit(winLimit);
        trade.setOpenedAt(Instant.now());
        trade.setQuantity(normalizedQty);

        // order indítás
        try {
            boolean entryOk = restClient.placeOrder(
                    info.getSymbol(),
                    normalizedQty,
                    normalizedPrice,
                    signal
            );

            if (entryOk) {
                // STOP LOSS order
                restClient.placeStopOrder(
                        info,
                        normalizedQty,
                        BigDecimal.valueOf(stopLimit),
                        signal
                );

                // TAKE PROFIT order
                restClient.placeTakeProfitOrder(
                        info,
                        normalizedQty,
                        BigDecimal.valueOf(winLimit),
                        signal
                );

                activeTrades.add(trade);
                log.info("Opened trade: {} {} @{} SL={} TP={}",
                        signal, info.getSymbol(), normalizedPrice, stopLimit, winLimit);
            }
        } catch (Exception e) {
            log.error("Failed to place order for {}", info.getSymbol(), e);
        }
    }

    public synchronized void closeTrade(TradeDto trade, boolean bad) {
        try {
            BigDecimal stopPrice;
            // egyszerű példa: ha LONG, akkor stop = entryPrice * 0.98, ha SHORT akkor entryPrice * 1.02
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
    public synchronized void cleanupBadTrades() {
        Instant now = Instant.now();
        badTrades.entrySet().removeIf(entry ->
                now.isAfter(entry.getValue().plusSeconds(5 * 3600))
        );
    }

    public List<TradeDto> getActiveTrades() {
        return List.copyOf(activeTrades);
    }

    private BigDecimal calculateQtyForSymbol(SymbolInfo info, BigDecimal lastPrice) {
        // Lekérjük a symbolhoz tartozó metaadatokat
        if (info == null) {
            throw new IllegalArgumentException("SymbolInfo not found for: " + info.getSymbol());
        }

        // Lot size filter
        SymbolInfo.Filter lotSize = info.getFilter("LOT_SIZE");
        BigDecimal minQty   = new BigDecimal(lotSize.getMinQty());
        BigDecimal maxQty   = new BigDecimal(lotSize.getMaxQty());
        BigDecimal stepSize = new BigDecimal(lotSize.getStepSize());

        // Min notional filter (ha van)
        SymbolInfo.Filter notionalFilter = info.getFilter("MIN_NOTIONAL");
        BigDecimal minNotional = notionalFilter != null && notionalFilter.getNotional() != null
                ? new BigDecimal(notionalFilter.getNotional())
                : BigDecimal.ZERO;

        // Példa: balance egy részével számolunk
        BigDecimal usdtBalance = BigDecimal.valueOf(50.0);
        BigDecimal rawQty = usdtBalance.divide(lastPrice, 8, RoundingMode.DOWN);

        // Ellenőrizzük a notional-t
        BigDecimal orderValue = rawQty.multiply(lastPrice);
        if (orderValue.compareTo(minNotional) < 0) {
            rawQty = minNotional.divide(lastPrice, 8, RoundingMode.UP);
        }

        // Illesszük a stepSize-hoz
        int precision = stepSize.stripTrailingZeros().scale();
        BigDecimal adjusted = rawQty.setScale(precision, RoundingMode.DOWN);

        // Határok közé szorítás
        if (adjusted.compareTo(minQty) < 0) {
            adjusted = minQty;
        }
        if (adjusted.compareTo(maxQty) > 0) {
            adjusted = maxQty;
        }

        return adjusted;
    }
}
