package kd.trading.bot.service.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.SwingAlgoTrainingPoint;
import kd.trading.bot.model.ml.MLTradeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PythonMLService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${python.ml.data.path:src/main/resources/data}")
    private String dataPath;

    @Value("${python.ml.models.path:src/main/resources/data/models}")
    private String modelsPath;

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    /**
     * MAIN METHOD: Export SwingAlgo training data
     * This extracts all SwingAlgo analysis results from backtest results
     */
    public void exportSwingAlgoTrainingData(List<BacktestResult> backtestResults) {
        try {
            log.info("Exporting SwingAlgo training data from {} backtest results", backtestResults.size());

            Path mlDataDir = Paths.get(dataPath, "training");
            Files.createDirectories(mlDataDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path trainingFile = mlDataDir.resolve("swingalgo_training_data_" + timestamp + ".json");

            // Collect all SwingAlgo analysis points
            List<Map<String, Object>> allTrainingData = new ArrayList<>();
            int totalAnalysisPoints = 0;
            int validTrainingPoints = 0;

            for (BacktestResult result : backtestResults) {
                if (!result.getSwingAlgoAnalysisPoints().isEmpty()) {
                    totalAnalysisPoints += result.getSwingAlgoAnalysisPoints().size();

                    for (SwingAlgoTrainingPoint point : result.getSwingAlgoAnalysisPoints()) {
                        // Convert SwingAlgo analysis to ML training format
                        Map<String, Object> trainingData = point.toMLTrainingData();

                        // Add backtest metadata
                        trainingData.put("backtest_symbol", result.getSymbol());
                        trainingData.put("backtest_timeframe", result.getTimeframe());
                        trainingData.put("backtest_return_percent", result.getTotalReturnPercent());
                        trainingData.put("backtest_win_rate", result.getWinRate());

                        // Only include points with definitive outcomes for training
                        if (isValidTrainingPoint(point)) {
                            allTrainingData.add(trainingData);
                            validTrainingPoints++;
                        }
                    }

                    log.info("Processed SwingAlgo data for {}: {} analysis points",
                            result.getSymbol(), result.getSwingAlgoAnalysisPoints().size());
                }
            }

            if (allTrainingData.isEmpty()) {
                log.warn("No valid SwingAlgo training data found in backtest results");
                return;
            }

            // Create comprehensive training dataset
            Map<String, Object> trainingDataset = createSwingAlgoTrainingDataset(allTrainingData, backtestResults);

            // Export to JSON
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(trainingDataset);
            Files.writeString(trainingFile, json);

            log.info("SwingAlgo training data exported: {} total analysis points, {} valid training points to: {}",
                    totalAnalysisPoints, validTrainingPoints, trainingFile);

            // Generate quality report
            generateSwingAlgoDataReport(allTrainingData, backtestResults);

        } catch (Exception e) {
            log.error("Failed to export SwingAlgo training data: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if SwingAlgo training point is valid for ML training
     */
    private boolean isValidTrainingPoint(SwingAlgoTrainingPoint point) {
        // Must have actual outcome
        if (point.getActualOutcome() == null) return false;

        // Skip HOLDING states (interim states, not final outcomes)
        if ("HOLDING".equals(point.getActualOutcome())) return false;

        // Must have SwingAlgo features
        if (point.getSwingAlgoFeatures() == null || point.getSwingAlgoFeatures().isEmpty()) return false;

        // Must have valid PnL data for trades
        return point.getActualOutcome().equals("NO_TRADE") || point.getActualPnlPercent() != null;
    }

    /**
     * Create comprehensive SwingAlgo training dataset
     */
    private Map<String, Object> createSwingAlgoTrainingDataset(List<Map<String, Object>> trainingData,
                                                               List<BacktestResult> backtestResults) {

        Map<String, Object> dataset = new HashMap<>();

        // Training samples
        dataset.put("training_samples", trainingData);

        // Dataset metadata
        dataset.put("metadata", Map.of(
                "data_source", "SwingAlgoService",
                "generated_at", LocalDateTime.now().toString(),
                "total_samples", trainingData.size(),
                "backtest_count", backtestResults.size(),
                "symbols", backtestResults.stream().map(BacktestResult::getSymbol).collect(Collectors.toSet()),
                "data_quality", "SWINGALGO_ANALYSIS",
                "version", "1.0_swingalgo"
        ));

        // Feature statistics
        dataset.put("feature_statistics", calculateSwingAlgoFeatureStats(trainingData));

        // Outcome distribution
        Map<String, Long> outcomeDistribution = trainingData.stream()
                .collect(Collectors.groupingBy(
                        data -> (String) data.get("actual_outcome"),
                        Collectors.counting()
                ));
        dataset.put("outcome_distribution", outcomeDistribution);

        // Signal accuracy statistics
        Map<String, Object> accuracyStats = calculateSwingAlgoAccuracyStats(trainingData);
        dataset.put("swingalgo_accuracy_stats", accuracyStats);

        return dataset;
    }

    /**
     * Calculate SwingAlgo feature statistics
     */
    private Map<String, Object> calculateSwingAlgoFeatureStats(List<Map<String, Object>> trainingData) {
        Map<String, Object> stats = new HashMap<>();

        if (trainingData.isEmpty()) return stats;

        // Key SwingAlgo features to analyze
        String[] keyFeatures = {
                "rsi", "ema20_4h", "ema50_4h", "ema200_daily", "primaryTrend", "shortTermTrend",
                "trendAlignment", "trendStrength", "macdLine", "macdSignal", "macdHistogram",
                "macdBullish", "macdBearish", "volumeRatio", "strongVolume", "atr",
                "volatilityPercent", "riskRewardRatio", "nearestSupport", "nearestResistance",
                "bullishStructure", "bearishStructure", "consolidation", "price_vs_ema20",
                "price_vs_ema50", "ema_alignment", "rsi_oversold", "rsi_overbought", "high_volume"
        };

        for (String feature : keyFeatures) {
            List<Double> values = new ArrayList<>();
            for (Map<String, Object> data : trainingData) {
                Map<String, Object> features = (Map<String, Object>) data.get("swingalgo_features");
                if (features != null && features.containsKey(feature)) {
                    Object value = features.get(feature);
                    if (value instanceof Number) {
                        values.add(((Number) value).doubleValue());
                    } else if (value instanceof Boolean) {
                        values.add(((Boolean) value) ? 1.0 : 0.0);
                    }
                }
            }

            if (!values.isEmpty()) {
                Map<String, Object> featureStats = new HashMap<>();
                featureStats.put("count", values.size());
                featureStats.put("mean", values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
                featureStats.put("min", values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0));
                featureStats.put("max", values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0));
                featureStats.put("std", calculateStandardDeviation(values));
                stats.put(feature, featureStats);
            }
        }

        return stats;
    }

    /**
     * Calculate SwingAlgo accuracy statistics
     */
    private Map<String, Object> calculateSwingAlgoAccuracyStats(List<Map<String, Object>> trainingData) {
        Map<String, Object> accuracyStats = new HashMap<>();

        // Signal accuracy by signal type
        Map<String, List<Double>> signalPnL = new HashMap<>();
        Map<String, Integer> signalCounts = new HashMap<>();
        Map<String, Integer> signalWins = new HashMap<>();

        for (Map<String, Object> data : trainingData) {
            String signal = (String) data.get("swingalgo_signal");
            String outcome = (String) data.get("actual_outcome");
            Double pnl = (Double) data.get("actual_pnl_percent");

            if (signal != null && pnl != null && !"NO_TRADE".equals(outcome)) {
                signalPnL.computeIfAbsent(signal, k -> new ArrayList<>()).add(pnl);
                signalCounts.put(signal, signalCounts.getOrDefault(signal, 0) + 1);

                if (pnl > 0) {
                    signalWins.put(signal, signalWins.getOrDefault(signal, 0) + 1);
                }
            }
        }

        // Calculate statistics for each signal type
        for (String signal : signalCounts.keySet()) {
            Map<String, Object> signalStats = new HashMap<>();
            List<Double> pnls = signalPnL.get(signal);

            signalStats.put("total_trades", signalCounts.get(signal));
            signalStats.put("winning_trades", signalWins.getOrDefault(signal, 0));
            signalStats.put("win_rate", (double) signalWins.getOrDefault(signal, 0) / signalCounts.get(signal) * 100);
            signalStats.put("avg_pnl", pnls.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
            signalStats.put("total_pnl", pnls.stream().mapToDouble(Double::doubleValue).sum());

            accuracyStats.put(signal + "_stats", signalStats);
        }

        // Overall SwingAlgo performance
        int totalTrades = signalCounts.values().stream().mapToInt(Integer::intValue).sum();
        int totalWins = signalWins.values().stream().mapToInt(Integer::intValue).sum();
        double overallWinRate = totalTrades > 0 ? (double) totalWins / totalTrades * 100 : 0.0;

        accuracyStats.put("overall_stats", Map.of(
                "total_trades", totalTrades,
                "total_wins", totalWins,
                "overall_win_rate", overallWinRate,
                "signal_types", signalCounts.keySet()
        ));

        return accuracyStats;
    }

    /**
     * Generate SwingAlgo data quality report
     */
    private void generateSwingAlgoDataReport(List<Map<String, Object>> trainingData, List<BacktestResult> backtestResults) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path reportDir = Paths.get(dataPath, "reports");
            Files.createDirectories(reportDir);
            Path reportFile = reportDir.resolve("swingalgo_data_report_" + timestamp + ".txt");

            StringBuilder report = new StringBuilder();
            report.append("=== SwingAlgo Training Data Quality Report ===\n");
            report.append("Generated at: ").append(LocalDateTime.now()).append("\n\n");

            // Dataset overview
            report.append("Dataset Overview:\n");
            report.append("- Total training samples: ").append(trainingData.size()).append("\n");
            report.append("- Source backtest results: ").append(backtestResults.size()).append("\n");

            Set<String> symbols = backtestResults.stream().map(BacktestResult::getSymbol).collect(Collectors.toSet());
            report.append("- Unique symbols: ").append(symbols.size())
                    .append(" (").append(String.join(", ", symbols)).append(")\n\n");

            // Outcome distribution (handle nulls as "UNKNOWN")
            Map<String, Long> outcomeDistribution = trainingData.stream()
                    .collect(Collectors.groupingBy(
                            data -> Optional.ofNullable((String) data.get("actual_outcome")).orElse("UNKNOWN"),
                            Collectors.counting()
                    ));

            report.append("Outcome Distribution:\n");
            outcomeDistribution.forEach((outcome, count) ->
                    report.append("- ").append(outcome).append(": ").append(count)
                            .append(" (").append(String.format("%.1f%%", count * 100.0 / trainingData.size())).append(")\n"));
            report.append("\n");

            // Signal distribution (handle nulls as "UNKNOWN")
            Map<String, Long> signalDistribution = trainingData.stream()
                    .collect(Collectors.groupingBy(
                            data -> Optional.ofNullable((String) data.get("swingalgo_signal")).orElse("UNKNOWN"),
                            Collectors.counting()
                    ));

            report.append("SwingAlgo Signal Distribution:\n");
            signalDistribution.forEach((signal, count) ->
                    report.append("- ").append(signal).append(": ").append(count)
                            .append(" (").append(String.format("%.1f%%", count * 100.0 / trainingData.size())).append(")\n"));
            report.append("\n");

            // Backtest performance summary
            report.append("Backtest Performance Summary:\n");
            double avgReturn = backtestResults.stream().mapToDouble(BacktestResult::getTotalReturnPercent).average().orElse(0.0);
            double avgWinRate = backtestResults.stream().mapToDouble(BacktestResult::getWinRate).average().orElse(0.0);
            int totalTrades = backtestResults.stream().mapToInt(BacktestResult::getTotalTrades).sum();

            report.append("- Average return: ").append(String.format("%.2f%%", avgReturn)).append("\n");
            report.append("- Average win rate: ").append(String.format("%.2f%%", avgWinRate)).append("\n");
            report.append("- Total trades across all backtests: ").append(totalTrades).append("\n\n");

            // Data quality metrics
            report.append("Data Quality Metrics:\n");
            long validFeatureCount = trainingData.stream()
                    .mapToLong(data -> {
                        Map<String, Object> features = (Map<String, Object>) data.get("swingalgo_features");
                        return features != null ? features.size() : 0;
                    })
                    .sum();
            double avgFeatureCount = validFeatureCount / (double) trainingData.size();
            report.append("- Average features per sample: ").append(String.format("%.1f", avgFeatureCount)).append("\n");

            long samplesWithPnL = trainingData.stream()
                    .mapToLong(data -> data.get("actual_pnl_percent") != null ? 1 : 0)
                    .sum();
            report.append("- Samples with PnL data: ").append(samplesWithPnL)
                    .append(" (").append(String.format("%.1f%%", samplesWithPnL * 100.0 / trainingData.size())).append(")\n");

            Files.writeString(reportFile, report.toString());
            log.info("SwingAlgo data quality report generated: {}", reportFile);

        } catch (Exception e) {
            log.error("Failed to generate SwingAlgo data quality report: {}", e.getMessage(), e);
        }
    }

    /**
     * Async method to train ML model using SwingAlgo data
     */
    @Async
    public CompletableFuture<Boolean> trainSwingAlgoModel(String trainingDataFile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Starting SwingAlgo ML model training with data: {}", trainingDataFile);

                Path pythonScript = Paths.get(dataPath, "scripts", "train_swingalgo_model.py");
                if (!Files.exists(pythonScript)) {
                    log.error("SwingAlgo training script not found: {}", pythonScript);
                    return false;
                }

                ProcessBuilder processBuilder = new ProcessBuilder(
                        pythonExecutable,
                        pythonScript.toString(),
                        "--data_file", trainingDataFile,
                        "--models_path", modelsPath
                );

                processBuilder.directory(new File(dataPath));
                Process process = processBuilder.start();

                boolean finished = process.waitFor(30, TimeUnit.MINUTES);
                if (!finished) {
                    process.destroyForcibly();
                    log.error("SwingAlgo model training timed out after 30 minutes");
                    return false;
                }

                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    log.info("SwingAlgo ML model training completed successfully");
                    return true;
                } else {
                    log.error("SwingAlgo model training failed with exit code: {}", exitCode);
                    return false;
                }

            } catch (Exception e) {
                log.error("SwingAlgo model training error: {}", e.getMessage(), e);
                return false;
            }
        });
    }

    /**
     * Predict trading outcome using trained SwingAlgo model
     */
    public MLTradeResult predictWithSwingAlgoModel(Map<String, Object> features) {
        try {
            Path modelPath = Paths.get(modelsPath, "swingalgo_model.pkl");
            if (!Files.exists(modelPath)) {
                log.warn("SwingAlgo ML model not found: {}", modelPath);
                return createErrorResult("MODEL_UNAVAILABLE");
            }

            Path pythonScript = Paths.get(dataPath, "scripts", "predict_swingalgo.py");
            if (!Files.exists(pythonScript)) {
                log.error("SwingAlgo prediction script not found: {}", pythonScript);
                return createErrorResult("SCRIPT_UNAVAILABLE");
            }

            // Prepare features JSON
            String featuresJson = objectMapper.writeValueAsString(features);
            Path tempFeaturesFile = Files.createTempFile("swingalgo_features_", ".json");
            Files.writeString(tempFeaturesFile, featuresJson);

            ProcessBuilder processBuilder = new ProcessBuilder(
                    pythonExecutable,
                    pythonScript.toString(),
                    "--features_file", tempFeaturesFile.toString(),
                    "--model_path", modelPath.toString()
            );

            processBuilder.directory(new File(dataPath));
            Process process = processBuilder.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Files.deleteIfExists(tempFeaturesFile);
                return createErrorResult("TIMEOUT");
            }

            // Clean up temp file
            Files.deleteIfExists(tempFeaturesFile);

            if (process.exitValue() != 0) {
                return createErrorResult("PROCESS_FAILED");
            }

            // Read prediction result (would need to implement result reading from stdout/file)
            // This is a simplified version - in practice you'd read the actual prediction
            return createPredictionResult("LONG", 0.75); // Placeholder values

        } catch (Exception e) {
            log.error("SwingAlgo prediction error: {}", e.getMessage(), e);
            return createErrorResult("EXCEPTION");
        }
    }

    /**
     * Create prediction result from ML model output
     */
    private MLTradeResult createPredictionResult(String prediction, double confidence) {
        return MLTradeResult.builder()
                .modelVersion("SwingAlgo_ML")
                .originalPrediction(prediction)
                .originalConfidence(confidence)
                .actualOutcome("PREDICTED") // This indicates it's a prediction, not actual outcome
                .entryTime(LocalDateTime.now()) // When prediction was made
                .build();
    }

    /**
     * Create error result for ML prediction failures
     */
    private MLTradeResult createErrorResult(String errorType) {
        return MLTradeResult.builder()
                .modelVersion("SwingAlgo_ML")
                .originalPrediction(errorType)
                .actualOutcome("ERROR")
                .entryTime(LocalDateTime.now())
                .build();
    }

    // Helper methods
    private double calculateStandardDeviation(List<Double> values) {
        if (values.size() < 2) return 0.0;

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }

}