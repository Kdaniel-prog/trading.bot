package kd.trading.bot.websocket;

import kd.trading.bot.interfaces.AccountDataListener;
import kd.trading.bot.service.TelegramCommandService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.PingFrame;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class AccountWebSocketService extends WebSocketClient  {
    static final long INTERVAL_MS = 180_000; // 3 minutes
    static final long PING_INTERVAL_MS = 30_000; // 30 sec ping

    final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    final AccountDataListener accountDataListener;

    public AccountWebSocketService(String serverUri, AccountDataListener accountDataListener) {
        super(URI.create(serverUri));
        this.accountDataListener = accountDataListener;
    }

    public void sendPingFrame() {
        if (this.isOpen()) {
            try {
                this.sendFrame(new PingFrame());
                log.debug("Ping frame sent");
            } catch (Exception e) {
                log.warn("Ping failed", e);
            }
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakeData) {
        log.info("Connected Account Data WebSocket");

        // start ping/pong to keep alive
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (this.isOpen()) this.sendPingFrame();
            } catch (Exception e) {
                log.warn("Failed to send ping", e);
            }
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onMessage(String message) {
        if(TelegramCommandService.SLEEP_MODE) return;
        this.accountDataListener.onTradeData(message);
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
