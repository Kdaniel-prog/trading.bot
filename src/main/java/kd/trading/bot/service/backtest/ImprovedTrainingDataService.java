package kd.trading.bot.service.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kd.trading.bot.model.HistoricalCandle;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestTrade;
import kd.trading.bot.util.IndicatorUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImprovedTrainingDataService {

    private final FeatherDataLoader featherDataLoader;
    private final IndicatorUtil indicatorUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * CRITICAL FIXES for better ML model training
     */
    public void generateBalancedTrainingData(List<BacktestResult> backtestResults) {
        try {
            log.info("🎯 Generating BALANCED training data with quality fixes");

            List<Map<String, Object>> balancedSamples = new ArrayList<>();
            Map<String, List<Map<String, Object>>> outcomeGroups = new HashMap<>();

            // 1. COLLECT AND GROUP samples by outcome
            for (BacktestResult result : backtestResults) {
                if (!result.isSuccess() || result.getTrades() == null) continue;

                for (BacktestTrade trade : result.getTrades()) {
                    Map<String, Object> sample = createHighQualitySample(trade, result);
                    if (sample != null) {
                        String outcome = (String) sample.get("outcome");
                        outcomeGroups.computeIfAbsent(outcome, k -> new ArrayList<>()).add(sample);
                    }
                }
            }

            // 2. BALANCE the dataset
            balancedSamples = createBalancedDataset(outcomeGroups);

            // 3. ADD SYNTHETIC negative samples
            balancedSamples.addAll(generateSyntheticNegativeSamples(backtestResults,
                    balancedSamples.size() / 4));

            // 4. EXPORT with metadata
            exportBalancedTrainingData(balancedSamples);

        } catch (Exception e) {
            log.error("❌ Failed to generate balanced training data: {}", e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> enforceOutcomeBalance(List<Map<String, Object>> samples) {
        Map<String, List<Map<String, Object>>> groups = samples.stream()
                .collect(Collectors.groupingBy(s -> (String) s.get("actualOutcome")));

        // Target: max 40% HOLDING, distribute rest among LONG/SHORT outcomes
        int totalSize = samples.size();
        int maxHolding = (int)(totalSize * 0.4);

        List<Map<String, Object>> balanced = new ArrayList<>();

        // Limit HOLDING samples
        List<Map<String, Object>> holdingSamples = groups.getOrDefault("HOLDING", new ArrayList<>());
        if (holdingSamples.size() > maxHolding) {
            Collections.shuffle(holdingSamples);
            balanced.addAll(holdingSamples.subList(0, maxHolding));
        } else {
            balanced.addAll(holdingSamples);
        }

        // Add all non-HOLDING samples
        groups.entrySet().stream()
                .filter(e -> !"HOLDING".equals(e.getKey()))
                .forEach(e -> balanced.addAll(e.getValue()));

        log.info("Balanced dataset: {} total, {} holding ({}%)",
                balanced.size(),
                balanced.stream().mapToLong(s -> "HOLDING".equals(s.get("actualOutcome")) ? 1 : 0).sum(),
                balanced.stream().mapToLong(s -> "HOLDING".equals(s.get("actualOutcome")) ? 1 : 0).sum() * 100 / balanced.size());

        return balanced;
    }

    /**
     * Create high-quality training sample with STRICT data validation
     */
    private Map<String, Object> createHighQualitySample(BacktestTrade trade, BacktestResult result) {
        try {
            // Load ONLY pre-trade historical data
            Map<String, List<Object[]>> preTradeData = loadDataBeforeTimestamp(
                    result.getSymbol(), trade.getEntryTime());

            if (!hasMinimumDataQuality(preTradeData)) {
                return null;
            }

            // Calculate REAL technical indicators
            Map<String, Object> indicators = calculateCleanIndicators(preTradeData);
            if (indicators.isEmpty()) {
                return null;
            }

            // Create sample with CLEAR outcome classification
            Map<String, Object> sample = new HashMap<>();
            sample.put("symbol", trade.getSymbol());
            sample.put("timestamp", trade.getEntryTime().toString());
            sample.put("timeframe", result.getTimeframe());

            // Technical features
            sample.putAll(indicators);

            // IMPROVED outcome classification
            sample.put("outcome", classifyOutcomeImproved(trade));
            sample.put("actualPnl", trade.getPnlPercent());
            sample.put("holdingHours", trade.getHoldingTimeHours());
            sample.put("direction", trade.getSide().toString());

            // Quality markers
            sample.put("dataQuality", "HIGH_QUALITY");
            sample.put("version", "3.0");

            return sample;

        } catch (Exception e) {
            log.warn("Failed to create sample for trade: {}", e.getMessage());
            return null;
        }
    }

    /**
     * COMPLETED: Load historical data BEFORE trade timestamp
     */
    private Map<String, List<Object[]>> loadDataBeforeTimestamp(String symbol, LocalDateTime timestamp) {
        Map<String, List<Object[]>> historicalData = new HashMap<>();

        // Extended timeframe to get enough historical data
        LocalDateTime extendedStart = timestamp.minusDays(200);
        String[] timeframes = {"1h", "4h", "1d"};

        try {
            for (String timeframe : timeframes) {
                try {
                    List<HistoricalCandle> candles = featherDataLoader.loadHistoricalData(
                            symbol, timeframe, extendedStart, timestamp);

                    // Filter to only data BEFORE timestamp (strict temporal order)
                    List<HistoricalCandle> preTradeCandles = candles.stream()
                            .filter(candle -> candle.getTimestamp().isBefore(timestamp))
                            .toList();

                    // Convert to Object[] format for calculations
                    List<Object[]> ohlcvData = preTradeCandles.stream()
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

                } catch (Exception e) {
                    log.warn("Failed to load {} data for {}: {}", timeframe, symbol, e.getMessage());
                    historicalData.put(timeframe, Collections.emptyList());
                }
            }
        } catch (Exception e) {
            log.error("Error loading historical data for {} at {}: {}", symbol, timestamp, e.getMessage());
        }

        return historicalData;
    }

    /**
     * IMPROVED outcome classification with better balance
     */
    private String classifyOutcomeImproved(BacktestTrade trade) {
        double pnl = trade.getPnlPercent();
        long hours = trade.getHoldingTimeHours();

        // Consider both PnL and time efficiency
        if (pnl > 2.0 && hours < 48) return "STRONG_WIN";    // Quick profitable trades
        if (pnl > 0.5) return "WIN";                          // Any profitable trade
        if (pnl > -0.5 && hours < 24) return "NEUTRAL";      // Break-even quick trades
        if (pnl > -2.0) return "SMALL_LOSS";                 // Minor losses
        if (pnl > -5.0) return "LOSS";                       // Manageable losses
        return "STRONG_LOSS";                                 // Major losses
    }

    /**
     * Create BALANCED dataset using stratified sampling
     */
    private List<Map<String, Object>> createBalancedDataset(Map<String, List<Map<String, Object>>> outcomeGroups) {
        List<Map<String, Object>> balanced = new ArrayList<>();

        // Find the target size (median group size or minimum threshold)
        int targetSize = Math.max(50, outcomeGroups.values().stream()
                .mapToInt(List::size)
                .sorted()
                .skip(outcomeGroups.size() / 2)
                .findFirst()
                .orElse(100));

        log.info("📊 Balancing dataset with target size: {} per outcome", targetSize);

        for (Map.Entry<String, List<Map<String, Object>>> entry : outcomeGroups.entrySet()) {
            String outcome = entry.getKey();
            List<Map<String, Object>> samples = entry.getValue();

            if (samples.size() >= targetSize) {
                // Randomly sample from large groups
                Collections.shuffle(samples);
                balanced.addAll(samples.subList(0, targetSize));
                log.info("  {}: {} -> {} (sampled)", outcome, samples.size(), targetSize);
            } else if (samples.size() >= 10) {
                // Use all samples from smaller groups and generate synthetic ones
                balanced.addAll(samples);
                balanced.addAll(generateSyntheticSamples(samples, targetSize - samples.size()));
                log.info("  {}: {} -> {} (augmented)", outcome, samples.size(), targetSize);
            } else {
                // Skip very small groups
                log.warn("  {}: {} samples - SKIPPED (too few)", outcome, samples.size());
            }
        }

        // Shuffle final dataset
        Collections.shuffle(balanced);
        return balanced;
    }

    /**
     * Generate synthetic samples with NOISE INJECTION
     */
    private List<Map<String, Object>> generateSyntheticSamples(List<Map<String, Object>> originalSamples, int count) {
        List<Map<String, Object>> synthetic = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < count; i++) {
            Map<String, Object> original = originalSamples.get(random.nextInt(originalSamples.size()));
            Map<String, Object> syntheticSample = new HashMap<>(original);

            // Add small variations to numerical features
            addNoisyVariations(syntheticSample, random);
            syntheticSample.put("synthetic", true);
            synthetic.add(syntheticSample);
        }

        return synthetic;
    }

    /**
     * Add controlled noise to prevent overfitting
     */
    private void addNoisyVariations(Map<String, Object> sample, Random random) {
        String[] numericFields = {"rsi", "ema20", "ema50", "volumeRatio", "atr", "macdLine"};

        for (String field : numericFields) {
            Object value = sample.get(field);
            if (value instanceof Number) {
                double original = ((Number) value).doubleValue();
                double noise = original * (random.nextGaussian() * 0.02); // 2% noise
                sample.put(field, original + noise);
            }
        }
    }

    /**
     * Generate synthetic NEGATIVE samples to balance NO_TRADE outcomes
     */
    private List<Map<String, Object>> generateSyntheticNegativeSamples(List<BacktestResult> results, int count) {
        List<Map<String, Object>> negativeSamples = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < count; i++) {
            BacktestResult result = results.get(random.nextInt(results.size()));

            // Create sample with random indicators but NO_TRADE outcome
            Map<String, Object> sample = new HashMap<>();
            sample.put("symbol", result.getSymbol());
            sample.put("outcome", "NO_TRADE");
            sample.put("actualPnl", 0.0);
            sample.put("direction", "HOLD");

            // Generate realistic but non-predictive indicators
            sample.put("rsi", 30 + random.nextDouble() * 40); // 30-70 range
            sample.put("ema20", random.nextDouble() * 1000);
            sample.put("volumeRatio", 0.5 + random.nextDouble() * 1.5);
            sample.put("macdLine", -0.1 + random.nextDouble() * 0.2);

            sample.put("synthetic", true);
            sample.put("dataQuality", "SYNTHETIC_NEGATIVE");
            negativeSamples.add(sample);
        }

        return negativeSamples;
    }

    /**
     * COMPLETED: Calculate clean technical indicators with real implementations
     */
    private Map<String, Object> calculateCleanIndicators(Map<String, List<Object[]>> preTradeData) {
        Map<String, Object> indicators = new HashMap<>();

        try {
            List<Object[]> h4Data = preTradeData.get("4h");
            if (h4Data == null || h4Data.size() < 50) {
                return indicators;
            }

            double[] closes = h4Data.stream()
                    .mapToDouble(candle -> ((Number) candle[4]).doubleValue())
                    .toArray();
            double[] highs = h4Data.stream()
                    .mapToDouble(candle -> ((Number) candle[2]).doubleValue())
                    .toArray();
            double[] lows = h4Data.stream()
                    .mapToDouble(candle -> ((Number) candle[3]).doubleValue())
                    .toArray();
            double[] volumes = h4Data.stream()
                    .mapToDouble(candle -> ((Number) candle[5]).doubleValue())
                    .toArray();

            // Convert to List<Double> for IndicatorUtil
            List<Double> closesList = Arrays.stream(closes).boxed().collect(Collectors.toList());
            List<Double> highsList = Arrays.stream(highs).boxed().collect(Collectors.toList());
            List<Double> lowsList = Arrays.stream(lows).boxed().collect(Collectors.toList());

            // Core indicators with validation using IndicatorUtil
            double rsi = indicatorUtil.RSI(closesList, 14);
            double ema20 = indicatorUtil.EMA(closesList, 20);
            double ema50 = indicatorUtil.EMA(closesList, 50);
            double sma20 = indicatorUtil.SMA(closesList, 20);
            double atr = indicatorUtil.calculateATR(highsList, lowsList, closesList, 14);
            double[] macd = indicatorUtil.MACD(closesList, 12, 26, 9);
            double[] supportResistance = indicatorUtil.calculateSupportResistance(highsList, lowsList, closesList);

            // Volume analysis
            double volumeRatio = calculateValidVolumeRatio(volumes);

            if (!Double.isNaN(rsi) && !Double.isNaN(ema20)) {
                indicators.put("rsi", rsi);
                indicators.put("ema20", ema20);
                indicators.put("ema50", ema50);
                indicators.put("sma20", sma20);
                indicators.put("atr", atr);
                indicators.put("volumeRatio", volumeRatio);
                indicators.put("macdLine", macd[0]);
                indicators.put("macdSignal", macd[1]);
                indicators.put("macdHistogram", macd[2]);
                indicators.put("supportLevel", supportResistance[0]);
                indicators.put("resistanceLevel", supportResistance[1]);

                // Derived features
                double currentPrice = closes[closes.length - 1];
                indicators.put("currentPrice", currentPrice);
                indicators.put("priceAboveEMA20", currentPrice > ema20 ? 1.0 : 0.0);
                indicators.put("priceAboveEMA50", currentPrice > ema50 ? 1.0 : 0.0);
                indicators.put("emaAlignment", ema20 > ema50 ? 1.0 : 0.0);
                indicators.put("rsiOversold", rsi < 30 ? 1.0 : 0.0);
                indicators.put("rsiOverbought", rsi > 70 ? 1.0 : 0.0);
                indicators.put("highVolume", volumeRatio > 1.5 ? 1.0 : 0.0);

                // Market structure indicators
                indicators.put("bullishTrend", (ema20 > ema50 && currentPrice > ema20) ? 1.0 : 0.0);
                indicators.put("bearishTrend", (ema20 < ema50 && currentPrice < ema20) ? 1.0 : 0.0);

                // Price momentum
                double momentum = indicatorUtil.calculateMomentum(closesList, 10);
                indicators.put("momentum", momentum);

                // Volatility percentage
                double volatility = indicatorUtil.calculateVolatility(closesList, 20);
                indicators.put("volatilityPercent", volatility);

                // Risk-reward approximation
                double riskReward = (Math.abs(currentPrice - supportResistance[1]) /
                        Math.abs(currentPrice - supportResistance[0]));
                indicators.put("riskRewardRatio", Double.isFinite(riskReward) ? riskReward : 1.0);
            }

        } catch (Exception e) {
            log.error("Error calculating clean indicators: {}", e.getMessage());
        }

        return indicators;
    }

    // Validation helpers
    private boolean hasMinimumDataQuality(Map<String, List<Object[]>> data) {
        List<Object[]> h4Data = data.get("4h");
        return h4Data != null && h4Data.size() >= 50;
    }

    private double calculateValidVolumeRatio(double[] volumes) {
        if (volumes.length < 2) return 1.0;

        double current = volumes[volumes.length - 1];

        // Calculate average volume excluding current
        double sum = 0;
        int count = 0;
        for (int i = 0; i < volumes.length - 1; i++) {
            if (volumes[i] > 0) { // Exclude zero volumes
                sum += volumes[i];
                count++;
            }
        }

        if (count == 0) return 1.0;

        double avg = sum / count;
        return avg > 0 ? current / avg : 1.0;
    }

    /**
     * COMPLETED: Export with comprehensive metadata
     */
    private void exportBalancedTrainingData(List<Map<String, Object>> samples) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = String.format("balanced_training_data_%s.json", timestamp);

            Map<String, Object> export = new HashMap<>();
            export.put("samples", samples);

            // Generate outcome distribution
            Map<String, Long> distribution = samples.stream()
                    .collect(Collectors.groupingBy(
                            s -> (String) s.get("outcome"),
                            Collectors.counting()
                    ));

            // Calculate feature statistics
            Map<String, Object> featureStats = calculateFeatureStatistics(samples);

            export.put("metadata", Map.of(
                    "totalSamples", samples.size(),
                    "generated", timestamp,
                    "outcomeDistribution", distribution,
                    "featureStatistics", featureStats,
                    "dataQuality", "BALANCED_HIGH_QUALITY",
                    "version", "3.0"
            ));

            File outputDir = new File("src/main/resources/data/training");
            outputDir.mkdirs();
            File outputFile = new File(outputDir, filename);

            objectMapper.enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(outputFile, export);

            log.info("✅ Balanced training data exported: {} samples to {}",
                    samples.size(), filename);

            distribution.forEach((outcome, count) ->
                    log.info("  {}: {} samples ({}%)",
                            outcome, count, (count * 100.0 / samples.size())));

        } catch (Exception e) {
            log.error("Failed to export balanced training data: {}", e.getMessage(), e);
        }
    }

    /**
     * Calculate feature statistics for quality assessment
     */
    private Map<String, Object> calculateFeatureStatistics(List<Map<String, Object>> samples) {
        Map<String, Object> stats = new HashMap<>();

        if (samples.isEmpty()) return stats;

        String[] numericFeatures = {"rsi", "ema20", "ema50", "volumeRatio", "atr", "currentPrice"};

        for (String feature : numericFeatures) {
            List<Double> values = samples.stream()
                    .map(s -> s.get(feature))
                    .filter(v -> v instanceof Number)
                    .map(v -> ((Number) v).doubleValue())
                    .filter(Double::isFinite)
                    .toList();

            if (!values.isEmpty()) {
                DoubleSummaryStatistics summary = values.stream()
                        .mapToDouble(Double::doubleValue)
                        .summaryStatistics();

                stats.put(feature, Map.of(
                        "mean", summary.getAverage(),
                        "min", summary.getMin(),
                        "max", summary.getMax(),
                        "count", summary.getCount()
                ));
            }
        }

        return stats;
    }
}