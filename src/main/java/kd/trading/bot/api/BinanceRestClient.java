package kd.trading.bot.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.config.binance.BinanceConfig;
import kd.trading.bot.model.ExchangeInfo;
import kd.trading.bot.model.Signal;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.model.TradeStatus;
import kd.trading.bot.util.SignatureUtil;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Service
@AllArgsConstructor
public class BinanceRestClient {
    ObjectMapper mapper;
    BinanceConfig config;
    HttpClient client;
    SignatureUtil util;

    public String createListenKey() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.restBaseUrl() + "/fapi/v1/listenKey"))
                .header("X-MBX-APIKEY", config.apiKey())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode node = mapper.readTree(response.body());
        return node.get("listenKey").asText();
    }

    public void keepAliveListenKey(String listenKey) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.restBaseUrl() + "/fapi/v1/listenKey?listenKey=" + listenKey))
                .header("X-MBX-APIKEY", config.apiKey())
                .method("PUT", HttpRequest.BodyPublishers.noBody())
                .build();

        client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public List<List<Object>> getKlines(String symbol, String interval, int limit) {
        try {
            String url = String.format("%s/fapi/v1/klines?symbol=%s&interval=%s&limit=%d",
                    config.restBaseUrl(), symbol, interval, limit);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .join();
            // Binance Klines = List<List<Object>>
            return mapper.readValue(response.body(), new TypeReference<>() {});
        } catch (IOException e) {
            log.error("Failed to fetch klines for {}", symbol, e);
            return List.of();
        } catch (Exception e) {
            log.error("Unexpected error while fetching klines for {}", symbol, e);
            return List.of();
        }
    }

    public List<SymbolInfo> getTradableSymbols() {
        try {
            String url = config.restBaseUrl() + "/fapi/v1/exchangeInfo";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            ExchangeInfo exchangeInfo = mapper.readValue(response.body(), ExchangeInfo.class);

            if (exchangeInfo == null || exchangeInfo.getSymbols() == null) {
                throw new RuntimeException("Exchange info not available");
            }

            // cutoff dátum = most - 120 nap
            Instant cutoff = Instant.now().minus(120, java.time.temporal.ChronoUnit.DAYS);

            return exchangeInfo.getSymbols().stream()
                    .filter(s -> "TRADING".equals(s.getStatus()))
                    .filter(s -> "USDT".equals(s.getQuoteAsset()))
                    .filter(s -> Instant.ofEpochMilli(s.getOnboardDate()).isBefore(cutoff)) // 4 hónap filter
                    .toList();

        } catch (Exception e) {
            log.error("Failed to fetch exchangeInfo", e);
            return List.of();
        }
    }

    public TradeStatus placeOrder(String symbol, BigDecimal qty, BigDecimal price, Signal signal) {
        try {
            String side = signal == Signal.LONG ? "BUY" : "SELL";

            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", "LIMIT");
            params.put("quantity", qty.stripTrailingZeros().toPlainString());
            params.put("price", price.stripTrailingZeros().toPlainString());
            params.put("timeInForce", "GTC");
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v1/order?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp;
            try {
                resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // visszaállítjuk az interrupt flag-et
                log.error("HTTP request interrupted for symbol {}", symbol, ie);
                return TradeStatus.ERROR;
            }

            if (resp == null || resp.body() == null) {
                log.error("Null or empty HTTP response for symbol {}", symbol);
                return TradeStatus.ERROR;
            }

            log.info("Order response: {}", resp.body());

            if (resp.body().contains("\"executedQty\":\"0\"")) {
                return TradeStatus.OPEN;
            }

            return TradeStatus.SUCCESS;

        } catch (Exception e) {
            String msg = (e.getMessage() != null) ? e.getMessage() : e.toString();
            log.error("Error placing order for {}: {}", symbol, msg, e);
            return TradeStatus.ERROR;
        }
    }

    public Boolean cancelOrdersForSymbol(String symbol) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v1/allOpenOrders?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .DELETE()
                    .build();

            Optional<HttpResponse<String>> response;
            response = Optional.ofNullable(client.send(request, HttpResponse.BodyHandlers.ofString()));

            return response.isPresent() && response.get().statusCode() == 200;
        } catch (Exception e) {
            String msg = (e.getMessage() != null) ? e.getMessage() : e.toString();
            log.error("Error canceling orders for symbols: {}", msg, e);
            return false;
        }
    }


    public String getUsdtBalance() {
        try {
            long timestamp = System.currentTimeMillis();
            long recvWindow = 5000L;
            String query = "timestamp=" + timestamp + "&recvWindow=" + recvWindow;
            String signature = util.sign(query);
            String url = config.restBaseUrl() + "/fapi/v2/balance?" + query + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());

            if (!root.isArray()) {
                log.error("Unexpected balance response: {}", response.body());
                return "0.0";
            }

            for (JsonNode asset : root) {
                if ("USDT".equals(asset.get("asset").asText())) {
                    String available = asset.get("availableBalance").asText();
                    log.info("USDT available balance = {}", available);
                    return available;
                }
            }
            log.warn("No USDT balance found in response: {}", response.body());
            return "0.0";
        } catch (Exception e) {
            log.error("Failed to fetch USDT balance", e);
            return "0.0";
        }
    }

    public boolean placeStopOrder(SymbolInfo info, BigDecimal qty, BigDecimal stopPrice, Signal signal) {
        try {
            int qtyScale = info.getQuantityScaleOrDefault();
            int priceScale = info.getPriceScaleOrDefault();

            String side = signal == Signal.LONG ? "SELL" : "BUY"; // long pozícióhoz stop SELL kell
            BigDecimal normalizedQty = qty.setScale(qtyScale, RoundingMode.DOWN);
            BigDecimal normalizedStop = stopPrice.setScale(priceScale, RoundingMode.DOWN);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", info.getSymbol());
            params.put("side", side);
            params.put("type", "STOP_MARKET");
            params.put("quantity", normalizedQty.stripTrailingZeros().toPlainString());
            params.put("stopPrice", normalizedStop.stripTrailingZeros().toPlainString());
            params.put("timeInForce", "GTC");
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v1/order?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Stop order response: {}", response.body());

            return response.body().contains("orderId");

        } catch (Exception e) {
            log.error("Error placing stop order for {}", info.getSymbol(), e);
            return false;
        }
    }

    private String buildQueryString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    public boolean placeTakeProfitOrder(SymbolInfo info, BigDecimal qty, BigDecimal stopPrice, Signal signal) {
        try {
            int qtyScale = info.getQuantityScaleOrDefault();
            int priceScale = info.getPriceScaleOrDefault();

            String side = signal == Signal.LONG ? "SELL" : "BUY"; // long TP = SELL, short TP = BUY
            BigDecimal normalizedQty = qty.setScale(qtyScale, RoundingMode.DOWN);
            BigDecimal normalizedStop = stopPrice.setScale(priceScale, RoundingMode.DOWN);

            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", info.getSymbol());
            params.put("side", side);
            params.put("type", "TAKE_PROFIT_MARKET");
            params.put("quantity", normalizedQty.stripTrailingZeros().toPlainString());
            params.put("stopPrice", normalizedStop.stripTrailingZeros().toPlainString());
            params.put("timeInForce", "GTC");
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v1/order?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("Take Profit order response: {}", response.body());

            return response.body().contains("orderId");

        } catch (Exception e) {
            log.error("Error placing take profit order for {}", info.getSymbol(), e);
            return false;
        }
    }

    public TradeStatus finishOrder(String symbol, Signal signal, BigDecimal quantity) {
        try {
            String side = signal == Signal.LONG ? "SELL" : "BUY"; // zárás mindig az ellenkező oldal

            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", "MARKET"); // első körben MARKET order
            params.put("quantity", quantity.stripTrailingZeros().toPlainString());
            params.put("reduceOnly", "true");
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v1/order?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp;
            try {
                resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("HTTP request interrupted when closing order for {}", symbol, ie);
                return TradeStatus.ERROR;
            }

            if (resp == null || resp.body() == null) {
                log.error("Null or empty HTTP response when closing order for {}", symbol);
                return TradeStatus.ERROR;
            }

            log.info("Close order response: {}", resp.body());

            // Binance hibák kezelése
            if (resp.statusCode() >= 400 || resp.body().contains("code")) {
                if (resp.body().contains("PERCENT_PRICE")) {
                    log.warn("Failed due to PERCENT_PRICE filter. Retrying with MARKET...");
                    return retryWithMarket(symbol, side); // fallback
                }
                return TradeStatus.ERROR;
            }

            return TradeStatus.SUCCESS;

        } catch (Exception e) {
            log.error("Exception while finishing order for {}", symbol, e);
            return TradeStatus.ERROR;
        }
    }

    private TradeStatus retryWithMarket(String symbol, String side) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", "MARKET");
            params.put("reduceOnly", "true");
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v1/order?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Retry MARKET close response: {}", resp.body());

            if (resp.statusCode() >= 400) {
                log.error("Retry MARKET order failed for {} with status {}", symbol, resp.statusCode());
                return TradeStatus.ERROR;
            }

            return TradeStatus.SUCCESS;

        } catch (Exception e) {
            log.error("Retry MARKET close failed for {}", symbol, e);
            return TradeStatus.ERROR;
        }
    }
}
