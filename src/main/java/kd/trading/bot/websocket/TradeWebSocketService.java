package kd.trading.bot.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.model.Signal;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.model.TradeDto;
import kd.trading.bot.service.TradeService;
import kd.trading.bot.service.TradeTrackerService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.framing.PingFrame;

import java.math.BigDecimal;
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
        try {
            JsonNode order = node.get("o");

            String eventTime = node.has("E") ? String.valueOf(node.get("E").asLong()) : "?";
            String symbol = order.get("s").asText();
            String side = order.get("S").asText();
            String type = order.get("o").asText();
            String status = order.get("X").asText(); // current order status
            String execType = order.get("x").asText(); // execution type
            long orderId = order.get("i").asLong();
            double qty = order.get("q").asDouble();
            double filledQty = order.get("z").asDouble();
            double avgPrice = order.get("ap").asDouble();
            double lastPrice = order.get("L").asDouble();

            log.info(
                    "EXECUTION REPORT: time={} orderId={} symbol={} side={} type={} status={} execType={} qty={} filledQty={} lastPrice={} avgPrice={}",
                    eventTime, orderId, symbol, side, type, status, execType, qty, filledQty, lastPrice, avgPrice
            );

            if ("FILLED".equals(status)) {
                tracker.recordTrade(symbol, Signal.valueOf(side), qty, avgPrice, avgPrice);

                // keresd meg az activeTrades-ben a tradet
                TradeDto trade = TradeService.activeTrades.stream()
                        .filter(s -> s.getSymbol().equals(symbol))
                        .findFirst()
                        .orElse(null);

                if (trade != null) {
                    BigDecimal price = BigDecimal.valueOf(avgPrice);

                    // STOP LIMIT ellenőrzés
                    if (trade.getSignal() == Signal.LONG && price.compareTo(BigDecimal.valueOf(trade.getStopLimit())) <= 0) {
                        log.info("STOP LIMIT hit for {}", symbol);
                        //closeTrade(trade, "STOP LIMIT hit", avgPrice);
                    } else if (trade.getSignal() == Signal.SHORT && price.compareTo(BigDecimal.valueOf(trade.getStopLimit())) >= 0) {
                        log.info("STOP LIMIT hit for {}", symbol);
                        //closeTrade(trade, "STOP LIMIT hit", avgPrice);
                    }

                    // WIN LIMIT ellenőrzés
                    if (trade.getSignal() == Signal.LONG && price.compareTo(BigDecimal.valueOf(trade.getWinLimit())) >= 0) {
                        log.info("WIN LIMIT hit for {}", symbol);
                        //closeTrade(trade, "WIN LIMIT hit", avgPrice);
                    } else if (trade.getSignal() == Signal.SHORT && price.compareTo(BigDecimal.valueOf(trade.getWinLimit())) <= 0) {
                        log.info("WIN LIMIT hit for {}", symbol);
                        //closeTrade(trade, "WIN LIMIT hit", avgPrice);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error handling executionReport: {}", node, e);
        }
    }

    private void closeTrade(TradeDto trade, String reason, double exitPrice) {
        try {
            log.info("Closing trade {} because {}", trade.getSymbol(), reason);

            Signal opposite = trade.getSignal() == Signal.LONG ? Signal.SHORT : Signal.LONG;

            //TradeService.closeTrade(trade);
            //tracker.recordTrade(trade.getSymbol(), trade.getSignal(), trade.getQuantity(), trade.getEntryPrice(), exitPrice);

        } catch (Exception e) {
            log.error("Failed to close trade {}", trade.getSymbol(), e);
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
        // minden nyers üzenet logolása debug szinten
        log.debug("RAW WS MESSAGE: {}", message);

        try {
            JsonNode node = mapper.readTree(message);
            String eventType = node.has("e") ? node.get("e").asText() : "UNKNOWN";

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
            log.error("Failed to parse WebSocket message. Raw message: {}", message, e);
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
