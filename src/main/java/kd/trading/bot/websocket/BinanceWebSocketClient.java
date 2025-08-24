package kd.trading.bot.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * Üzenet kezelés
 */
@Slf4j
public class BinanceWebSocketClient extends WebSocketClient {

    public BinanceWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakeData) {
        log.info("Connected to Binance Futures WebSocket");
    }

    @Override
    public void onMessage(String message) {
        log.info("Received: " + message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("Closed: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        log.info("Errors: {} ",ex.getMessage());
    }

}
