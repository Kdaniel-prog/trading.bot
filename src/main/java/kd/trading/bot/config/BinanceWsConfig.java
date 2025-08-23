package kd.trading.bot.config;

import kd.trading.bot.service.BinanceListenKeyService;
import kd.trading.bot.websocket.BinanceWebSocketClient;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@RequiredArgsConstructor
public class BinanceWsConfig {
    BinanceListenKeyService listenKeyService;
    BinanceConfig config;

    @Bean
    public BinanceWebSocketClient binanceWebSocketClient() throws Exception {
        // 1. listenKey létrehozása
        String listenKey = listenKeyService.createListenKey();

        // 2. WS URL összeállítása
        String wsUrl = config.wsBaseUrl() + "/" + listenKey;

        // 3. WebSocket kliens példányosítása és visszaadása
        BinanceWebSocketClient client = new BinanceWebSocketClient(new URI(wsUrl));
        client.connect();
        return client;
    }
}
