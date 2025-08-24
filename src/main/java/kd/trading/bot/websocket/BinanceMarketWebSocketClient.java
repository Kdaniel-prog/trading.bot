package kd.trading.bot.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.model.BinanceTickerData;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.List;

@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class BinanceMarketWebSocketClient extends WebSocketClient {
    static final long INTERVAL_MS = 60_000; // 30 seconds
    long lastProcessed = 0;
    List<BinanceTickerData> tickers;

    public BinanceMarketWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakeData) {
        log.info("Connected to Binance Futures Market WebSocket");
    }

    @Override
    public void onMessage(String message) {
        long now = System.currentTimeMillis();
        try {
            if (now - lastProcessed >= INTERVAL_MS) {
                ObjectMapper mapper = new ObjectMapper();
                tickers = mapper.readValue(message, new TypeReference<>(){});


                log.info("Coin received: {}", tickers.size());

            }
        } catch (Exception e) {
            log.error("Failed to parse 24h ticker array: {}", message, e);
        }
        //2. fázis
        if (now - lastProcessed >= INTERVAL_MS) {
            System.out.println("teszt");
            lastProcessed = now;
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("Closed: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        log.error("Error: ", ex);
    }


}
