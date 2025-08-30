package kd.trading.bot.core;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.interfaces.AccountDataListener;
import kd.trading.bot.interfaces.MarketDataListener;
import kd.trading.bot.model.TradeDto;
import kd.trading.bot.service.TradableSymbolService;
import kd.trading.bot.service.TradeService;
import kd.trading.bot.service.MarketDataPipelineService;
import kd.trading.bot.session.BinanceSessionManager;
import kd.trading.bot.websocket.AccountWebSocketService;
import kd.trading.bot.websocket.BinanceMarketWebSocketClient;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradingBot implements MarketDataListener, AccountDataListener {
     TradableSymbolService tradableSymbolService;
     BinanceSessionManager sessionManager;
     MarketDataPipelineService pipelineService;

    @PostConstruct
    private void init() throws URISyntaxException {
        //1. start Binance market ws
        String wsUrlMain = "wss://fstream.binance.com/ws/!ticker@arr";
        BinanceMarketWebSocketClient client = new BinanceMarketWebSocketClient(new URI(wsUrlMain), this);
        client.connect();

        //2. Start Account Update ws
        String wsUrl =  "wss://stream.binancefuture.com/ws/" + sessionManager.getListenKey();
        AccountWebSocketService tradeClient = new AccountWebSocketService(wsUrl,this);
        tradeClient.connect();
    }

    @Override
    public void onTradeData(String message) {
        //rate coins and trade
        log.debug(message);
        pipelineService.processMessage(message, tradableSymbolService.getTradableSymbols());

        for(TradeDto trade: TradeService.activeTrades) {
            log.debug("Active trades symbols: {}", trade.getSymbol());
        }

        for(String symbol: TradeService.openTrades) {
            log.debug("Open trades symbols: {}", symbol);
        }
    }

    @Override
    public void onMarketData(String message) {

    }

    @Override
    public void onChangeData(String message) {

    }
}
