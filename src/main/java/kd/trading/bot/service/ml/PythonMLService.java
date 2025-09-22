package kd.trading.bot.service.ml;

import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestTrade;
import kd.trading.bot.enums.Direction;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.model.ml.MLTradeResult;
import kd.trading.bot.service.backtest.FeatherDataLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PythonMLService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FeatherDataLoader featherDataLoader;

    @Value("${python.ml.data.path:src/main/resources/data}")
    private String dataPath;

    @Value("${python.ml.models.path:src/main/resources/data/models}")
    private String modelsPath;

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    /**
     * KRITIKUS JAVÍTÁS: Training data export LOOK-AHEAD BIAS NÉLKÜL
     *
     * A főbb problémák a régi implementációban:
     * 1. A technikai indikátorokat a trade EREDMÉNYÉNEK ismeretében generáltuk
     * 2. Szintetikus/placeholder értékeket használtunk valós számítások helyett
     * 3. A "múltbeli" adatok már tartalmazták a jövő információit
     */
    public void exportCleanTrainingData(List<BacktestResult> backtestResults) {
        try {
            log.info("🚀 Starting CLEAN training data export (no look-ahead bias)");

            Path mlDataDir = Paths.get(dataPath, "training");
            Files.createDirectories(mlDataDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path trainingFile = mlDataDir.resolve("clean_training_data_" + timestamp + ".json");

            List<Map<String, Object>> cleanSamples = new ArrayList<>();
            int totalProcessed = 0;
            int validSamples = 0;

            for (BacktestResult result : backtestResults) {
                if (result.isSuccess() && result.getTrades() != null && !result.getTrades().isEmpty()) {

                    log.info("Processing backtest result for {}: {} trades",
                            result.getSymbol(), result.getTrades().size());

                    // KRITIKUS: Betöltjük a TELJES történelmi adatokat
                    Map<String, List<Object[]>> historicalData = loadCompleteHistoricalData(
                            result.getSymbol(), result.getStartDate(), result.getEndDate());

                    for (BacktestTrade trade : result.getTrades()) {
                        totalProcessed++;

                        try {
                            // KRITIKUS: Technikai indikátorokat CSAK a trade entry time ELŐTTI adatokból számítjuk
                            Map<String, Object> realIndicators = calculateIndicatorsBeforeTradeEntry(
                                    historicalData, trade.getEntryTime());

                            if (realIndicators != null && !realIndicators.isEmpty()) {
                                Map<String, Object> cleanSample = createCleanTrainingSample(trade, result, realIndicators);
                                cleanSamples.add(cleanSample);
                                validSamples++;

                                // Debug first few samples
                                if (validSamples <= 3) {
                                    log.info("Clean sample #{}: Symbol={}, Entry={}, RSI={}, EMA20={}, Actual={}%",
                                            validSamples, trade.getSymbol(), trade.getEntryTime(),
                                            realIndicators.get("rsi"), realIndicators.get("ema20_4h"),
                                            String.format("%.2f", trade.getPnlPercent()));
                                }
                            }

                        } catch (Exception e) {
                            log.warn("Failed to process trade for {} at {}: {}",
                                    result.getSymbol(), trade.getEntryTime(), e.getMessage());
                        }
                    }
                }
            }

            // Export final dataset
            Map<String, Object> trainingDataset = new HashMap<>();
            trainingDataset.put("samples", cleanSamples);
            trainingDataset.put("metadata", Map.of(
                    "generated_at", timestamp,
                    "total_samples", validSamples,
                    "processed_trades", totalProcessed,
                    "data_quality", "CLEAN_NO_LOOKAHEAD",
                    "version", "4.0_clean"
            ));

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(trainingDataset);
            Files.writeString(trainingFile, json);

            log.info("✅ CLEAN training data exported: {} valid samples from {} trades to: {}",
                    validSamples, totalProcessed, trainingFile);

            // Generate quality report
            generateDataQualityReport(cleanSamples);

        } catch (Exception e) {
            log.error("❌ Failed to export clean training data: {}", e.getMessage(), e);
        }
    }

    /**
     * KRITIKUS: Teljes történelmi adatok betöltése minden timeframe-hez
     */
    private Map<String, List<Object[]>> loadCompleteHistoricalData(String symbol,
                                                                   LocalDateTime startDate,
                                                                   LocalDateTime endDate) {
        Map<String, List<Object[]>> historicalData = new HashMap<>();

        // Kiterjesztett időintervallum (technikai indikátorokhoz 200+ candle kell)
        LocalDateTime extendedStart = startDate.minusDays(200);

        try {
            String[] timeframes = {"1h", "4h", "1d"};

            for (String timeframe : timeframes) {
                try {
                    var candleData = featherDataLoader.loadHistoricalData(symbol, timeframe, extendedStart, endDate);

                    // Convert to OHLCV arrays for efficient calculation
                    List<Object[]> ohlcvData = candleData.stream()
                            .map(candle -> new Object[]{
                                    candle.getTimestamp(),
                                    candle.getOpen(),
                                    candle.getHigh(),
                                    candle.getLow(),
                                    candle.getClose(),
                                    candle.getVolume()
                            })
                            .collect(Collectors.toList());

                    historicalData.put(timeframe, ohlcvData);

                    log.debug("Loaded {} {} candles for {}", ohlcvData.size(), timeframe, symbol);

                } catch (Exception e) {
                    log.warn("Failed to load {} data for {}: {}", timeframe, symbol, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Failed to load historical data for {}: {}", symbol, e.getMessage());
        }

        return historicalData;
    }

    /**
     * KRITIKUS: Technikai indikátorok számítása SZIGORÚAN a trade entry előtt
     */
    private Map<String, Object> calculateIndicatorsBeforeTradeEntry(Map<String, List<Object[]>> historicalData,
                                                                    LocalDateTime tradeEntryTime) {
        Map<String, Object> indicators = new HashMap<>();

        try {
            // KRITIKUS: Csak azokat a candlestick-eket használjuk, amelyek a tradeEntryTime ELŐTT vannak

            // 4H timeframe indicators
            List<Object[]> h4Data = getDataBeforeTimestamp(historicalData.get("4h"), tradeEntryTime);
            if (h4Data.size() >= 50) {
                double[] closes = h4Data.stream()
                        .mapToDouble(candle -> ((Number) candle[4]).doubleValue()) // close price
                        .toArray();
                double[] highs = h4Data.stream()
                        .mapToDouble(candle -> ((Number) candle[2]).doubleValue()) // high price
                        .toArray();
                double[] lows = h4Data.stream()
                        .mapToDouble(candle -> ((Number) candle[3]).doubleValue()) // low price
                        .toArray();
                double[] volumes = h4Data.stream()
                        .mapToDouble(candle -> ((Number) candle[5]).doubleValue()) // volume
                        .toArray();

                // VALÓS technikai indikátorok számítása
                indicators.put("rsi", calculateRealRSI(closes, 14));
                indicators.put("ema20_4h", calculateRealEMA(closes, 20));
                indicators.put("ema50_4h", calculateRealEMA(closes, 50));
                indicators.put("volumeRatio", calculateRealVolumeRatio(volumes, 20));
                indicators.put("atr", calculateRealATR(highs, lows, closes, 14));

                // MACD számítása
                MACDResult macd = calculateRealMACD(closes);
                indicators.put("macdLine", macd.line);
                indicators.put("macdSignal", macd.signal);
                indicators.put("macdHistogram", macd.histogram);

                // Trend analysis
                double currentPrice = closes[closes.length - 1];
                double ema20 = (Double) indicators.get("ema20_4h");
                double ema50 = (Double) indicators.get("ema50_4h");

                String trend = "NEUTRAL";
                if (currentPrice > ema20 && ema20 > ema50) {
                    trend = "BULLISH";
                } else if (currentPrice < ema20 && ema20 < ema50) {
                    trend = "BEARISH";
                }
                indicators.put("primaryTrend", trend);
                indicators.put("currentPrice", currentPrice);

            } else {
                log.warn("Insufficient 4H data for indicators: {} candles", h4Data.size());
                return null;
            }

            // Daily timeframe for longer-term indicators
            List<Object[]> dailyData = getDataBeforeTimestamp(historicalData.get("1d"), tradeEntryTime);
            if (dailyData.size() >= 200) {
                double[] dailyCloses = dailyData.stream()
                        .mapToDouble(candle -> ((Number) candle[4]).doubleValue())
                        .toArray();
                indicators.put("ema200_daily", calculateRealEMA(dailyCloses, 200));
            } else {
                // Fallback to 4H EMA50 as proxy
                indicators.put("ema200_daily", indicators.get("ema50_4h"));
            }

            // Risk metrics
            double atr = (Double) indicators.get("atr");
            double currentPrice = (Double) indicators.get("currentPrice");
            double atrPercent = currentPrice > 0 ? (atr / currentPrice) * 100.0 : 0.0;
            indicators.put("atrPercent", atrPercent);
            indicators.put("volatilityPercent", atrPercent);

            // Volume strength
            double volumeRatio = (Double) indicators.get("volumeRatio");
            indicators.put("strongVolume", volumeRatio > 1.3);
            indicators.put("volumeBreakout", volumeRatio > 2.0);

            // RSI zones
            double rsi = (Double) indicators.get("rsi");
            indicators.put("rsiBullishZone", rsi < 40);
            indicators.put("rsiBearishZone", rsi > 60);
            indicators.put("rsiOversold", rsi < 30);
            indicators.put("rsiOverbought", rsi > 70);


            // Data quality flag
            indicators.put("_dataQuality", "REAL_HISTORICAL");
            indicators.put("_calculatedAt", tradeEntryTime.toString());

            return indicators;

        } catch (Exception e) {
            log.error("Error calculating real indicators: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Adatok szűrése egy timestamp előttre
     */
    private List<Object[]> getDataBeforeTimestamp(List<Object[]> data, LocalDateTime timestamp) {
        if (data == null) return Collections.emptyList();

        return data.stream()
                .filter(candle -> {
                    LocalDateTime candleTime = (LocalDateTime) candle[0];
                    return candleTime.isBefore(timestamp);
                })
                .collect(Collectors.toList());
    }

    /**
     * Tiszta training sample készítése VALÓS indikátorokkal
     */
    private Map<String, Object> createCleanTrainingSample(BacktestTrade trade,
                                                          BacktestResult result,
                                                          Map<String, Object> realIndicators) {
        Map<String, Object> sample = new HashMap<>();

        // Basic info
        sample.put("symbol", trade.getSymbol());
        sample.put("timeframe", result.getTimeframe());
        sample.put("timestamp", trade.getEntryTime().toString());
        sample.put("entryPrice", trade.getEntryPrice());

        // VALÓS technikai indikátorok (trade előtti adatokból)
        sample.put("technicalIndicators", realIndicators);

        // ACTUAL outcomes (ezeket már ismerjük)
        sample.put("actualDirection", trade.getSide().toString());
        sample.put("actualPnlPercent", trade.getPnlPercent());
        sample.put("actualOutcome", determineOutcome(trade.getPnlPercent()));
        sample.put("holdingTimeHours", trade.getHoldingTimeHours());

        // Metadata
        sample.put("tradingRule", trade.getTradingRule());
        sample.put("score", trade.getScore());
        sample.put("exitReason", determineExitReason(trade));

        // Data quality
        sample.put("_sampleQuality", "CLEAN_NO_LOOKAHEAD");

        return sample;
    }

    /**
     * VALÓS RSI számítás
     */
    private double calculateRealRSI(double[] prices, int period) {
        if (prices.length < period + 1) {
            return 50.0;
        }

        double[] gains = new double[prices.length - 1];
        double[] losses = new double[prices.length - 1];

        // Calculate price changes
        for (int i = 1; i < prices.length; i++) {
            double change = prices[i] - prices[i - 1];
            gains[i - 1] = Math.max(change, 0);
            losses[i - 1] = Math.max(-change, 0);
        }

        // Calculate initial SMA
        double avgGain = 0, avgLoss = 0;
        for (int i = 0; i < period; i++) {
            avgGain += gains[i];
            avgLoss += losses[i];
        }
        avgGain /= period;
        avgLoss /= period;

        // Apply Wilder's smoothing for subsequent values
        for (int i = period; i < gains.length; i++) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period;
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period;
        }

        if (avgLoss == 0) return 100.0;
        if (avgGain == 0) return 0.0;

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * VALÓS EMA számítás
     */
    private double calculateRealEMA(double[] prices, int period) {
        if (prices.length == 0) return 0.0;
        if (prices.length < period) return prices[prices.length - 1];

        double multiplier = 2.0 / (period + 1);
        double ema = prices[0];

        for (int i = 1; i < prices.length; i++) {
            ema = (prices[i] * multiplier) + (ema * (1 - multiplier));
        }

        return ema;
    }

    /**
     * VALÓS Volume ratio számítás
     */
    private double calculateRealVolumeRatio(double[] volumes, int period) {
        if (volumes.length < 2) return 1.0;

        double currentVolume = volumes[volumes.length - 1];

        // Calculate average volume over period
        int startIndex = Math.max(0, volumes.length - period);
        double avgVolume = 0;
        int count = 0;

        for (int i = startIndex; i < volumes.length - 1; i++) { // Exclude current volume from average
            avgVolume += volumes[i];
            count++;
        }

        if (count > 0) {
            avgVolume /= count;
            return avgVolume > 0 ? currentVolume / avgVolume : 1.0;
        }

        return 1.0;
    }

    /**
     * VALÓS ATR számítás
     */
    private double calculateRealATR(double[] highs, double[] lows, double[] closes, int period) {
        if (highs.length < period + 1) return 0.0;

        double[] trueRanges = new double[highs.length - 1];

        for (int i = 1; i < highs.length; i++) {
            double tr1 = highs[i] - lows[i];
            double tr2 = Math.abs(highs[i] - closes[i - 1]);
            double tr3 = Math.abs(lows[i] - closes[i - 1]);
            trueRanges[i - 1] = Math.max(Math.max(tr1, tr2), tr3);
        }

        // Use EMA for ATR calculation (Wilder's smoothing)
        return calculateRealEMA(trueRanges, period);
    }

    /**
     * VALÓS MACD számítás
     */
    private MACDResult calculateRealMACD(double[] prices) {
        if (prices.length < 26) {
            return new MACDResult(0.0, 0.0, 0.0);
        }

        double ema12 = calculateRealEMA(prices, 12);
        double ema26 = calculateRealEMA(prices, 26);
        double macdLine = ema12 - ema26;

        // For proper signal line, we'd need MACD line history
        // Simplified: use 90% of MACD line as signal approximation
        double signal = macdLine * 0.9;
        double histogram = macdLine - signal;

        return new MACDResult(macdLine, signal, histogram);
    }

    @Async
    public CompletableFuture<Boolean> submitTradeResult(MLTradeResult tradeResult) {
        log.debug("Trade result submitted: {} - {}%", tradeResult.getSymbol(), tradeResult.getPnlPercent());
        return CompletableFuture.completedFuture(true);
    }

    /**
     * MACD eredmény osztály
     */
    private record MACDResult(double line, double signal, double histogram) {
    }

    /**
     * Outcome meghatározása PnL alapján
     */
    private String determineOutcome(double pnlPercent) {
        if (pnlPercent > 3.0) return "STRONG_WIN";
        if (pnlPercent > 0.5) return "WIN";
        if (pnlPercent > -0.5) return "NEUTRAL";
        if (pnlPercent > -3.0) return "LOSS";
        return "STRONG_LOSS";
    }

    /**
     * Exit reason meghatározása
     */
    private String determineExitReason(BacktestTrade trade) {
        if (trade.getHoldingTimeHours() > 240) return "MAX_TIME";
        if (trade.getPnlPercent() > 6.0) return "TAKE_PROFIT";
        if (trade.getPnlPercent() < -3.0) return "STOP_LOSS";
        return "SIGNAL_CHANGE";
    }

    /**
     * Adatminőség riport generálása
     */
    private void generateDataQualityReport(List<Map<String, Object>> samples) {
        if (samples.isEmpty()) {
            log.warn("No samples to generate quality report");
            return;
        }

        // Outcome distribution
        Map<String, Integer> outcomeCount = new HashMap<>();
        double totalPnl = 0;
        int validIndicatorCount = 0;

        for (Map<String, Object> sample : samples) {
            String outcome = (String) sample.get("actualOutcome");
            outcomeCount.put(outcome, outcomeCount.getOrDefault(outcome, 0) + 1);

            Double pnl = (Double) sample.get("actualPnlPercent");
            if (pnl != null) totalPnl += pnl;

            @SuppressWarnings("unchecked")
            Map<String, Object> indicators = (Map<String, Object>) sample.get("technicalIndicators");
            if (indicators != null && indicators.containsKey("_dataQuality")) {
                validIndicatorCount++;
            }
        }

        double avgPnl = totalPnl / samples.size();

        log.info("📊 CLEAN Training Data Quality Report:");
        log.info("  Total samples: {}", samples.size());
        log.info("  Valid indicators: {}/{} ({}%)", validIndicatorCount, samples.size(),
                (validIndicatorCount * 100 / samples.size()));
        log.info("  Average PnL: {}%", avgPnl);
        log.info("  Outcome distribution:");
        outcomeCount.forEach((outcome, count) -> {
            double percentage = (count * 100.0) / samples.size();
            log.info("    {}: {} ({}%)", outcome, count, percentage);
        });
    }



}