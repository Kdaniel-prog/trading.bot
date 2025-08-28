package kd.trading.bot.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.service.TradeService;
import kd.trading.bot.service.TradeTrackerService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.framing.PingFrame;

import java.net.URI;
import java.util.concurrent.*;

@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class TradeWebSocketService extends WebSocketClient {

    final TradeService tradeService;
    final ObjectMapper mapper;
    final TradeTrackerService tracker;
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    final TradingConfig tradingConfig;

    volatile boolean reconnecting = false;

    public TradeWebSocketService(String serverUri,
                                 TradeService tradeService,
                                 ObjectMapper mapper,
                                 TradeTrackerService tracker,
                                 TradingConfig tradingConfig) {
        super(URI.create(serverUri));
        this.tradeService = tradeService;
        this.mapper = mapper;
        this.tracker = tracker;
        this.tradingConfig = tradingConfig;
        // rendszeres ping frame küldés 20 másodpercenként
        scheduler.scheduleAtFixedRate(this::sendPingFrame, 20, 20, TimeUnit.SECONDS);
    }

    private void handleTradeUpdate(JsonNode node) {
        String symbol = node.get("o").get("s").asText();
        String side = node.get("o").get("S").asText();
        String status = node.get("o").get("X").asText();
        double qty = node.get("o").get("q").asDouble();
        double price = node.get("o").get("ap").asDouble();

        log.info("TRADE UPDATE: {} {} {} qty={} price={}", symbol, side, status, qty, price);

        if ("FILLED".equals(status)) {
            tracker.recordTrade(symbol, side, qty, price, price);
        }
    }

    private void handleAccountUpdate(JsonNode node) {
        JsonNode balances = node.get("a").get("B");
        balances.forEach(b -> {
            String asset = b.get("a").asText();
            double walletBalance = b.get("wb").asDouble();
            if ("USDT".equals(asset)) {
                tracker.updateBalance(walletBalance);
                log.info("ACCOUNT UPDATE: {} walletBalance={}", asset, walletBalance);
            }
        });
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        log.info("Connected to Binance User Data Stream WebSocket");
        reconnecting = false;
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonNode node = mapper.readTree(message);
            String eventType = node.get("e").asText();

            switch (eventType) {
                case "executionReport":
                    handleTradeUpdate(node);
                    break;
                case "outboundAccountUpdate":
                    handleAccountUpdate(node);
                    break;
                default:
                    log.debug("Unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to parse WebSocket message: {}", message, e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.warn("WebSocket closed: code={} reason={} remote={}", code, reason, remote);
        scheduleReconnect();
    }

    @Override
    public void onError(Exception e) {
        log.error("WebSocket error", e);
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

    private void scheduleReconnect() {
        if (reconnecting) return;
        reconnecting = true;

        scheduler.schedule(() -> {
            try {
                // ha kell új listenKey:
                // String listenKey = restClient.startUserDataStream();
                // URI newUri = URI.create("wss://fstream.binance.com/ws/" + listenKey);
                // this.uri = newUri; // ha dinamikusan frissíted

                log.info("Reconnecting WebSocket...");
                this.reconnectBlocking(); // blokkoló reconnect
            } catch (Exception e) {
                log.error("Reconnect attempt failed", e);
                scheduleReconnect(); // újrapróbálás exponenciális backoff-fal jobb lenne
            }
        }, 5, TimeUnit.SECONDS); // 5s késleltetés
    }
}
