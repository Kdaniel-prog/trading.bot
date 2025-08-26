package kd.trading.bot.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.config.binance.BinanceConfig;
import kd.trading.bot.model.ExchangeInfo;
import kd.trading.bot.model.SymbolInfo;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Service
@AllArgsConstructor
public class BinanceRestClient {
    ObjectMapper mapper;
    BinanceConfig config;
    HttpClient client;

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

    private String sign(String data) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(config.apiKey().getBytes(), "HmacSHA256");
            sha256_HMAC.init(secretKey);
            return HexFormat.of().formatHex(sha256_HMAC.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("Unable to sign request", e);
        }
    }
}
