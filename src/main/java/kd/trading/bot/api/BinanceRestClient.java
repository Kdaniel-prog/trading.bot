package kd.trading.bot.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.config.binance.BinanceConfig;
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
import java.util.HexFormat;
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

    public String createListenKey() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.restBaseUrl() + "/fapi/v1/listenKey"))
                .header("X-MBX-APIKEY", config.apiKey())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("ListenKey Response: {}", response.body());

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

    /**
     * Get all 24h ticker statistics (Futures)
     */
    public List<Map<String, Object>> tickers24h() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.restBaseUrl() + "/fapi/v1/ticker/24hr"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Parse JSON array into List<Map<String,Object>>
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(response.body(), new TypeReference<List<Map<String,Object>>>() {});
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
