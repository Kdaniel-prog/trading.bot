package kd.trading.bot.core;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.config.binance.BinanceConfig;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.interfaces.AccountDataListener;
import kd.trading.bot.interfaces.MarketDataListener;
import kd.trading.bot.service.*;
import kd.trading.bot.session.BinanceSessionManager;
import kd.trading.bot.util.BinanceEventConverter;
import kd.trading.bot.websocket.AccountWebSocketService;
import kd.trading.bot.websocket.BinanceMarketWebSocketClient;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradingBot implements MarketDataListener, AccountDataListener {

    final TradableSymbolService tradableSymbolService;
    final BinanceSessionManager sessionManager;
    final MarketDataPipelineService pipelineService;
    final BinanceEventConverter converter;
    final HandleOrderUpdateService handleOrderUpdateService;
    final TradeCheckingService checkingService;
    final BinanceConfig binanceConfig;
    final TradeService tradeService;
    final TradingConfig tradingConfig;

    boolean isRunning = false;
    BinanceMarketWebSocketClient marketClient;
    AccountWebSocketService accountClient;

    @PostConstruct
    private void init() throws URISyntaxException {
        if (!tradingConfig.enabled()) {
            log.warn("🚫 Trading is DISABLED in configuration (trading.enabled=false)");
            return;
        }

        try {
            startTradingBot();
        } catch (Exception e) {
            log.error("❌ Failed to start TradingBot: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void startTradingBot() throws URISyntaxException {
        log.info("🤖 Starting TradingBot");

        // 0. Load active trades (if app restart we will load the trades)
        tradeService.loadActiveOrdersOnStartup();
        TradeService.BANNED_SYMBOL.addAll(tradingConfig.banSymbol());

        // 1. Start Binance market WebSocket
        String wsUrlMain = "wss://fstream.binance.com/stream?streams=!ticker@arr";
        marketClient = new BinanceMarketWebSocketClient(new URI(wsUrlMain), this);
        marketClient.connect();

        log.info("📡 Market data WebSocket connected: {}", wsUrlMain);

        // 2. Start Account Update WebSocket
        String wsUrl = binanceConfig.wsBaseUrl() + sessionManager.getListenKey();
        accountClient = new AccountWebSocketService(wsUrl, this);
        accountClient.connect();

        log.info("👤 Account data WebSocket connected: {}", wsUrl);

        isRunning = true;
        log.info("✅ TradingBot started successfully - Live trading ENABLED");
    }

    /**
     * Gracefully stop the trading bot
     */
    public void stopTradingBot() {
        if (!isRunning) {
            log.info("🔄 TradingBot is already stopped");
            return;
        }

        log.info("🛑 Stopping TradingBot...");

        try {
            if (marketClient != null) {
                marketClient.close();
                log.info("📡 Market WebSocket disconnected");
            }

            if (accountClient != null) {
                accountClient.close();
                log.info("👤 Account WebSocket disconnected");
            }

            log.info("✅ TradingBot stopped successfully");

        } catch (Exception e) {
            log.error("❌ Error stopping TradingBot: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if trading bot is currently running
     */
    public boolean isRunning() {
        return !isRunning || !tradingConfig.enabled();
    }

    /**
     * Handle account/trade updates (order fills, balance changes, etc.)
     */
    @Override
    public void onTradeData(String message) {
        if (isRunning()) {
            return;
        }

        try {
            Object dto = converter.convert(message);
            log.debug("📊 Trade data: {}", dto);
            handleOrderUpdateService.controlOrderListsAndProfit(dto);
        } catch (Exception e) {
            log.error("❌ Error processing trade data: {}", e.getMessage());
        }
    }

    /**
     * Market data processing - triggers trading algorithm every 15 minutes
     */
    @Override
    public void onMarketData(String message) {
        if (isRunning()) {
            return;
        }

        try {
            log.debug("📈 Processing market data for algorithm analysis");
            pipelineService.processMessage(message, tradableSymbolService.getTradableSymbols());
        } catch (Exception e) {
            log.error("❌ Error processing market data: {}", e.getMessage());
        }
    }

    /**
     * Real-time price updates - monitors active trades for P&L
     */
    @Override
    public void onChangeData(String message) {
        if (isRunning()) {
            return;
        }

        try {
            // Only process if we have active trades
            if (!TradeService.getActiveOrderList().isEmpty()) {
                checkingService.calculateTradeInfos(message);
            }
        } catch (Exception e) {
            log.error("❌ Error processing price change data: {}", e.getMessage());
        }
    }

}