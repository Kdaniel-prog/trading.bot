package kd.trading.bot.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.config.BinanceConfig;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequiredArgsConstructor
@Service
public class BinanceListenKeyService {
    BinanceConfig config;
    HttpClient client;
    ObjectMapper mapper;

    public String createListenKey() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.restBaseUrl() + "/fapi/v1/listenKey"))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("ListenKey Response: {}", response.body());

            JsonNode node = mapper.readTree(response.body());

            return node.get("listenKey").asText();

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to create listenKey", e);
        }
    }

    public void keepAliveListenKey(String listenKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.restBaseUrl() + "/fapi/v1/listenKey?listenKey=" + listenKey))
                    .header("X-MBX-APIKEY", config.apiKey())
                    .method("PUT", HttpRequest.BodyPublishers.noBody())
                    .build();

            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to keep alive listenKey", e);
        }
    }
}
