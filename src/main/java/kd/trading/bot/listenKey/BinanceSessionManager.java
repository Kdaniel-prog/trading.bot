package kd.trading.bot.listenKey;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@RequiredArgsConstructor
public class BinanceSessionManager {
    BinanceListenKeyService listenKeyService;

    @Scheduled(fixedRate = 60 * 60 * 1000, initialDelay = 60 * 60 * 1000) // 60 perc
    public void refreshListenKey() {
        if (!BinanceListenerKey.getInstance("").listenerKey.isEmpty()) {
            listenKeyService.keepAliveListenKey(BinanceListenerKey.getInstance("").listenerKey);
            log.info("ListenKey refreshed: {}", BinanceListenerKey.getInstance("").listenerKey);
        }
    }

}
