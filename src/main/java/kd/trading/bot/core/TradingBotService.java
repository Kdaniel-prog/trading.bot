package kd.trading.bot.core;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.model.MarketDataListener;
import kd.trading.bot.service.RankingService;
import kd.trading.bot.session.BinanceSessionManager;
import kd.trading.bot.service.MarketDataPipelineService;
import kd.trading.bot.service.TradableSymbolService;
import kd.trading.bot.websocket.BinanceMarketWebSocketClient;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradingBotService implements MarketDataListener {

    static final long INTERVAL_MS = 180_000; // 3 minutes

    final BinanceSessionManager sessionManager;
    final TradableSymbolService symbolService;
    final MarketDataPipelineService pipelineService;

    private volatile Set<String> tradableSymbols;
    private volatile long lastProcessed = 0;

    @PostConstruct
    private void init() throws URISyntaxException {
        refreshTradableSymbols();

        String wsUrlMain = "wss://fstream.binance.com/ws/!ticker@arr";
        BinanceMarketWebSocketClient client = new BinanceMarketWebSocketClient(new URI(wsUrlMain), this);
        client.connect();

    }

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void refreshTradableSymbols() {
        try {
            tradableSymbols = symbolService.getTradableSymbols();
            log.info("Refreshed tradableSymbols. size: {}", tradableSymbols.size());
        } catch (Exception e) {
            log.error("Error refreshing tradableSymbols", e);
        }
    }

    public RankingService.RankedCoins getLatestResult() {
        return pipelineService.getLatestResult();
    }

    @Override
    public void onMarketData(String message) {
        long now = System.currentTimeMillis();
        if (now - lastProcessed < INTERVAL_MS) {
            return; // skip, not 3 minutes yet
        }
        lastProcessed = now;

        pipelineService.processMessage(message, tradableSymbols);
    }
}
