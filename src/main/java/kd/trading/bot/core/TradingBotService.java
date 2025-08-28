package kd.trading.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.model.MarketDataListener;
import kd.trading.bot.model.Signal;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.service.*;
import kd.trading.bot.session.BinanceSessionManager;
import kd.trading.bot.websocket.BinanceMarketWebSocketClient;
import kd.trading.bot.websocket.TradeWebSocketService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
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
    final TradeService tradeService;
    final ObjectMapper mapper;
    final BinanceRestClient restClient;
    final TradeTrackerService tracker;
    final TradingConfig tradingConfig;

    private volatile Set<SymbolInfo> tradableSymbols;
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

    public void runTradingCycle() {
        RankingService.RankedCoins ranked = pipelineService.getLatestResult();
        if (ranked == null) return;

        if (tradeService.getActiveTrades().size() >= 6) return;

        ranked.top().stream()
                .filter(t -> t.getSignal() != Signal.NO_TRADE) // csak ha van érvényes jel
                .limit(2)
                .forEach(t -> {
                            Optional<SymbolInfo> symbol = tradableSymbols.stream()
                                    .filter(s -> s.getSymbol().equals(t.getSymbol()))
                                    .findFirst();
                            tradeService.openTrade(t.getSignal(), BigDecimal.valueOf(t.getLastPrice()), symbol.get());
                });

        ranked.bottom().stream()
                .filter(t -> t.getSignal() != Signal.NO_TRADE)
                .limit(2)
                .forEach(t -> {
                    Optional<SymbolInfo> symbol = tradableSymbols.stream()
                            .filter(s -> s.getSymbol().equals(t.getSymbol()))
                            .findFirst();
                    tradeService.openTrade(t.getSignal(), BigDecimal.valueOf(t.getLastPrice()), symbol.get());
                });
    }

    @Override
    public void onMarketData(String message) {
        //get symbols
        pipelineService.processMessage(message, tradableSymbols);

        //2. trade
        runTradingCycle();

        //3. check trade.
        String wsUrl = "wss://stream.binancefuture.com/ws/" + sessionManager.getListenKey();
        TradeWebSocketService tradeClient = new TradeWebSocketService(wsUrl, tradeService, mapper, tracker, tradingConfig);
        tradeClient.connect();
    }
}
