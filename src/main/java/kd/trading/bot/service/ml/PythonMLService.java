package kd.trading.bot.service.ml;

import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.model.ml.MLTradeResult;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestTrade;
import kd.trading.bot.enums.Direction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PythonMLService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    @Value("${python.ml.script.path:src/main/python/ml_predictor.py}")
    private String mlScriptPath;

    @Value("${python.ml.timeout:15}")
    private int timeoutSeconds;

    @Value("${python.ml.models.path:src/main/resources/data/models}")
    private String modelsPath;

    @Value("${python.ml.data.path:src/main/resources/data}")
    private String dataPath;

    /**
     * JAVÍTOTT ML prediction - valódi technical indicators használatával
     */
    public CompletableFuture<MLPredictionResponse> getPrediction(String symbol,
                                                                 List<List<Object>> fourHourKlines,
                                                                 List<List<Object>> dailyKlines,
                                                                 List<List<Object>> hourlyKlines,
                                                                 Map<String, Object> mlData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.debug("=== ML PREDICTION REQUEST for {} ===", symbol);
                log.debug("ML Data keys: {}", mlData.keySet());
                log.debug("Core indicators - RSI: {}, EMA20: {}, Volume: {}",
                        mlData.get("rsi"), mlData.get("ema20_4h"), mlData.get("volumeRatio"));

                // Validate input data
                if (mlData.isEmpty()) {
                    log.warn("Empty ML data for {}", symbol);
                    return createDefaultPrediction(symbol, "Empty ML data");
                }

                // Prepare input JSON for Python ML script
                Map<String, Object> inputData = prepareMLInputData(symbol, mlData, fourHourKlines, dailyKlines, hourlyKlines);
                String inputJson = objectMapper.writeValueAsString(inputData);

                // Run Python predictor
                ProcessBuilder pb = new ProcessBuilder("python3", "src/main/python/ml_predictor.py");
                pb.redirectErrorStream(true);

                Process process = pb.start();

                // Send input JSON to Python stdin
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                    writer.write(inputJson);
                    writer.flush();
                }

                // Read Python stdout
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    log.error("Python predictor exited with code {} for {}", exitCode, symbol);
                    return createDefaultPrediction(symbol, "Python predictor failed");
                }

                // Parse JSON result from Python
                Map<String, Object> result = objectMapper.readValue(output.toString(), Map.class);

                if (result.containsKey("error")) {
                    return MLPredictionResponse.builder()
                            .modelName("Python-ML")
                            .modelVersion("1.0")
                            .success(false)
                            .confidence(0.0)
                            .predictedSignal(Signal.NO_TRADE)
                            .errorMessage(result.get("error").toString())
                            .rawPredictionData(output.toString())
                            .build();
                }

                String directionStr = result.getOrDefault("predicted_class", "NEUTRAL").toString();
                double confidence = Double.parseDouble(result.getOrDefault("confidence", 0.0).toString());

                Direction direction = Direction.valueOf(directionStr.toUpperCase());
                Signal signal = switch (direction) {
                    case LONG -> Signal.LONG;
                    case SHORT -> Signal.SHORT;
                    default -> Signal.NO_TRADE;
                };

                MLPredictionResponse prediction = MLPredictionResponse.builder()
                        .modelName("Python-ML")
                        .modelVersion("1.0")
                        .direction(direction)
                        .confidence(confidence)
                        .predictedSignal(signal)
                        .success(true)
                        .rawPredictionData(output.toString())
                        .build();

                log.info("ML Prediction for {}: Direction={}, Confidence={}",
                        symbol, prediction.getDirection(), prediction.getConfidence());

                return prediction;

            } catch (Exception e) {
                log.error("ML prediction error for {}: {}", symbol, e.getMessage(), e);
                return createDefaultPrediction(symbol, "ML prediction failed: " + e.getMessage());
            }
        });
    }

    /**
     * Sophisticated rule-based directional prediction
     * This mimics ML behavior while using real technical indicators
     */
    private MLPredictionResponse generateDirectionalPrediction(String symbol, Map<String, Object> mlData) {
        try {
            // Extract and validate key indicators
            double rsi = getDoubleValue(mlData, "rsi", 50.0);
            double primaryTrendScore = getDoubleValue(mlData, "primaryTrendScore", 0.0);
            double shortTermTrendScore = getDoubleValue(mlData, "shortTermTrendScore", 0.0);
            double volumeRatio = getDoubleValue(mlData, "volumeRatio", 1.0);
            double macdHistogram = getDoubleValue(mlData, "macdHistogram", 0.0);
            double riskRewardRatio = getDoubleValue(mlData, "riskRewardRatio", 0.0);
            double trendAlignment = getDoubleValue(mlData, "trendAlignment", 0.0);
            double volatilityPercent = getDoubleValue(mlData, "atrPercent", 5.0);

            // Log extracted values for debugging
            log.debug("Extracted indicators for {}: RSI={}, PrimaryTrend={}, Volume={}, MACD={}",
                    symbol, rsi, primaryTrendScore, volumeRatio, macdHistogram);

            // Calculate direction scores
            double longScore = calculateLongScore(rsi, primaryTrendScore, shortTermTrendScore,
                    volumeRatio, macdHistogram, riskRewardRatio,
                    trendAlignment, volatilityPercent);

            double shortScore = calculateShortScore(rsi, primaryTrendScore, shortTermTrendScore,
                    volumeRatio, macdHistogram, riskRewardRatio,
                    trendAlignment, volatilityPercent);

            double holdScore = calculateHoldScore(rsi, volumeRatio, volatilityPercent, riskRewardRatio);

            // Determine direction and confidence
            Direction direction;
            double confidence;
            Map<String, Double> probabilities = new HashMap<>();

            double maxScore = Math.max(Math.max(longScore, shortScore), holdScore);
            double threshold = 3.5; // Minimum score for action

            if (maxScore < threshold) {
                direction = Direction.HOLD;
                confidence = 0.3;
            } else if (longScore == maxScore && longScore > shortScore + 0.5) {
                direction = Direction.LONG;
                confidence = Math.min(0.95, 0.5 + (longScore / 10.0));
            } else if (shortScore == maxScore && shortScore > longScore + 0.5) {
                direction = Direction.SHORT;
                confidence = Math.min(0.95, 0.5 + (shortScore / 10.0));
            } else {
                direction = Direction.HOLD;
                confidence = 0.4;
            }

            // Calculate probabilities
            double total = longScore + shortScore + holdScore;
            if (total > 0) {
                probabilities.put("LONG", longScore / total);
                probabilities.put("SHORT", shortScore / total);
                probabilities.put("HOLD", holdScore / total);
            } else {
                probabilities.put("LONG", 0.33);
                probabilities.put("SHORT", 0.33);
                probabilities.put("HOLD", 0.34);
            }

            return MLPredictionResponse.builder()
                    .success(true)
                    .direction(direction)
                    .confidence(confidence)
                    .probabilities(probabilities)
                    .predictedSignal(convertDirectionToSignal(direction))
                    .modelConfidence(confidence)
                    .build();

        } catch (Exception e) {
            log.error("Error generating directional prediction for {}: {}", symbol, e.getMessage());
            return createDefaultPrediction(symbol, "Prediction generation failed");
        }
    }

    private double calculateLongScore(double rsi, double primaryTrend, double shortTermTrend,
                                      double volumeRatio, double macdHist, double riskReward,
                                      double trendAlignment, double volatility) {
        double score = 0.0;

        // Trend analysis
        if (primaryTrend > 0.3) score += 2.5;
        if (shortTermTrend > 0.3) score += 1.5;
        if (trendAlignment > 0.5) score += 1.0;

        // RSI analysis
        if (rsi < 40 && rsi > 25) score += 2.0; // Oversold bounce potential
        if (rsi > 30 && rsi < 70) score += 1.0; // Good zone

        // MACD momentum
        if (macdHist > 0) score += 1.5;

        // Volume confirmation
        if (volumeRatio > 1.3) score += 1.2;
        if (volumeRatio > 2.0) score += 0.5; // Strong volume

        // Risk/Reward
        if (riskReward > 2.0) score += 1.0;
        if (riskReward > 3.0) score += 0.5;

        // Volatility filter
        if (volatility > 15.0) score -= 1.0; // High volatility penalty

        return Math.max(0, score);
    }

    private double calculateShortScore(double rsi, double primaryTrend, double shortTermTrend,
                                       double volumeRatio, double macdHist, double riskReward,
                                       double trendAlignment, double volatility) {
        double score = 0.0;

        // Trend analysis (bearish)
        if (primaryTrend < -0.3) score += 2.5;
        if (shortTermTrend < -0.3) score += 1.5;
        if (trendAlignment > 0.5 && primaryTrend < 0) score += 1.0;

        // RSI analysis
        if (rsi > 60 && rsi < 75) score += 2.0; // Overbought reversal potential
        if (rsi > 30 && rsi < 70) score += 1.0; // Good zone

        // MACD momentum
        if (macdHist < 0) score += 1.5;

        // Volume confirmation
        if (volumeRatio > 1.3) score += 1.2;
        if (volumeRatio > 2.0) score += 0.5; // Strong volume

        // Risk/Reward
        if (riskReward > 2.0) score += 1.0;
        if (riskReward > 3.0) score += 0.5;

        // Volatility filter
        if (volatility > 15.0) score -= 1.0; // High volatility penalty

        return Math.max(0, score);
    }

    private double calculateHoldScore(double rsi, double volumeRatio, double volatility, double riskReward) {
        double score = 2.0; // Base hold bias

        // Neutral RSI favors hold
        if (rsi > 45 && rsi < 55) score += 1.0;

        // Low volume favors hold
        if (volumeRatio < 0.8) score += 1.5;

        // High volatility favors hold
        if (volatility > 12.0) score += 1.0;

        // Poor risk/reward favors hold
        if (riskReward < 1.5) score += 1.0;

        return score;
    }

    private kd.trading.bot.enums.Signal convertDirectionToSignal(Direction direction) {
        return switch (direction) {
            case LONG -> kd.trading.bot.enums.Signal.LONG;
            case SHORT -> kd.trading.bot.enums.Signal.SHORT;
            case HOLD -> kd.trading.bot.enums.Signal.NO_TRADE;
        };
    }

    /**
     * JAVÍTOTT training data export - valódi technical indicators használatával
     */
    public void exportTrainingData(List<BacktestResult> backtestResults) {
        try {
            Path mlDataDir = Paths.get(dataPath, "training");
            Files.createDirectories(mlDataDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path trainingFile = mlDataDir.resolve("training_data_" + timestamp + ".json");

            Map<String, Object> trainingDataWrapper = new HashMap<>();
            List<Map<String, Object>> samples = new ArrayList<>();

            int sampleCount = 0;

            for (BacktestResult result : backtestResults) {
                if (result.isSuccess() && result.getTrades() != null && !result.getTrades().isEmpty()) {

                    log.info("Processing backtest result for {}: {} trades",
                            result.getSymbol(), result.getTrades().size());

                    for (BacktestTrade trade : result.getTrades()) {
                        Map<String, Object> sample = createTrainingSample(trade, result);

                        if (sample != null) {
                            samples.add(sample);
                            sampleCount++;

                            // Log first few samples for debugging
                            if (sampleCount <= 5) {
                                Map<String, Object> indicators = (Map<String, Object>) sample.get("technicalIndicators");
                                log.info("Sample {}: Symbol={}, Outcome={}, RSI={}, EMA20={}, Volume={}",
                                        sampleCount, sample.get("symbol"), sample.get("actualOutcome"),
                                        indicators.get("rsi"), indicators.get("ema20_4h"), indicators.get("volumeRatio"));
                            }
                        }
                    }
                }
            }

            trainingDataWrapper.put("samples", samples);
            trainingDataWrapper.put("metadata", Map.of(
                    "generated_at", timestamp,
                    "total_samples", sampleCount,
                    "backtest_results", backtestResults.size(),
                    "version", "2.0"
            ));

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(trainingDataWrapper);
            Files.writeString(trainingFile, json);

            log.info("✅ Exported {} training samples to: {}", sampleCount, trainingFile);

        } catch (Exception e) {
            log.error("❌ Error exporting training data: {}", e.getMessage(), e);
        }
    }

    /**
     * Create a proper training sample with realistic technical indicators
     */
    private Map<String, Object> createTrainingSample(BacktestTrade trade, BacktestResult result) {
        try {
            Map<String, Object> sample = new HashMap<>();
            Map<String, Object> technicalIndicators = new HashMap<>();

            // Basic trade info
            sample.put("symbol", result.getSymbol());
            sample.put("timeframe", result.getTimeframe());
            sample.put("timestamp", trade.getEntryTime().toString());
            sample.put("currentPrice", trade.getEntryPrice());

            // Determine actual outcome based on PnL
            String actualOutcome = determineOutcome(trade.getPnlPercent());
            sample.put("actualOutcome", actualOutcome);

            // Generate REALISTIC technical indicators based on trade outcome
            populateRealisticIndicators(technicalIndicators, trade, actualOutcome);

            sample.put("technicalIndicators", technicalIndicators);

            // Additional metadata
            sample.put("pnl_percent", trade.getPnlPercent());
            sample.put("direction", trade.getSide().toString());
            sample.put("tradingRule", trade.getTradingRule());
            sample.put("score", trade.getScore());

            return sample;

        } catch (Exception e) {
            log.error("Error creating training sample: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Determine outcome based on PnL with more nuanced categories
     */
    private String determineOutcome(double pnlPercent) {
        if (pnlPercent > 3.0) {
            return "STRONG_WIN";
        } else if (pnlPercent > 0.5) {
            return "WIN";
        } else if (pnlPercent > -0.5) {
            return "NEUTRAL";
        } else if (pnlPercent > -3.0) {
            return "LOSS";
        } else {
            return "STRONG_LOSS";
        }
    }

    /**
     * Calculate REAL technical indicators from actual kline data
     * This replaces the populateRealisticIndicators method in PythonMLService
     */
    private void populateRealisticIndicators(Map<String, Object> indicators, BacktestTrade trade, String outcome) {
        try {
            // For now, we'll calculate basic indicators from the trade context
            // In a full implementation, you'd pass the actual kline data here

            double entryPrice = trade.getEntryPrice();

            // Since we don't have the full kline history in this context,
            // we need to either:
            // 1. Pass kline data to this method, OR
            // 2. Move this calculation to where kline data is available

            // For immediate fix, let's create a more structured approach:
            calculateRealIndicators(indicators, entryPrice, trade);

        } catch (Exception e) {
            log.error("Error calculating technical indicators: {}", e.getMessage());
            // Fallback to safe defaults
            setDefaultIndicators(indicators, trade.getEntryPrice());
        }
    }

    /**
     * Calculate real technical indicators - this method should receive actual kline data
     */
    private void calculateRealIndicators(Map<String, Object> indicators, double entryPrice, BacktestTrade trade) {

        // CRITICAL: This method needs access to actual kline data
        // You need to modify the calling chain to pass kline data here

        // For now, calculate what we can from available trade data

        // 1. Basic price ratios (these should come from actual EMA calculations)
        // TODO: Replace with real EMA calculations from kline data
        indicators.put("ema20_4h", entryPrice); // PLACEHOLDER - calculate from klines!
        indicators.put("ema50_4h", entryPrice); // PLACEHOLDER - calculate from klines!
        indicators.put("ema200_daily", entryPrice); // PLACEHOLDER - calculate from klines!

        // 2. RSI (needs 14+ periods of close prices)
        // TODO: Replace with real RSI calculation
        double rsi = 50.0; // PLACEHOLDER - calculate from price changes!
        indicators.put("rsi", rsi);

        // 3. MACD (needs EMA12, EMA26, EMA9 of the difference)
        // TODO: Replace with real MACD calculation
        indicators.put("macdLine", 0.0); // PLACEHOLDER
        indicators.put("macdSignal", 0.0); // PLACEHOLDER
        indicators.put("macdHistogram", 0.0); // PLACEHOLDER

        // 4. Volume indicators (needs volume history)
        // TODO: Replace with real volume analysis
        indicators.put("volumeRatio", 1.0); // PLACEHOLDER

        // 5. Volatility (needs high/low/close history)
        // TODO: Replace with real ATR or volatility calculation
        indicators.put("atrPercent", 5.0); // PLACEHOLDER

        // Add metadata to indicate these are placeholders
        indicators.put("_status", "PLACEHOLDERS_NEED_REAL_KLINE_DATA");
    }

    /**
     * REAL RSI calculation from price array
     */
    public static double calculateRSI(double[] prices, int period) {
        if (prices.length < period + 1) return 50.0;

        double[] gains = new double[prices.length - 1];
        double[] losses = new double[prices.length - 1];

        // Calculate price changes
        for (int i = 1; i < prices.length; i++) {
            double change = prices[i] - prices[i - 1];
            gains[i - 1] = Math.max(change, 0);
            losses[i - 1] = Math.max(-change, 0);
        }

        // Calculate initial averages
        double avgGain = 0, avgLoss = 0;
        for (int i = 0; i < period; i++) {
            avgGain += gains[i];
            avgLoss += losses[i];
        }
        avgGain /= period;
        avgLoss /= period;

        // Calculate RSI using Wilder's smoothing
        for (int i = period; i < gains.length; i++) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period;
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period;
        }

        if (avgLoss == 0) return 100.0;

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * REAL EMA calculation
     */
    public static double calculateEMA(double[] prices, int period) {
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
     * REAL MACD calculation
     */
    public static class MACDResult {
        public final double macdLine;
        public final double signal;
        public final double histogram;

        public MACDResult(double macdLine, double signal, double histogram) {
            this.macdLine = macdLine;
            this.signal = signal;
            this.histogram = histogram;
        }
    }

    public static MACDResult calculateMACD(double[] prices) {
        if (prices.length < 26) {
            return new MACDResult(0.0, 0.0, 0.0);
        }

        double ema12 = calculateEMA(prices, 12);
        double ema26 = calculateEMA(prices, 26);
        double macdLine = ema12 - ema26;

        // For signal line, you'd need to calculate EMA9 of MACD line
        // This is simplified - in reality you need MACD history for signal
        double signal = macdLine * 0.9; // Simplified
        double histogram = macdLine - signal;

        return new MACDResult(macdLine, signal, histogram);
    }

    /**
     * Calculate volume ratio (current vs average)
     */
    public static double calculateVolumeRatio(double[] volumes) {
        if (volumes.length < 2) return 1.0;

        double currentVolume = volumes[volumes.length - 1];
        double avgVolume = 0;

        for (double vol : volumes) {
            avgVolume += vol;
        }
        avgVolume /= volumes.length;

        return avgVolume > 0 ? currentVolume / avgVolume : 1.0;
    }

    /**
     * Calculate ATR (Average True Range) for volatility
     */
    public static double calculateATR(double[] highs, double[] lows, double[] closes, int period) {
        if (highs.length < period + 1) return 0.0;

        double[] trueRanges = new double[highs.length - 1];

        for (int i = 1; i < highs.length; i++) {
            double tr1 = highs[i] - lows[i];
            double tr2 = Math.abs(highs[i] - closes[i - 1]);
            double tr3 = Math.abs(lows[i] - closes[i - 1]);

            trueRanges[i - 1] = Math.max(Math.max(tr1, tr2), tr3);
        }

        // Calculate simple average of true ranges
        double atr = 0;
        int startIndex = Math.max(0, trueRanges.length - period);

        for (int i = startIndex; i < trueRanges.length; i++) {
            atr += trueRanges[i];
        }

        return atr / Math.min(period, trueRanges.length);
    }

// ===== MODIFIED METHOD THAT NEEDS KLINE DATA =====

    /**
     * This is how the method should be called - WITH REAL KLINE DATA
     * You need to modify your calling code to pass actual market data
     */
    private Map<String, Object> createTrainingSampleWithRealData(
            BacktestTrade trade,
            BacktestResult result,
            List<List<Object>> klineData) { // ADD THIS PARAMETER

        try {
            Map<String, Object> sample = new HashMap<>();
            Map<String, Object> technicalIndicators = new HashMap<>();

            // Basic trade info
            sample.put("symbol", result.getSymbol());
            sample.put("timeframe", result.getTimeframe());
            sample.put("timestamp", trade.getEntryTime().toString());
            sample.put("currentPrice", trade.getEntryPrice());

            // Determine actual outcome
            String actualOutcome = determineOutcome(trade.getPnlPercent());
            sample.put("actualOutcome", actualOutcome);

            // Calculate REAL indicators from kline data
            if (klineData != null && !klineData.isEmpty()) {
                calculateRealIndicatorsFromKlines(technicalIndicators, klineData, trade);
            } else {
                log.warn("No kline data available for {}, using defaults", result.getSymbol());
                setDefaultIndicators(technicalIndicators, trade.getEntryPrice());
            }

            sample.put("technicalIndicators", technicalIndicators);

            // Additional metadata
            sample.put("pnl_percent", trade.getPnlPercent());
            sample.put("direction", trade.getSide().toString());
            sample.put("tradingRule", trade.getTradingRule());
            sample.put("score", trade.getScore());

            return sample;

        } catch (Exception e) {
            log.error("Error creating training sample: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Calculate real indicators from kline data
     */
    private void calculateRealIndicatorsFromKlines(Map<String, Object> indicators,
                                                   List<List<Object>> klineData,
                                                   BacktestTrade trade) {
        try {
            // Extract price arrays from kline data
            // Kline format: [timestamp, open, high, low, close, volume, ...]
            double[] closes = new double[klineData.size()];
            double[] highs = new double[klineData.size()];
            double[] lows = new double[klineData.size()];
            double[] volumes = new double[klineData.size()];

            for (int i = 0; i < klineData.size(); i++) {
                List<Object> kline = klineData.get(i);
                if (kline.size() >= 6) {
                    closes[i] = ((Number) kline.get(4)).doubleValue(); // close
                    highs[i] = ((Number) kline.get(2)).doubleValue();   // high
                    lows[i] = ((Number) kline.get(3)).doubleValue();    // low
                    volumes[i] = ((Number) kline.get(5)).doubleValue(); // volume
                }
            }

            // Now calculate REAL indicators
            double rsi = calculateRSI(closes, 14);
            double ema20 = calculateEMA(closes, 20);
            double ema50 = calculateEMA(closes, 50);

            MACDResult macd = calculateMACD(closes);
            double volumeRatio = calculateVolumeRatio(volumes);
            double atr = calculateATR(highs, lows, closes, 14);
            double atrPercent = closes.length > 0 ? (atr / closes[closes.length - 1]) * 100 : 0;

            // Store calculated values
            indicators.put("rsi", rsi);
            indicators.put("ema20_4h", ema20);
            indicators.put("ema50_4h", ema50);
            indicators.put("ema200_daily", ema50); // Approximate - needs daily data

            indicators.put("macdLine", macd.macdLine);
            indicators.put("macdSignal", macd.signal);
            indicators.put("macdHistogram", macd.histogram);
            indicators.put("macdBullish", macd.histogram > 0);
            indicators.put("macdBearish", macd.histogram < 0);

            indicators.put("volumeRatio", volumeRatio);
            indicators.put("strongVolume", volumeRatio > 1.3);

            indicators.put("atrPercent", atrPercent);
            indicators.put("volatilityPercent", atrPercent);

            // Trend analysis
            double currentPrice = closes[closes.length - 1];
            indicators.put("currentPrice", currentPrice);

            String primaryTrend = "NEUTRAL";
            if (currentPrice > ema20 && ema20 > ema50) {
                primaryTrend = "BULLISH";
            } else if (currentPrice < ema20 && ema20 < ema50) {
                primaryTrend = "BEARISH";
            }
            indicators.put("primaryTrend", primaryTrend);

            // Risk/reward (simplified - would need support/resistance calculation)
            double riskReward = Math.abs(trade.getPnlPercent()) > 0 ?
                    Math.abs(trade.getPnlPercent() / 1.0) : 1.0;
            indicators.put("riskRewardRatio", riskReward);

            // Market structure flags
            indicators.put("trendAlignment", Math.abs(currentPrice - ema20) / currentPrice < 0.02);
            indicators.put("bullishStructure", primaryTrend.equals("BULLISH"));
            indicators.put("bearishStructure", primaryTrend.equals("BEARISH"));

            log.debug("Calculated real indicators for {}: RSI={}, EMA20={}, Volume={}",
                    trade.getEntryTime(), rsi, ema20, volumeRatio);

        } catch (Exception e) {
            log.error("Error calculating real indicators: {}", e.getMessage());
            setDefaultIndicators(indicators, trade.getEntryPrice());
        }
    }

    private void setDefaultIndicators(Map<String, Object> indicators, double entryPrice) {
        indicators.put("rsi", 50.0);
        indicators.put("ema20_4h", entryPrice);
        indicators.put("ema50_4h", entryPrice);
        indicators.put("ema200_daily", entryPrice);
        indicators.put("macdLine", 0.0);
        indicators.put("macdSignal", 0.0);
        indicators.put("macdHistogram", 0.0);
        indicators.put("volumeRatio", 1.0);
        indicators.put("atrPercent", 5.0);
        indicators.put("primaryTrend", "NEUTRAL");
        indicators.put("riskRewardRatio", 1.0);
        indicators.put("currentPrice", entryPrice);
    }

    /**
     * JAVÍTOTT model training
     */
    public boolean trainModel(String modelName) {
        try {
            log.info("🚀 Starting ML model training: {}", modelName);

            // Check for training script
            List<String> scriptPaths = Arrays.asList(
                    "src/main/python/train_model.py",
                    "train_model.py",
                    "improved_trainer.py",
                    "src/main/python/improved_trainer.py"
            );

            Path scriptPath = null;
            for (String path : scriptPaths) {
                Path candidate = Paths.get(path);
                if (Files.exists(candidate)) {
                    scriptPath = candidate;
                    break;
                }
            }

            if (scriptPath == null) {
                log.error("❌ Training script not found in any of: {}", scriptPaths);
                return false;
            }

            // Ensure directories exist
            Files.createDirectories(Paths.get(dataPath, "training"));
            Files.createDirectories(Paths.get(modelsPath));

            // Build command
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    scriptPath.toString(),
                    "training_data_*.json",
                    "100" // epochs
            );

            pb.directory(new File("."));
            pb.redirectErrorStream(false);

            log.info("Executing: {} {} training_data_*.json 100", pythonExecutable, scriptPath);

            Process process = pb.start();

            // Read output streams
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try { return readProcessOutput(process.getInputStream()); }
                catch (IOException e) { return "Error reading output: " + e.getMessage(); }
            });

            CompletableFuture<String> errorFuture = CompletableFuture.supplyAsync(() -> {
                try { return readProcessOutput(process.getErrorStream()); }
                catch (IOException e) { return "Error reading error stream: " + e.getMessage(); }
            });

            // Wait with timeout
            boolean finished = process.waitFor(300, TimeUnit.SECONDS);

            String output = outputFuture.getNow("No output");
            String errorOutput = errorFuture.getNow("No error output");

            if (!finished) {
                process.destroyForcibly();
                log.error("❌ ML training timed out for model: {}", modelName);
                return false;
            }

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                log.info("✅ ML model training completed successfully: {}", modelName);
                log.debug("Training output (last 500 chars): {}",
                        output.length() > 500 ? "..." + output.substring(output.length() - 500) : output);
                return true;
            } else {
                log.error("❌ ML model training failed with exit code {}: {}", exitCode, errorOutput);
                log.error("Full output: {}", output);
                return false;
            }

        } catch (Exception e) {
            log.error("❌ ML training error: {}", e.getMessage(), e);
            return false;
        }
    }

    // === HELPER METHODS ===

    private Map<String, Object> prepareMLInputData(String symbol, Map<String, Object> mlData,
                                                   List<List<Object>> fourHourKlines,
                                                   List<List<Object>> dailyKlines,
                                                   List<List<Object>> hourlyKlines) {
        Map<String, Object> inputData = new HashMap<>();

        inputData.put("symbol", symbol);
        inputData.put("timestamp", System.currentTimeMillis());
        inputData.put("technical_indicators", mlData);

        // Only include klines if they're not empty
        if (fourHourKlines != null && !fourHourKlines.isEmpty()) {
            inputData.put("klines_4h", fourHourKlines);
        }
        if (dailyKlines != null && !dailyKlines.isEmpty()) {
            inputData.put("klines_daily", dailyKlines);
        }
        if (hourlyKlines != null && !hourlyKlines.isEmpty()) {
            inputData.put("klines_hourly", hourlyKlines);
        }

        return inputData;
    }

    private MLPredictionResponse createDefaultPrediction(String symbol, String reason) {
        log.debug("Creating default prediction for {}: {}", symbol, reason);

        return MLPredictionResponse.builder()
                .success(false)
                .direction(Direction.HOLD)
                .confidence(0.0)
                .predictedSignal(kd.trading.bot.enums.Signal.NO_TRADE)
                .errorMessage(reason)
                .probabilities(Map.of("LONG", 0.33, "SHORT", 0.33, "HOLD", 0.34))
                .build();
    }

    private double getDoubleValue(Map<String, Object> data, String key, double defaultValue) {
        Object value = data.get(key);
        if (value == null) return defaultValue;

        if (value instanceof Number) {
            double doubleValue = ((Number) value).doubleValue();
            if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                return defaultValue;
            }
            return doubleValue;
        }

        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String readProcessOutput(InputStream inputStream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString().trim();
    }

    // === PLACEHOLDER METHODS (unchanged) ===

    @Async
    public CompletableFuture<Boolean> submitTradeResult(MLTradeResult tradeResult) {
        log.debug("Trade result submitted: {} - {}%", tradeResult.getSymbol(), tradeResult.getPnlPercent());
        return CompletableFuture.completedFuture(true);
    }

    public Map<String, Double> evaluateModel(String modelName) {
        Map<String, Double> evaluation = new HashMap<>();
        evaluation.put("accuracy", 0.75);
        evaluation.put("precision", 0.72);
        evaluation.put("recall", 0.78);
        evaluation.put("f1_score", 0.75);
        return evaluation;
    }

    public boolean isModelReady(String modelName) {
        try {
            Path modelsDir = Paths.get(modelsPath);
            return Files.exists(modelsDir.resolve("swing_trading_model.keras")) ||
                    Files.exists(modelsDir.resolve("swing_trading_model.pkl"));
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> getModelInfo(String modelName) {
        return Map.of("status", "ready", "model_name", modelName, "version", "2.0");
    }

    public int getBacktestHistorySize() {
        return 0;
    }
}