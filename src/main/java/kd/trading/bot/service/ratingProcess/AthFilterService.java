package kd.trading.bot.service.ratingProcess;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kd.trading.bot.model.BinanceTickerData;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AthFilterService {

    BinanceHistoryService historyService;
    Map<String, AthEntry> athCache = new ConcurrentHashMap<>();

    static long ATH_TTL_MS = Duration.ofHours(6).toMillis();

    // --- queue + worker pool ---//
    BlockingQueue<String> athQueue = new LinkedBlockingQueue<>();
    ExecutorService workerPool = Executors.newFixedThreadPool(4);

    @PostConstruct
    public void initWorker() {
        for (int i = 0; i < 4; i++) {
            workerPool.submit(this::processAthTasks);
        }
    }

    @PreDestroy
    public void shutdownWorker() {
        log.info("Shutting down ATH worker...");
        workerPool.shutdownNow();
    }

    public List<BinanceTickerData> filterBelowAth(List<BinanceTickerData> candidates) {
        return candidates.stream()
                .filter(t -> {
                    Double ath = getAthCached(t.getSymbol());
                    return ath != null && t.getLastPrice() < ath;
                })
                .toList();
    }


    private Double getAthCached(String symbol) {
        long now = System.currentTimeMillis();
        AthEntry entry = athCache.get(symbol);

        if (entry == null || (now - entry.ts) > ATH_TTL_MS) {
            try {
                double ath = historyService.getATH(symbol);
                athCache.put(symbol, new AthEntry(ath, now));
                return ath;
            } catch (Exception e) {
                log.warn("Skipping ATH for {}: {}", symbol, e.getMessage());
                return null; // jelezzük, hogy nincs érvényes ATH
            }
        }
        return entry.value;
    }

    private void processAthTasks() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String symbol = athQueue.take();
                refreshAth(symbol);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("ATH worker interrupted, exiting.");
            } catch (Exception e) {
                log.error("Unexpected error in ATH worker", e);
            }
        }
    }

    private void refreshAth(String symbol) {
        try {
            double ath = historyService.getATH(symbol); // REST call
            athCache.put(symbol, new AthEntry(ath, System.currentTimeMillis()));
            log.debug("Refreshed ATH for {}", symbol);
        } catch (Exception e) {
            log.error("Failed to refresh ATH for {}", symbol, e);
        }
    }

    record AthEntry(double value, long ts) {}
}
