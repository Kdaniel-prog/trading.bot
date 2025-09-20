package kd.trading.bot.service.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.CoinAnalysis;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestTrade;
import kd.trading.bot.model.ml.MLFeatures;
import kd.trading.bot.model.ml.MLPrediction;
import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.model.ml.MLTrainingData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PythonMLService {

    private final ObjectMapper objectMapper;

    @Value("${ml.python.script.path:/app/python/ml}")
    private String pythonScriptPath;

    @Value("${ml.python.executable:python3}")
    private String pythonExecutable;

    @Value("${ml.data.output.path:/app/data/ml}")
    private String mlDataPath;

    @Value("${ml.model.path:/app/models}")
    private String modelPath;

    @Value("${ml.training.enabled:true}")
    private boolean trainingEnabled;

    /**
     * FIXED METHOD: Get ML prediction with proper async signature matching SwingAlgoService usage
     */
    @Async
    public CompletableFuture<MLPredictionResponse> getPrediction(String symbol,
                                                                 List<List<Object>> fourHourKlines,
                                                                 List<List<Object>> dailyKlines,
                                                                 List<List<Object>> hourlyKlines,
                                                                 Map<String, Object> mlData) {
        try {
            // Convert klines data to features for ML processing
            MLFeatures features = convertKlinesToFeatures(symbol, fourHourKlines, dailyKlines, hourlyKlines, mlData);

            // Save features to temp file for Python processing
            String tempFile = saveTempFeatures(features);

            // Call Python prediction script with proper model selection
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    Paths.get(pythonScriptPath, "predict.py").toString(),
                    "--features-file", tempFile,
                    "--model-path", modelPath,
                    "--symbol", symbol
            );

            pb.directory(new File(pythonScriptPath));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read prediction result with timeout
            StringBuilder output = new StringBuilder();
            StringBuilder errors = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("ERROR:") || line.startsWith("Exception:")) {
                        errors.append(line).append("\n");
                        log.warn("Python ML Warning: {}", line);
                    } else {
                        output.append(line);
                    }
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("ML prediction timeout for {}", symbol);
                return CompletableFuture.completedFuture(createErrorResponse("Prediction timeout"));
            }

            // Clean up temp file
            try {
                Files.deleteIfExists(Paths.get(tempFile));
            } catch (Exception e) {
                log.debug("Failed to delete temp file: {}", tempFile);
            }

            if (process.exitValue() == 0 && output.length() > 0) {
                try {
                    // Parse Python output as JSON
                    Map<String, Object> result = objectMapper.readValue(output.toString(), Map.class);

                    MLPredictionResponse prediction = MLPredictionResponse.builder()
                            .success(true)
                            .confidence((Double) result.getOrDefault("confidence", 0.0))
                            .predictedSignal(parseSignal((String) result.getOrDefault("signal", "NO_TRADE")))
                            .expectedReturn((Double) result.getOrDefault("expected_return", 0.0))
                            .riskScore((Double) result.getOrDefault("risk_score", 0.5))
                            .modelVersion((String) result.getOrDefault("model_version", "unknown"))
                            .features(features)
                            .timestamp(LocalDateTime.now())
                            .build();

                    log.debug("ML prediction successful for {}: signal={}, confidence={:.3f}",
                            symbol, prediction.getPredictedSignal(), prediction.getConfidence());

                    return CompletableFuture.completedFuture(prediction);

                } catch (Exception e) {
                    log.error("Failed to parse ML prediction output for {}: {}", symbol, e.getMessage());
                    return CompletableFuture.completedFuture(createErrorResponse("Failed to parse prediction: " + e.getMessage()));
                }
            } else {
                String errorMsg = errors.length() > 0 ? errors.toString() : "Unknown prediction error";
                log.error("ML prediction failed for {} (exit code: {}): {}", symbol, process.exitValue(), errorMsg);
                return CompletableFuture.completedFuture(createErrorResponse(errorMsg));
            }

        } catch (Exception e) {
            log.error("ML prediction error for {}: {}", symbol, e.getMessage(), e);
            return CompletableFuture.completedFuture(createErrorResponse("Prediction error: " + e.getMessage()));
        }
    }

    /**
     * LEGACY METHOD: Keep for backward compatibility with existing code
     */
    public MLPrediction getPrediction(CoinAnalysis analysis, Map<String, Object> marketData) {
        try {
            // Extract features from analysis
            MLFeatures features = extractFeatures(analysis, marketData);

            // Save features to temp file
            String tempFile = saveTempFeatures(features);

            // Call Python prediction script
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    Paths.get(pythonScriptPath, "predict.py").toString(),
                    "--features-file", tempFile,
                    "--model-path", modelPath
            );

            pb.directory(new File(pythonScriptPath));
            Process process = pb.start();

            // Read prediction result
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor(10, TimeUnit.SECONDS);

            // Clean up temp file
            Files.deleteIfExists(Paths.get(tempFile));

            if (process.exitValue() == 0) {
                Map<String, Object> result = objectMapper.readValue(output.toString(), Map.class);

                return MLPrediction.builder()
                        .success(true)
                        .confidence((Double) result.getOrDefault("confidence", 0.0))
                        .predictedSignal(parseSignal((String) result.getOrDefault("signal", "NO_TRADE")))
                        .expectedReturn((Double) result.getOrDefault("expected_return", 0.0))
                        .build();
            } else {
                log.error("ML prediction failed");
                return MLPrediction.builder()
                        .success(false)
                        .confidence(0.0)
                        .errorMessage("Prediction failed")
                        .build();
            }

        } catch (Exception e) {
            log.error("ML prediction error: {}", e.getMessage(), e);
            return MLPrediction.builder()
                    .success(false)
                    .confidence(0.0)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * Convert klines data to ML features
     */
    private MLFeatures convertKlinesToFeatures(String symbol,
                                               List<List<Object>> fourHourKlines,
                                               List<List<Object>> dailyKlines,
                                               List<List<Object>> hourlyKlines,
                                               Map<String, Object> mlData) {

        // Extract recent price data
        List<Object> latestCandle = fourHourKlines.get(fourHourKlines.size() - 1);
        double currentPrice = Double.parseDouble(latestCandle.get(4).toString());
        double volume = Double.parseDouble(latestCandle.get(5).toString());

        // Calculate basic technical indicators for features
        List<Double> closes4h = fourHourKlines.stream()
                .map(k -> Double.parseDouble(k.get(4).toString()))
                .toList();

        List<Double> volumes4h = fourHourKlines.stream()
                .map(k -> Double.parseDouble(k.get(5).toString()))
                .toList();

        // Simple moving averages for features
        double sma_20 = closes4h.subList(Math.max(0, closes4h.size() - 20), closes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(currentPrice);

        double sma_50 = closes4h.subList(Math.max(0, closes4h.size() - 50), closes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(currentPrice);

        // Volume ratio
        double avgVolume = volumes4h.subList(Math.max(0, volumes4h.size() - 20), volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(volume);
        double volumeRatio = avgVolume > 0 ? volume / avgVolume : 1.0;

        return MLFeatures.builder()
                .symbol(symbol)
                .timestamp(LocalDateTime.now())
                .currentPrice(currentPrice)
                .volume(volume)
                .volumeRatio(volumeRatio)
                .sma_20(sma_20)
                .sma_50(sma_50)
                // Extract from mlData if available
                .rsi((Double) mlData.getOrDefault("rsi", 50.0))
                .macd((Double) mlData.getOrDefault("macd", 0.0))
                .tradingRule((Integer) mlData.getOrDefault("tradingRule", 0))
                .algoScore((Double) mlData.getOrDefault("algoScore", 0.0))
                // Add trend analysis features
                .trendStrength(extractTrendStrength(mlData))
                .momentumScore(extractMomentumScore(mlData))
                .riskScore(extractRiskScore(mlData))
                .build();
    }

    private MLPredictionResponse createErrorResponse(String errorMessage) {
        return MLPredictionResponse.builder()
                .success(false)
                .confidence(0.0)
                .predictedSignal(Signal.NO_TRADE)
                .expectedReturn(0.0)
                .riskScore(1.0)
                .errorMessage(errorMessage)
                .timestamp(LocalDateTime.now())
                .build();
    }

    private Signal parseSignal(String signalStr) {
        try {
            return Signal.valueOf(signalStr.toUpperCase());
        } catch (Exception e) {
            log.warn("Unknown signal: {}, defaulting to NO_TRADE", signalStr);
            return Signal.NO_TRADE;
        }
    }

    private double extractTrendStrength(Map<String, Object> mlData) {
        try {
            Map<String, Object> trendAnalysis = (Map<String, Object>) mlData.get("trendAnalysis");
            if (trendAnalysis != null) {
                return (Double) trendAnalysis.getOrDefault("trendStrength", 0.0);
            }
        } catch (Exception e) {
            log.debug("Failed to extract trend strength: {}", e.getMessage());
        }
        return 0.0;
    }

    private double extractMomentumScore(Map<String, Object> mlData) {
        try {
            Map<String, Object> momentumAnalysis = (Map<String, Object>) mlData.get("momentumAnalysis");
            if (momentumAnalysis != null) {
                double rsi = (Double) momentumAnalysis.getOrDefault("rsi", 50.0);
                boolean macdBullish = (Boolean) momentumAnalysis.getOrDefault("macdBullish", false);
                return rsi + (macdBullish ? 10.0 : -10.0);
            }
        } catch (Exception e) {
            log.debug("Failed to extract momentum score: {}", e.getMessage());
        }
        return 50.0;
    }

    private double extractRiskScore(Map<String, Object> mlData) {
        try {
            Map<String, Object> riskAnalysis = (Map<String, Object>) mlData.get("riskAnalysis");
            if (riskAnalysis != null) {
                return (Double) riskAnalysis.getOrDefault("riskRewardRatio", 1.0);
            }
        } catch (Exception e) {
            log.debug("Failed to extract risk score: {}", e.getMessage());
        }
        return 1.0;
    }

    /**
     * Extract ML features from backtest results for training
     */
    public void exportTrainingData(List<BacktestResult> backtestResults) {
        if (!trainingEnabled) {
            log.info("ML training disabled, skipping data export");
            return;
        }

        try {
            List<MLTrainingData> trainingData = new ArrayList<>();

            for (BacktestResult result : backtestResults) {
                if (!result.isSuccess() || result.getTrades().isEmpty()) {
                    continue;
                }

                for (BacktestTrade trade : result.getTrades()) {
                    MLTrainingData data = extractTrainingData(trade, result);
                    trainingData.add(data);
                }
            }

            if (!trainingData.isEmpty()) {
                saveTrainingData(trainingData);
                log.info("Exported {} training samples for ML", trainingData.size());
            }

        } catch (Exception e) {
            log.error("Failed to export training data: {}", e.getMessage(), e);
        }
    }

    /**
     * Train the ML model using collected data
     */
    public boolean trainModel(String modelName) {
        try {
            log.info("Starting ML model training: {}", modelName);

            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    Paths.get(pythonScriptPath, "train_model.py").toString(),
                    "--model-name", modelName,
                    "--data-path", mlDataPath,
                    "--model-path", modelPath
            );

            pb.directory(new File(pythonScriptPath));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Log Python output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("Python Training: {}", line);
                }
            }

            boolean finished = process.waitFor(30, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.error("Model training timed out");
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("Model training completed successfully: {}", modelName);
                return true;
            } else {
                log.error("Model training failed with exit code: {}", exitCode);
                return false;
            }

        } catch (Exception e) {
            log.error("Model training error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Evaluate model performance on test data
     */
    public Map<String, Double> evaluateModel(String modelName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    Paths.get(pythonScriptPath, "evaluate_model.py").toString(),
                    "--model-name", modelName,
                    "--model-path", modelPath,
                    "--test-data-path", mlDataPath
            );

            pb.directory(new File(pythonScriptPath));
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor(5, TimeUnit.MINUTES);

            if (process.exitValue() == 0) {
                return objectMapper.readValue(output.toString(), Map.class);
            } else {
                log.error("Model evaluation failed");
                return new HashMap<>();
            }

        } catch (Exception e) {
            log.error("Model evaluation error: {}", e.getMessage(), e);
            return new HashMap<>();
        }
    }

    // Helper methods remain the same
    private MLTrainingData extractTrainingData(BacktestTrade trade, BacktestResult result) {
        return MLTrainingData.builder()
                .symbol(trade.getSymbol())
                .timestamp(trade.getEntryTime())
                .entryPrice(trade.getEntryPrice())
                .exitPrice(trade.getExitPrice())
                .side(trade.getSide())
                .tradingRule(trade.getTradingRule())
                .algoScore(trade.getScore())
                .pnlPercent(trade.getPnlPercent())
                .holdingTimeHours(trade.getHoldingTimeHours())
                .isWinning(trade.isWinningTrade())
                .build();
    }

    private MLFeatures extractFeatures(CoinAnalysis analysis, Map<String, Object> marketData) {
        return MLFeatures.builder()
                .symbol(analysis.getSymbol())
                .timestamp(LocalDateTime.now())
                .currentPrice((Double) marketData.get("price"))
                .volume((Double) marketData.getOrDefault("volume", 0.0))
                .tradingRule(analysis.getTradingRule())
                .algoScore(analysis.getScore())
                .signal(analysis.getSignal())
                .rsi((Double) marketData.getOrDefault("rsi", 50.0))
                .macd((Double) marketData.getOrDefault("macd", 0.0))
                .bollinger_upper((Double) marketData.getOrDefault("bb_upper", 0.0))
                .bollinger_lower((Double) marketData.getOrDefault("bb_lower", 0.0))
                .sma_20((Double) marketData.getOrDefault("sma_20", 0.0))
                .ema_50((Double) marketData.getOrDefault("ema_50", 0.0))
                .build();
    }

    private void saveTrainingData(List<MLTrainingData> trainingData) throws IOException {
        Files.createDirectories(Paths.get(mlDataPath));

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("training_data_%s.json", timestamp);
        String filePath = Paths.get(mlDataPath, filename).toString();

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(new File(filePath), trainingData);

        log.info("Training data saved to: {}", filePath);
    }

    private String saveTempFeatures(MLFeatures features) throws IOException {
        String tempDir = System.getProperty("java.io.tmpdir");
        String tempFile = Paths.get(tempDir, "ml_features_" + System.currentTimeMillis() + ".json").toString();

        objectMapper.writeValue(new File(tempFile), features);
        return tempFile;
    }

    /**
     * Check if ML model is available and ready
     */
    public boolean isModelReady(String modelName) {
        String modelFile = Paths.get(modelPath, modelName + ".pkl").toString();
        return Files.exists(Paths.get(modelFile));
    }

    /**
     * Get model information and statistics
     */
    public Map<String, Object> getModelInfo(String modelName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    Paths.get(pythonScriptPath, "model_info.py").toString(),
                    "--model-name", modelName,
                    "--model-path", modelPath
            );

            pb.directory(new File(pythonScriptPath));
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            process.waitFor(10, TimeUnit.SECONDS);

            if (process.exitValue() == 0) {
                return objectMapper.readValue(output.toString(), Map.class);
            }

        } catch (Exception e) {
            log.error("Failed to get model info: {}", e.getMessage());
        }

        return new HashMap<>();
    }
}