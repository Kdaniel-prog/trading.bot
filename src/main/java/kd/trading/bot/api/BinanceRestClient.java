package kd.trading.bot.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.config.binance.BinanceConfig;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.ExchangeInfo;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.util.MessageParser;
import kd.trading.bot.util.SignatureUtil;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Service
@AllArgsConstructor
public class BinanceRestClient {
    ObjectMapper mapper;
    BinanceConfig config;
    HttpClient client;
    SignatureUtil util;
    TradingConfig tradingConfig;
    MessageParser parser;

    public List<SymbolInfo> getBinanceTradableSymbols() {
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
            Instant cutoff = LocalDateTime.now(ZoneOffset.UTC).minusMonths(tradingConfig.coinMinMonth()).toInstant(ZoneOffset.UTC);

            return exchangeInfo.getSymbols().stream()
                    .filter(s -> !s.getSymbol().isBlank())
                    .filter(s -> "TRADING".equals(s.getStatus()))
                    .filter(s -> tradingConfig.coinType().equals(s.getQuoteAsset()))
                    .filter(s -> Instant.ofEpochMilli(s.getOnboardDate()).isBefore(cutoff)) // 4 hónap filter
                    .toList();

        } catch (Exception e) {
            log.error("Failed to fetch exchangeInfo", e);
            return List.of();
        }
    }

    public String createListenKey() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.restBaseUrl() + "/fapi/v1/listenKey"))
                .header("X-MBX-APIKEY", config.apiKey())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode node = mapper.readTree(response.body());
        JsonNode listenKeyNode = node.get("listenKey");

        if (listenKeyNode == null || listenKeyNode.isNull()) {
            throw new RuntimeException("Nem található listenKey a válaszban: " + response.body());
        }

        return listenKeyNode.asText();
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

            if (response.body() == null) {
                throw new RuntimeException("Empty response from Binance getKlines API");
            }

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

    public void placeOrder(String symbol, BigDecimal qty, BigDecimal price, Signal signal) {
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

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp == null || resp.body() == null) {
                log.error("Null or empty HTTP response for symbol {}", symbol);
            }

        } catch (Exception e) {
            String msg = (e.getMessage() != null) ? e.getMessage() : e.toString();
            log.error("Error placing order for {}: {}", symbol, msg, e);
        }
    }

    public void placeMarketOrder(String symbol, BigDecimal qty, Signal signal) {
        try {
            String side = signal == Signal.LONG ? "BUY" : "SELL";

            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", "MARKET");
            params.put("quantity", qty.stripTrailingZeros().toPlainString());
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

            if (resp == null || resp.body() == null) {
                log.error("Null or empty HTTP response for symbol {}", symbol);
            } else {
                log.info("Order response: {}", resp.body());
            }

        } catch (Exception e) {
            String msg = (e.getMessage() != null) ? e.getMessage() : e.toString();
            log.error("Error placing market order for {}: {}", symbol, msg, e);
        }
    }

    public void changeLeverage(String symbol) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("leverage", String.valueOf(tradingConfig.leverage()));
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v1/leverage?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Leverage change response: {}", resp.body());

        } catch (Exception e) {
            log.error("Error changing leverage for {}: {}", symbol, e.getMessage(), e);
        }
    }

    public void cancelOrdersForSymbol(String symbol) {
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

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 200) {
                log.info("Successfully canceled all open orders for symbol: {}", symbol);
            } else {
                log.warn("Failed to cancel orders for symbol: {} | status code: {} | body: {}",
                        symbol, status, response.body());
            }

        } catch (Exception e) {
            log.error("Error canceling orders for symbol: {}", symbol, e);
        }
    }

    public void closeMarketOrder(SymbolInfo info, BigDecimal qty, Signal signal) {
        try {
            int qtyScale = info.getQuantityScaleOrDefault();
            BigDecimal normalizedQty = qty.setScale(qtyScale, RoundingMode.DOWN);

            // Ha LONG pozíciót nyitottunk (BUY), akkor záráshoz SELL kell
            String side = signal == Signal.LONG ? "SELL" : "BUY";

            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol", info.getSymbol());
            params.put("side", side);
            params.put("type", "MARKET");
            params.put("quantity", normalizedQty.stripTrailingZeros().toPlainString());
            params.put("reduceOnly", "true"); // !!! fontos, nehogy új pozíciót nyisson
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v1/order?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            client.send(request, HttpResponse.BodyHandlers.ofString());

        } catch (Exception e) {
            log.error("Error closing market order for {}", info.getSymbol(), e);
        }
    }

    public BigDecimal getOpenPositionQty(String symbol) {
        try {
            long ts = System.currentTimeMillis();
            String query = "symbol=" + symbol + "&timestamp=" + ts;
            String signature = util.sign(query);

            String url = config.restBaseUrl() + "/fapi/v2/positionRisk?" + query + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode json = new ObjectMapper().readTree(response.body());
            if (json.isArray() && !json.isEmpty()) {
                return new BigDecimal(json.get(0).get("positionAmt").asText());
            }
        } catch (Exception e) {
            log.error("Error fetching position for {}", symbol, e);
        }
        return BigDecimal.ZERO;
    }

    private String buildQueryString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    public List<OrderDto> getActiveOrders() {
        List<OrderDto> activeOrders = new ArrayList<>();
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature = util.sign(queryString);

            String finalUrl = config.restBaseUrl() + "/fapi/v2/positionRisk?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();

                ObjectMapper mapper = new ObjectMapper();
                List<Map<String, Object>> positions = mapper.readValue(body, new TypeReference<>() {});

                for (Map<String, Object> pos : positions) {
                    BigDecimal amt = new BigDecimal((String) pos.get("positionAmt"));

                    // Csak azokat adjuk vissza, ahol valóban van pozíció (nem üres)
                    if (amt.compareTo(BigDecimal.ZERO) != 0) {
                        OrderDto dto = parser.mapToOrderDto(pos);
                        dto.setIsLoaded(true);
                        activeOrders.add(dto);
                    }
                }
            } else {
                log.error("Nem sikerült lekérdezni a pozíciókat: {} - {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("Hiba a pozíciók lekérdezésekor", e);
        }
        return activeOrders;
    }
}
