package kd.trading.bot.service;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.model.Signal;
import kd.trading.bot.model.TradeDto;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    private static final int MAX_TRADES = 4;

    public synchronized void openTrade(String symbol, Signal signal, double entryPrice) {
        if (activeTrades.size() >= MAX_TRADES) {
            log.info("Max trades reached, skipping {}", symbol);
            return;
        }
        if (badTrades.containsKey(symbol)) {
            log.info("Symbol {} is in badTrades, skipping", symbol);
            return;
        }

        double stopLimit = entryPrice * (1 - Double.parseDouble(tradingConfig.stopLimit()) / 100.0);
        double winLimit = entryPrice * (1 + Double.parseDouble(tradingConfig.winLimit()) / 100.0);

        TradeDto trade = new TradeDto();
        trade.setSymbol(symbol);
        trade.setSignal(signal);
        trade.setEntryPrice(entryPrice);
        trade.setStopLimit(stopLimit);
        trade.setWinLimit(winLimit);
        trade.setOpenedAt(Instant.now());

        // REST API order indítása
        try {
            boolean success = restClient.placeOrder(symbol, signal, tradingConfig.tradeWithPercent());
            if(success) {
                activeTrades.add(trade);
                log.info("Opened trade: {} {} @{}", signal, symbol, entryPrice);
            }
        } catch (Exception e) {
            log.error("Failed to place order for {}", symbol, e);
        }
    }

    public synchronized void closeTrade(TradeDto trade, boolean bad) {
        try {
            restClient.closeOrder(trade.getSymbol());
            activeTrades.remove(trade);
            log.info("Closed trade: {} {}", trade.getSignal(), trade.getSymbol());
            if (bad) {
                badTrades.put(trade.getSymbol(), Instant.now());
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
}
