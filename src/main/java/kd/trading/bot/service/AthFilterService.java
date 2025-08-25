package kd.trading.bot.service;

import kd.trading.bot.model.BinanceTickerData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AthFilterService {

    private final BinanceHistoryService historyService;
    private final Map<String, AthEntry> athCache = new ConcurrentHashMap<>();

    private static final long ATH_TTL_MS = Duration.ofHours(6).toMillis();

    public List<BinanceTickerData> filterBelowAth(List<BinanceTickerData> candidates) {
        return candidates.stream()
                .filter(t -> t.getLastPrice() < getAthCached(t.getSymbol()))
                .toList();
    }

    private double getAthCached(String symbol) {
        long now = System.currentTimeMillis();
        AthEntry entry = athCache.get(symbol);
        if (entry == null || (now - entry.ts) > ATH_TTL_MS) {
            double ath = historyService.getATH(symbol);
            athCache.put(symbol, new AthEntry(ath, now));
            return ath;
        }
        return entry.value;
    }

    record AthEntry(double value, long ts) {}
}

