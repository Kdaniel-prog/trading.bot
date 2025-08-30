package kd.trading.bot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.config.binance.BinanceConfig;
import kd.trading.bot.model.ExchangeInfo;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.util.SignatureUtil;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Service
@AllArgsConstructor
public class BinanceRestClient {
    ObjectMapper mapper;
    BinanceConfig config;
    HttpClient client;
    SignatureUtil util;

    //USDT
    public List<SymbolInfo> getTradableSymbols(String currency) {
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
            Instant cutoff = LocalDateTime.now(ZoneOffset.UTC).minusMonths(config.coinMinMonth()).toInstant(ZoneOffset.UTC);

            return exchangeInfo.getSymbols().stream()
                    .filter(s -> "TRADING".equals(s.getStatus()))
                    .filter(s -> currency.equals(s.getQuoteAsset()))
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
}
