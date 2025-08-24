package kd.trading.bot.listenKey;

import kd.trading.bot.api.BinanceRestClient;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Service
@AllArgsConstructor
public class BinanceListenKeyService {
    BinanceRestClient restClient;

    public void createListenKey() {
        try {
            BinanceListenerKey.getInstance(restClient.createListenKey());
        } catch (Exception e) {
            log.error("Error : {}", e.getMessage());
        }
    }

    public void keepAliveListenKey(String listenKey) {
        try {
            restClient.keepAliveListenKey(listenKey);
        } catch (Exception e) {
            log.error("Error : {}", e.getMessage());
        }
    }

}
