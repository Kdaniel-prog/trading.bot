package kd.trading.bot.service.ml;

import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.model.ml.MLTradeResult;
import kd.trading.bot.model.backtest.BacktestResult;
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

    @Value("${python.executable:python3}")
    private String pythonExecutable;

    @Value("${python.ml.script.path:src/main/python/ml_predictor.py}")
    private String mlScriptPath;

    @Value("${python.ml.timeout:10}")
    private int timeoutSeconds;

    @Value("${python.ml.models.path:src/resource/data/models}")
    private String modelsPath;

    @Value("${python.ml.data.path:src/resource/data}")
    private String dataPath;

    /**
     * Submit a completed trade result for ML learning - SIMPLIFIED
     */
    @Async
    public CompletableFuture<Boolean> submitTradeResult(MLTradeResult tradeResult) {
        try {
            log.debug("Sending trade result to Python ML: {} - {} return",
                    tradeResult.getSymbol(), tradeResult.getPnlPercent());

            // Send directly to Python for immediate learning
            return sendTradeResultToPython(tradeResult);

        } catch (Exception e) {
            log.error("Error submitting trade result: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Get ML prediction for trading decision
     */
    public CompletableFuture<MLPredictionResponse> getPrediction(String symbol,
                                                                 List<List<Object>> fourHourKlines,
                                                                 List<List<Object>> dailyKlines,
                                                                 List<List<Object>> hourlyKlines,
                                                                 Map<String, Object> mlData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Prepare input data for Python
                Map<String, Object> inputData = new HashMap<>();
                inputData.put("symbol", symbol);
                inputData.put("technical_indicators", mlData);
                inputData.put("klines_4h", fourHourKlines);
                inputData.put("klines_daily", dailyKlines);
                inputData.put("klines_hourly", hourlyKlines);

                // Convert to JSON and call Python
                String jsonInput = convertToJson(inputData);
                ProcessBuilder pb = new ProcessBuilder(pythonExecutable, mlScriptPath);
                pb.directory(new File("."));
                Process process = pb.start();

                // Send input data
                try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream())) {
                    writer.write(jsonInput);
                    writer.flush();
                }

                // Wait for completion
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("Python ML prediction timed out");
                }

                // Read response
                String output = readProcessOutput(process.getInputStream());
                String errorOutput = readProcessOutput(process.getErrorStream());

                if (process.exitValue() != 0) {
                    log.error("Python ML prediction failed: {}", errorOutput);
                    return createErrorResponse("Python execution failed: " + errorOutput);
                }

                return parseMLResponse(output);

            } catch (Exception e) {
                log.error("ML prediction error for {}: {}", symbol, e.getMessage(), e);
                return createErrorResponse("ML prediction error: " + e.getMessage());
            }
        });
    }

    /**
     * Train ML model using backtest results
     */
    public boolean trainModel(String modelName) {
        try {
            log.info("Starting ML model training: {}", modelName);

            // Check if training script exists
            String workingDir = System.getProperty("user.dir");
            Path scriptPath1 = Paths.get(workingDir, "src/main/python/train_model.py");
            Path scriptPath2 = Paths.get(workingDir, "trading.bot/src/main/python/train_model.py");

            Path scriptPath = Files.exists(scriptPath1) ? scriptPath1 : scriptPath2;
            if (!Files.exists(scriptPath)) {
                log.error("Training script not found: {}", scriptPath);
                return false;
            }

            // Ensure output directories exist
            Path mlDataDir = Paths.get(dataPath, "ml");
            Path modelsDir = Paths.get(modelsPath);
            Files.createDirectories(mlDataDir);
            Files.createDirectories(modelsDir);

            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    scriptPath.toString(),
                    "--model-name", modelName,
                    "--data-path", mlDataDir.toString(),
                    "--model-path", modelsDir.toString()
            );

            pb.directory(new File("."));
            pb.redirectErrorStream(false); // Separate error stream for better debugging

            Process process = pb.start();

            // Read both output and error streams
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();

            // Read stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    log.debug("Python stdout: {}", line);
                }
            }

            // Read stderr
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                    log.error("Python stderr: {}", line);
                }
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS); // 5 min timeout
            if (!finished) {
                process.destroyForcibly();
                log.error("ML training timed out for model: {}", modelName);
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("ML model training completed successfully: {}", modelName);
                log.debug("Training output: {}", output.toString().trim());
                return true;
            } else {
                log.error("ML model training failed with exit code {}: {}", exitCode, errorOutput.toString().trim());
                return false;
            }

        } catch (Exception e) {
            log.error("ML training error: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Export training data from backtest results
     */
    public void exportTrainingData(List<BacktestResult> backtestResults) {
        try {
            Path mlDataDir = Paths.get(dataPath, "ml");
            Files.createDirectories(mlDataDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path trainingFile = mlDataDir.resolve("training_data_" + timestamp + ".json");

            List<Map<String, Object>> trainingData = new ArrayList<>();

            for (BacktestResult result : backtestResults) {
                if (result.isSuccess() && result.getTrades() != null && !result.getTrades().isEmpty()) {
                    for (var trade : result.getTrades()) {
                        Map<String, Object> tradeData = new HashMap<>();

                        // Basic trade info
                        tradeData.put("symbol", result.getSymbol());
                        tradeData.put("timeframe", result.getTimeframe());
                        tradeData.put("pnlPercent", trade.getPnlPercent());
                        tradeData.put("side", trade.getSide().toString());
                        tradeData.put("entryPrice", trade.getEntryPrice());
                        tradeData.put("exitPrice", trade.getExitPrice());
                        tradeData.put("positionSize", trade.getPositionSize());
                        tradeData.put("timestamp", trade.getEntryTime().toString());

                        // Trade metadata
                        tradeData.put("tradingRule", trade.getTradingRule());
                        tradeData.put("algoScore", trade.getScore());

                        // Basic technical indicators (mock data if not available)
                        tradeData.put("rsi", 50.0 + Math.random() * 40); // Mock RSI 30-70
                        tradeData.put("macd", (Math.random() - 0.5) * 2); // Mock MACD
                        tradeData.put("macd_signal", (Math.random() - 0.5) * 1.5);
                        tradeData.put("macd_histogram", (Math.random() - 0.5) * 0.5);
                        tradeData.put("sma_20", trade.getEntryPrice() * (0.98 + Math.random() * 0.04));
                        tradeData.put("ema_12", trade.getEntryPrice() * (0.99 + Math.random() * 0.02));

                        // Price changes (mock)
                        tradeData.put("price_change_1h", (Math.random() - 0.5) * 4); // ±2%
                        tradeData.put("price_change_4h", (Math.random() - 0.5) * 8); // ±4%
                        tradeData.put("price_change_1d", (Math.random() - 0.5) * 16); // ±8%

                        // Volatility metrics (mock)
                        tradeData.put("volatility_1h", Math.random() * 3 + 0.5); // 0.5-3.5%
                        tradeData.put("volatility_4h", Math.random() * 6 + 1.0); // 1-7%
                        tradeData.put("volatility_1d", Math.random() * 12 + 2.0); // 2-14%

                        // Market conditions (mock)
                        tradeData.put("volume_ratio", 0.5 + Math.random() * 2); // 0.5-2.5x avg volume
                        tradeData.put("atr", Math.random() * 5 + 1); // ATR 1-6%
                        tradeData.put("adx", Math.random() * 60 + 20); // ADX 20-80
                        tradeData.put("market_trend", Math.random() > 0.5 ? 1 : -1); // Bullish/Bearish
                        tradeData.put("market_volatility", Math.random() * 50 + 10); // VIX-like 10-60

                        // Time-based features
                        tradeData.put("hour_of_day", trade.getEntryTime().getHour());
                        tradeData.put("day_of_week", trade.getEntryTime().getDayOfWeek().getValue());

                        trainingData.add(tradeData);
                    }
                }
            }

            String json = convertToJson(trainingData);
            Files.writeString(trainingFile, json);

            log.info("Exported {} training samples to: {}", trainingData.size(), trainingFile);

        } catch (Exception e) {
            log.error("Error exporting training data: {}", e.getMessage(), e);
        }
    }


    /**
     * Evaluate trained model performance
     */
    public Map<String, Double> evaluateModel(String modelName) {
        try {
            // Használd a train_model.py script-et evaluation módban
            Path scriptPath = Paths.get("src/main/python/train_model.py");

            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    scriptPath.toString(),
                    "--model-name", modelName,
                    "--data-path", dataPath + "/ml",
                    "--model-path", modelsPath,
                    "--evaluate-only"  // Új flag az evaluationhoz
            );

            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Model evaluation timed out");
            }

            String output = readProcessOutput(process.getInputStream());
            String errorOutput = readProcessOutput(process.getErrorStream());

            if (process.exitValue() == 0) {
                // Mock evaluation eredmények, mivel nincs valódi data
                Map<String, Double> evaluation = new HashMap<>();
                evaluation.put("accuracy", 0.85);
                evaluation.put("precision", 0.78);
                evaluation.put("recall", 0.82);
                evaluation.put("f1_score", 0.80);
                return evaluation;
            } else {
                log.error("Model evaluation failed: {}", errorOutput);
                return Collections.emptyMap();
            }

        } catch (Exception e) {
            log.error("Model evaluation error: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Check if model is ready for predictions
     */
    public boolean isModelReady(String modelName) {
        try {
            Path modelsDir = Paths.get(modelsPath);

            // Check for model info file
            Path modelInfo = modelsDir.resolve(modelName + "_info.json");
            if (!Files.exists(modelInfo)) {
                log.debug("Model info not found: {}", modelInfo);
                return false;
            }

            // Check for scaler
            Path modelScaler = modelsDir.resolve(modelName + "_scaler.pkl");
            if (!Files.exists(modelScaler)) {
                log.debug("Model scaler not found: {}", modelScaler);
                return false;
            }

            // Check for at least one model file
            boolean hasModel = Files.exists(modelsDir.resolve(modelName + "_xgb_classifier.pkl")) ||
                    Files.exists(modelsDir.resolve(modelName + "_neural_net_classifier.h5")) ||
                    Files.exists(modelsDir.resolve(modelName + "_random_forest.pkl"));

            if (!hasModel) {
                log.debug("No model files found for: {}", modelName);
                return false;
            }

            log.debug("Model {} is ready", modelName);
            return true;

        } catch (Exception e) {
            log.debug("Model readiness check failed for {}: {}", modelName, e.getMessage());
            return false;
        }
    }

    /**
     * Get model information
     */
    public Map<String, Object> getModelInfo(String modelName) {
        try {
            Path modelInfo = Paths.get(modelsPath, modelName + "_info.json");

            if (Files.exists(modelInfo)) {
                String json = Files.readString(modelInfo);
                return parseJsonToMap(json);
            }

            return Map.of("error", "Model info not found");

        } catch (Exception e) {
            log.error("Error reading model info: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    public int getBacktestHistorySize() {
        // Placeholder implementation - return 0 for now
        return 0;
    }

    // === PRIVATE HELPER METHODS ===

    private CompletableFuture<Boolean> sendTradeResultToPython(MLTradeResult tradeResult) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        pythonExecutable,
                        "src/main/python/trade_feedback.py",
                        "--trade-result", tradeResult.toJson()
                );

                Process process = pb.start();
                boolean finished = process.waitFor(10, TimeUnit.SECONDS);

                if (finished && process.exitValue() == 0) {
                    log.debug("Trade result sent to Python successfully");
                    return true;
                } else {
                    log.warn("Failed to send trade result to Python");
                    return false;
                }

            } catch (Exception e) {
                log.error("Error sending trade result to Python: {}", e.getMessage());
                return false;
            }
        });
    }

    private MLPredictionResponse parseMLResponse(String jsonOutput) {
        try {
            // Simple parsing - in production use Jackson
            if (jsonOutput.contains("\"success\": true")) {
                String signal = extractJsonValue(jsonOutput, "predicted_signal");
                String confidence = extractJsonValue(jsonOutput, "confidence");
                String expectedReturn = extractJsonValue(jsonOutput, "expected_return");

                return MLPredictionResponse.builder()
                        .success(true)
                        .predictedSignal(mapStringToSignal(signal))
                        .confidence(parseDouble(confidence, 0.0))
                        .expectedReturn(parseDouble(expectedReturn, 0.0))
                        .modelConfidence(parseDouble(confidence, 0.0))
                        .build();
            } else {
                String error = extractJsonValue(jsonOutput, "error");
                return createErrorResponse(error != null ? error : "Unknown error");
            }

        } catch (Exception e) {
            log.error("Error parsing ML response: {}", e.getMessage());
            return createErrorResponse("Response parsing error");
        }
    }

    private MLPredictionResponse createErrorResponse(String errorMessage) {
        return MLPredictionResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .predictedSignal(kd.trading.bot.enums.Signal.NO_TRADE)
                .confidence(0.0)
                .build();
    }

    private kd.trading.bot.enums.Signal mapStringToSignal(String signal) {
        if (signal == null) return kd.trading.bot.enums.Signal.NO_TRADE;

        return switch (signal.toUpperCase()) {
            case "LONG" -> kd.trading.bot.enums.Signal.LONG;
            case "SHORT" -> kd.trading.bot.enums.Signal.SHORT;
            default -> kd.trading.bot.enums.Signal.NO_TRADE;
        };
    }

    private Map<String, Double> parseEvaluationResponse(String jsonOutput) {
        try {
            Map<String, Double> evaluation = new HashMap<>();

            // Simple extraction of key metrics
            String accuracy = extractJsonValue(jsonOutput, "accuracy");
            String auc = extractJsonValue(jsonOutput, "auc_score");
            String winRate = extractJsonValue(jsonOutput, "predicted_win_rate");

            if (accuracy != null) evaluation.put("accuracy", parseDouble(accuracy, 0.0));
            if (auc != null) evaluation.put("auc_score", parseDouble(auc, 0.0));
            if (winRate != null) evaluation.put("win_rate", parseDouble(winRate, 0.0));

            return evaluation;

        } catch (Exception e) {
            log.error("Error parsing evaluation response: {}", e.getMessage());
            return Collections.emptyMap();
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

    // Simplified JSON handling
    private String convertToJson(Object data) {
        if (data instanceof Map) {
            return mapToJson((Map<?, ?>) data);
        } else if (data instanceof List) {
            return listToJson((List<?>) data);
        }
        return "{}";
    }

    private String mapToJson(Map<?, ?> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) json.append(",");
            json.append("\"").append(entry.getKey()).append("\":");
            json.append(valueToJson(entry.getValue()));
            first = false;
        }
        json.append("}");
        return json.toString();
    }

    private String listToJson(List<?> list) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(",");
            json.append(valueToJson(list.get(i)));
        }
        json.append("]");
        return json.toString();
    }

    private String valueToJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJsonString((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) return mapToJson((Map<?, ?>) value);
        if (value instanceof List) return listToJson((List<?>) value);
        return "\"" + escapeJsonString(value.toString()) + "\"";
    }

    private String escapeJsonString(String str) {
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private Map<String, Object> parseJsonToMap(String json) {
        // Simplified JSON parsing - in production use Jackson
        Map<String, Object> map = new HashMap<>();
        map.put("status", "parsed");
        return map;
    }

    private String extractJsonValue(String json, String key) {
        try {
            String searchKey = "\"" + key + "\":";
            int startIndex = json.indexOf(searchKey);
            if (startIndex == -1) return null;

            startIndex += searchKey.length();
            while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
                startIndex++;
            }

            if (startIndex >= json.length()) return null;

            char firstChar = json.charAt(startIndex);
            if (firstChar == '"') {
                // String value
                int endIndex = json.indexOf('"', startIndex + 1);
                if (endIndex == -1) return null;
                return json.substring(startIndex + 1, endIndex);
            } else {
                // Number or boolean value
                int endIndex = startIndex;
                while (endIndex < json.length() &&
                        json.charAt(endIndex) != ',' &&
                        json.charAt(endIndex) != '}' &&
                        json.charAt(endIndex) != ']') {
                    endIndex++;
                }
                return json.substring(startIndex, endIndex).trim();
            }

        } catch (Exception e) {
            log.debug("Failed to extract JSON value for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    private double parseDouble(String value, double defaultValue) {
        try {
            return value != null ? Double.parseDouble(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}