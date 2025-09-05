package kd.trading.bot.core;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.config.binance.BinanceConfig;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.interfaces.AccountDataListener;
import kd.trading.bot.interfaces.MarketDataListener;
import kd.trading.bot.model.TradeDto;
import kd.trading.bot.service.*;
import kd.trading.bot.session.BinanceSessionManager;
import kd.trading.bot.util.BinanceEventConverter;
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
     BinanceEventConverter converter;
     AccountProfitService accountProfitService;
     TradeCheckingService checkingService;
     BinanceConfig binanceConfig;
     TradeService tradeService;
     TradingConfig tradingConfig;

    @PostConstruct
    private void init() throws URISyntaxException {
        // 0. get active trades (if app restart we will load the trades)
        tradeService.loadActiveOrdersOnStartup();
        TradeService.BANNED_SYMBOL.addAll(tradingConfig.banSymbol());

        //1. start Binance market ws
        String wsUrlMain = "wss://fstream.binance.com/stream?streams=!ticker@arr";
        BinanceMarketWebSocketClient client = new BinanceMarketWebSocketClient(new URI(wsUrlMain), this);
        client.connect();

        //2. Start Account Update ws
        String wsUrl =  binanceConfig.wsBaseUrl() + sessionManager.getListenKey();
        AccountWebSocketService tradeClient = new AccountWebSocketService(wsUrl,this);
        tradeClient.connect();
    }

    @Override
    public void onTradeData(String message) {
        Object dto = converter.convert(message);
        log.debug("dto :{}", dto);
        accountProfitService.controlOrderListsAndProfit(dto);
    }

    /**
     * Itt 5 percenként tradelünk
     * @param message
     */
    @Override
    public void onMarketData(String message) {
        pipelineService.processMessage(message, tradableSymbolService.getTradableSymbols());
    }

    /**
     * Itt x másodpercenként jön marketről adat és itt nézük mennyi a profit és loss a coinon.
     * @param message
     */
    @Override
    public void onChangeData(String message) {
        if(!TradeService.getActiveOrderList().isEmpty()){
            checkingService.calculateTradeInfos(message);
        }
    }
}
