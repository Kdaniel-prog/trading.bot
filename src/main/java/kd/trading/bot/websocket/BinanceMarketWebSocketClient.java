package kd.trading.bot.websocket;

import kd.trading.bot.interfaces.MarketDataListener;
import kd.trading.bot.service.TelegramCommandService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.PingFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class BinanceMarketWebSocketClient extends WebSocketClient {

    static final long INTERVAL_MS = 15_000; // 25 sec
    static final long INTERVAL_MS2 = 180_000; // 5 minutes
    static final long PING_INTERVAL_MS = 30_000; // 30 sec ping

    final MarketDataListener listener;
    volatile long lastProcessed = 0;
    volatile long lastProcessed2 = 0;
    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public BinanceMarketWebSocketClient(URI serverUri, MarketDataListener listener) {
        super(serverUri);
        this.listener = listener;
    }

    @Scheduled(fixedRate = 1 * 60 * 1000) // 1 percenként
    public void keepAlive() {
        if (this.isOpen()) {
            this.sendPing();
            log.debug("Sent PING to Binance WS");
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakeData) {
        log.info("Connected to Binance Futures Market WebSocket");
    }

    @Override
    public void onMessage(String message) {
        if(TelegramCommandService.SLEEP_MODE) return;
        long now = System.currentTimeMillis();

        //Check active trades
        if(now - lastProcessed >= INTERVAL_MS){
            lastProcessed = now;
            listener.onChangeData(message);
        }

        // Use Algo to rate coins
        if (now - lastProcessed2 >= INTERVAL_MS2) {
            log.debug(message);
            lastProcessed2 = now;
            listener.onMarketData(message);
            log.info("WS Market algo");

        }

    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("Closed: {} | code {}", reason, code);
        scheduler.schedule(() -> {
            try {
                log.info("Reconnecting...");
                this.reconnect();
            } catch (Exception e) {
                log.error("Reconnect failed", e);
            }
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    public void onError(Exception ex) {
        log.error("Error: ", ex);
    }

}

