package kd.trading.bot.service;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.config.binance.BinanceConfig;
import kd.trading.bot.listenKey.BinanceListenKeyService;
import kd.trading.bot.websocket.BinanceMarketWebSocketClient;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@RequiredArgsConstructor
public class BinanceMarketService {
    BinanceListenKeyService listenKeyService;
    BinanceConfig config;
    BinanceHistoryService historyService;
    AlgorithmService algorithmService;

    @PostConstruct
    private void run() throws URISyntaxException {
        // 1. Create listen key if needed (for user data)
        listenKeyService.createListenKey();

        // 2. Compose WS URL (for market data, no listenKey needed)
        /**
         * 24 hr rolling window ticker statistics for all symbols. These are NOT the statistics of the UTC day, but a 24hr
         * rolling window from requestTime to 24hrs before. Note that only tickers that have changed will be present in the array.
         */

        String wsUrlMain = "wss://fstream.binance.com/ws/!ticker@arr";
        BinanceMarketWebSocketClient client = new BinanceMarketWebSocketClient(new URI(wsUrlMain), historyService, algorithmService);
        client.connect();
    }

}
