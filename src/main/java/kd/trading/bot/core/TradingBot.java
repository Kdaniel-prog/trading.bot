package kd.trading.bot.core;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.interfaces.AccountDataListener;
import kd.trading.bot.interfaces.MarketDataListener;
import kd.trading.bot.service.TradableSymbolService;
import kd.trading.bot.service.TradingService;
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
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradingBot implements MarketDataListener, AccountDataListener {
    final TradingService tradingService;
    final TradableSymbolService tradableSymbolService;
    final BinanceSessionManager sessionManager;

    @PostConstruct
    private void init() throws URISyntaxException {
        //2. check market
        String wsUrlMain = "wss://fstream.binance.com/ws/!ticker@arr";
        BinanceMarketWebSocketClient client = new BinanceMarketWebSocketClient(new URI(wsUrlMain), this);
        client.connect();

        //3. check trade.
        String wsUrl =  "wss://stream.binancefuture.com/ws/" + sessionManager.getListenKey();
        AccountWebSocketService tradeClient = new AccountWebSocketService(wsUrl,this);
        tradeClient.connect();
    }

    @Override
    public void onTradeData(String message) {

    }

    @Override
    public void onMarketData(String message) {

    }

    @Override
    public void onChangeData(String message) {

    }
}
