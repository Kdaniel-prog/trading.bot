package kd.trading.bot.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.CoinAnalysis;
import kd.trading.bot.service.AlgorithmService;
import kd.trading.bot.service.BinanceHistoryService;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
public class BinanceMarketWebSocketClient extends WebSocketClient {

    // 180_000 ms = 3 perc (a korábbi komment 30 mp volt, de az 30_000)
    static final long INTERVAL_MS = 600_000;

    // Elemzésre vett tickerek max száma (prefilter): csökkentsd/emeld igény szerint
    static final int MAX_CANDIDATES = 60;

    // REST elemzések párhuzamossági limitje (API limit / CPU függő)
    static final int ANALYSIS_THREADS = Math.max(8, Runtime.getRuntime().availableProcessors());

    static final ObjectMapper MAPPER = new ObjectMapper();

    final BinanceHistoryService binanceHistoryService;
    final AlgorithmService algorithmService;

    final ExecutorService analysisPool = Executors.newFixedThreadPool(ANALYSIS_THREADS);

    // Egyszerű ATH cache (6 órás TTL). Ha van Caffeine/Guava, azzal még szebb.
    final Map<String, AthEntry> athCache = new ConcurrentHashMap<>();
    static final long ATH_TTL_MS = Duration.ofHours(6).toMillis();

    volatile long lastProcessed = 0;

    public BinanceMarketWebSocketClient(URI serverUri,
                                        BinanceHistoryService binanceHistoryService,
                                        AlgorithmService algorithmService) {
        super(serverUri);
        this.binanceHistoryService = binanceHistoryService;
        this.algorithmService = algorithmService;
    }

    @Override
    public void onOpen(ServerHandshake handshakeData) {
        log.info("Connected to Binance Futures Market WebSocket");
    }

    @Override
    public void onMessage(String message) {
        long now = System.currentTimeMillis();
        if (now - lastProcessed < INTERVAL_MS) return;
        lastProcessed = now;

        List<BinanceTickerData> incomingTickers;
        try {
            incomingTickers = MAPPER.readValue(message, new TypeReference<List<BinanceTickerData>>() {});
        } catch (Exception e) {
            log.error("Failed to parse ticker array", e);
            return;
        }

        // Háttérfeladat: async feldolgozás
        analysisPool.submit(() -> {
            try {
                // 1️⃣ Prefilter: top 60 by quoteVolume
                // quoteVolume = adott pár teljes kereskedési volumene a jegyző (quote) devizában
                // (pl. BTCUSDT esetén USDT-ben számolva). Így az 50-60 legnagyobb forgalmú coin kerül be.
                List<BinanceTickerData> candidates = incomingTickers.stream()
                        .sorted(Comparator.comparingDouble(BinanceTickerData::getQuoteVolume).reversed())
                        .limit(60)
                        .toList();

                // 2️⃣ ATH filter
                List<BinanceTickerData> athFiltered = candidates.stream()
                        .filter(t -> t.getLastPrice() < getAthCached(t.getSymbol()))
                        .toList();

                // 3️⃣ Elemzés párhuzamosan
                List<CompletableFuture<BinanceTickerData>> futures = athFiltered.stream()
                        .map(ticker -> CompletableFuture.supplyAsync(() -> {
                                    CoinAnalysis analysis = algorithmService.analyzeCoin(ticker.getSymbol());
                                    ticker.setSignal(analysis.getSignal());
                                    ticker.setScore(analysis.getScore());
                                    return ticker;
                                }, analysisPool).orTimeout(4, TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    ticker.setScore(Double.NEGATIVE_INFINITY);
                                    return ticker;
                                }))
                        .toList();

                List<BinanceTickerData> analyzed = futures.stream()
                        .map(CompletableFuture::join)
                        .filter(t -> !Double.isInfinite(t.getScore()))
                        .toList();

                // 4️⃣ Top 5 és Bottom 5 egyszerre
                List<BinanceTickerData> sorted = analyzed.stream()
                        .sorted((a, b) -> Double.compare(b.getScore(), a.getScore())) // csökkenő sorrend
                        .toList();

                List<BinanceTickerData> topCoins = sorted.stream().limit(5).toList();
                List<BinanceTickerData> bottomCoins = sorted.stream()
                        .skip(Math.max(sorted.size() - 5, 0)) // utolsó 5 elem
                        .toList();

                // 5️⃣ Log
                log.info("=== TOP 5 COINS ===");
                topCoins.forEach(c ->
                        log.info("{} | Score: {} | Signal: {}",
                                c.getSymbol(),
                                String.format("%.2f", c.getScore()),
                                c.getSignal())
                );

                log.info("=== BOTTOM 5 COINS ===");
                bottomCoins.forEach(c ->
                        log.info("{} | Score: {} | Signal: {}",
                                c.getSymbol(),
                                String.format("%.2f", c.getScore()),
                                c.getSignal())
                );

            } catch (Exception e) {
                log.error("Processing pipeline failed", e);
            }
        });
    }

    private double getAthCached(String symbol) {
        long now = System.currentTimeMillis();
        AthEntry entry = athCache.get(symbol);
        if (entry == null || (now - entry.ts) > ATH_TTL_MS) {
            double ath = binanceHistoryService.getATH(symbol);
            athCache.put(symbol, new AthEntry(ath, now));
            return ath;
        }
        return entry.value;
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        log.info("Closed: {}", reason);
        analysisPool.shutdownNow();
    }

    @Override
    public void onError(Exception ex) {
        log.error("Error: ", ex);
    }

    // --- Helper ---
    static class AthEntry {
        final double value;
        final long ts;
        AthEntry(double value, long ts) { this.value = value; this.ts = ts; }
    }
}
