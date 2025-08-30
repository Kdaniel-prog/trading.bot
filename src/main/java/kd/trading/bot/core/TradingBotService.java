package kd.trading.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.interfaces.MarketDataListener;
import kd.trading.bot.interfaces.AccountDataListener;
import kd.trading.bot.model.*;
import kd.trading.bot.service.*;
import kd.trading.bot.session.BinanceSessionManager;
import kd.trading.bot.util.BinanceEventConverter;
import kd.trading.bot.websocket.BinanceMarketWebSocketClient;
import kd.trading.bot.websocket.AccountWebSocketService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradingBotService implements MarketDataListener, AccountDataListener {
    final BinanceSessionManager sessionManager;
    final TradableSymbolService symbolService;
    final MarketDataPipelineService pipelineService;
    final TradeService tradeService;
    final ObjectMapper mapper;
    final BinanceRestClient restClient;
    final TradeTrackerService tracker;
    final TradingConfig tradingConfig;
    final BinanceEventConverter converter;
    final MarketDataParserService marketDataParserService;
    final ControlTradesService controlTradesService;

    private volatile Set<SymbolInfo> tradableSymbols;
    private double profit = 0.0;

    @PostConstruct
    private void init() throws URISyntaxException {
        refreshTradableSymbols();
        String listenKey = sessionManager.getListenKey();

        String wsUrlMain = "wss://fstream.binance.com/ws/!ticker@arr";
        BinanceMarketWebSocketClient client = new BinanceMarketWebSocketClient(new URI(wsUrlMain), this);
        client.connect();

        //3. check trade.
        String wsUrl =  "wss://stream.binancefuture.com/ws/" + listenKey;
        AccountWebSocketService tradeClient = new AccountWebSocketService(wsUrl,this);
        tradeClient.connect();
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

        //3. delete open trades
        tradeService.closeOpenTrades();
    }

    @Override
    public void onChangeData(String message) {
        List<BinanceTickerData> coins = marketDataParserService.parseMessage(message);
        controlTradesService.checkTrades(coins);
    }

    @Override
    public void onTradeData(String message) {
        Object dto = converter.convert(message);

        if (dto instanceof AccountUpdateDto acc) {
            // unrealized és cumulative realized profit
            /**
            if (!acc.a.P.isEmpty()) {
                AccountUpdateDto.Position pos = acc.a.P.get(0);
                System.out.println("Unrealized PnL: " + pos.up);
                System.out.println("Cumulative Realized PnL: " + pos.cr);
            }
             */
        } else if (dto instanceof OrderTradeUpdateDto order) {
            String status = order.o.X;
            if ("FILLED".equals(status)) {
                String realized = order.o.rp; // realized profit
                String side = order.o.S;
                String qty = order.o.q;
                String price = order.o.p;
                profit += Double.parseDouble(order.o.rp);
                System.out.printf("Trade CLOSED: %s %s @ %s | Profit/Loss: %s%n",
                        side, qty, price, realized);
                System.out.printf("All profit: %s%n", profit);

                boolean removed = TradeService.activeTrades.removeIf(
                        t -> t.getSymbol().getSymbol().equals(order.o.s)
                );
                if (removed) {
                    log.info("Removed trade with symbol: {}", order.o.s);
                } else {
                    log.warn("No active trade found for symbol: {}", order.o.s);
                }
            }
        }
    }
}
