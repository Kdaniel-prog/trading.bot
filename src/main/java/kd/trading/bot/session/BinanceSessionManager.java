package kd.trading.bot.session;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.api.BinanceRestClient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BinanceSessionManager {
    @Getter
    String listenKey;
    final BinanceRestClient restClient;

    @PostConstruct
    public void init() {
        createListenKey();
    }

    private void createListenKey() {
        try {
            listenKey = restClient.createListenKey();
            log.info("Created new listenKey: {}", listenKey);
        } catch (Exception e) {
            log.error("Failed to create listenKey", e);
        }
    }

    /**
     * 60 percenként frissül a listenKey
     */
    @Scheduled(fixedRate = 60 * 60 * 1000)
    public void refreshListenKey() {
        if (listenKey == null || listenKey.isEmpty()) {
            log.warn("No listenKey found, creating new...");
            createListenKey();
            return;
        }
        try {
            restClient.keepAliveListenKey(listenKey);
            log.info("ListenKey refreshed: {}", listenKey);
        } catch (Exception e) {
            log.error("Failed to refresh listenKey, recreating...", e);
            createListenKey();
        }
    }

}
