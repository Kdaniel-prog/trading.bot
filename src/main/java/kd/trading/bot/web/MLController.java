package kd.trading.bot.web;

import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.model.ml.MLTrainingRequest;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.service.backtest.BacktestService;
import kd.trading.bot.service.ml.PythonMLService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
@Slf4j
public class MLController {

    private final PythonMLService pythonMLService;
    private final BacktestService backtestService;

    /**
     * Train ML model from backtest results
     * POST /api/ml/train
     */
    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> trainModel(@RequestBody MLTrainingRequest request) {
        try {
            log.info("Starting ML training for symbols: {}, timeframe: {}",
                    request.getSymbols(), request.getTimeframe());

            Map<String, Object> response = new HashMap<>();
            int successfulBacktests = 0;

            // 1. Run backtests to generate training data
            if (request.isRunBacktests()) {
                log.info("Running backtests first to generate fresh training data...");

                for (String symbol : request.getSymbols()) {
                    try {
                        // Use LocalDateTime directly
                        BacktestResult backtestResult = backtestService.runBacktest(
                                symbol,
                                request.getTimeframe(),
                                request.getStartDate(),
                                request.getEndDate()
                        );

                        if (backtestResult != null && backtestResult.isSuccess()) {
                            successfulBacktests++;
                            log.info("Backtest completed for {}: {} trades, {}% profit",
                                    symbol, backtestResult.getTotalTrades(),
                                    backtestResult.getTotalReturnPercent());
                        } else {
                            log.warn("Backtest failed for {}: {}", symbol,
                                    backtestResult != null ? backtestResult.getErrorMessage() : "null result");
                        }

                    } catch (Exception e) {
                        log.error("Backtest error for {}: {}", symbol, e.getMessage());
                    }
                }

                response.put("backtestsRun", request.getSymbols().size());
                response.put("successfulBacktests", successfulBacktests);

                if (successfulBacktests == 0) {
                    response.put("success", false);
                    response.put("message", "No successful backtests to train on");
                    return ResponseEntity.badRequest().body(response);
                }
            }

            // 2. Export training data from backtests
            log.info("Exporting training data from backtest results...");
            List<BacktestResult> backtestResults = backtestService.getAllBacktestResults();

            if (backtestResults.isEmpty()) {
                response.put("success", false);
                response.put("message", "No backtest results available for training");
                return ResponseEntity.badRequest().body(response);
            }

            // Export training data to Python
            pythonMLService.exportTrainingData(backtestResults);
            log.info("Exported {} backtest results for training", backtestResults.size());

            // 3. Train the ML model
            log.info("Training ML model: {}", request.getModelName());
            boolean trainSuccess = pythonMLService.trainModel(request.getModelName());

            if (trainSuccess) {
                // 4. Evaluate trained model
                log.info("Evaluating trained model...");
                Map<String, Double> evaluation = pythonMLService.evaluateModel(request.getModelName());

                response.put("success", true);
                response.put("message", "Model training completed successfully");
                response.put("modelName", request.getModelName());
                response.put("evaluation", evaluation);
                response.put("trainingSymbols", request.getSymbols());
                response.put("timeframe", request.getTimeframe());
                response.put("trainingDataSize", backtestResults.size());

                log.info("ML training completed successfully for model: {}", request.getModelName());
                return ResponseEntity.ok(response);

            } else {
                response.put("success", false);
                response.put("message", "Model training failed");
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("ML training error: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Training error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Get model prediction for a specific analysis
     * POST /api/ml/predict
     */
    @PostMapping("/predict")
    public ResponseEntity<MLPredictionResponse> getPrediction(@RequestBody Map<String, Object> predictionRequest) {
        try {
            String symbol = (String) predictionRequest.get("symbol");
            log.debug("Getting ML prediction for symbol: {}", symbol);

            // This would be called from SwingAlgoService normally, but for testing:
            CompletableFuture<MLPredictionResponse> predictionFuture = pythonMLService.getPrediction(
                    symbol,
                    (List<List<Object>>) predictionRequest.get("fourHourKlines"),
                    (List<List<Object>>) predictionRequest.get("dailyKlines"),
                    (List<List<Object>>) predictionRequest.get("hourlyKlines"),
                    (Map<String, Object>) predictionRequest.get("mlData")
            );

            MLPredictionResponse prediction = predictionFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
            return ResponseEntity.ok(prediction);

        } catch (Exception e) {
            log.error("Prediction error: {}", e.getMessage(), e);
            MLPredictionResponse errorResponse = MLPredictionResponse.builder()
                    .success(false)
                    .errorMessage("Prediction error: " + e.getMessage())
                    .build();
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    /**
     * Evaluate existing model performance
     * POST /api/ml/evaluate/{modelName}
     */
    @PostMapping("/evaluate/{modelName}")
    public ResponseEntity<Map<String, Object>> evaluateModel(@PathVariable String modelName) {
        try {
            log.info("Evaluating model: {}", modelName);

            Map<String, Double> evaluation = pythonMLService.evaluateModel(modelName);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("modelName", modelName);
            response.put("evaluation", evaluation);
            response.put("ready", pythonMLService.isModelReady(modelName));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Model evaluation error: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Evaluation error: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    /**
     * Get model information and statistics
     * GET /api/ml/model/{modelName}/info
     */
    @GetMapping("/model/{modelName}/info")
    public ResponseEntity<Map<String, Object>> getModelInfo(@PathVariable String modelName) {
        try {
            Map<String, Object> modelInfo = pythonMLService.getModelInfo(modelName);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("modelName", modelName);
            response.put("ready", pythonMLService.isModelReady(modelName));
            response.put("info", modelInfo);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Model info error: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Model info error: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    /**
     * List all available models
     * GET /api/ml/models
     */
    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> listModels() {
        try {
            Map<String, Object> response = new HashMap<>();

            // Check common model names
            String[] commonModels = {"swing_trader", "swing_trader_v2", "btc_trader", "eth_trader"};
            Map<String, Boolean> modelStatus = new HashMap<>();

            for (String modelName : commonModels) {
                modelStatus.put(modelName, pythonMLService.isModelReady(modelName));
            }

            response.put("success", true);
            response.put("models", modelStatus);
            response.put("message", "Model status retrieved");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("List models error: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "List models error: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    /**
     * Health check for ML service
     * GET /api/ml/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Check if Python ML service is accessible
            boolean pythonReady = pythonMLService.isModelReady("test");

            response.put("success", true);
            response.put("pythonMLService", "available");
            response.put("backtestHistorySize", backtestService.getBacktestHistorySize());
            response.put("message", "ML service is healthy");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("pythonMLService", "error");
            response.put("message", "ML service error: " + e.getMessage());

            return ResponseEntity.status(503).body(response);
        }
    }

    /**
     * Get backtest history for inspection
     * GET /api/ml/backtest-history
     */
    @GetMapping("/backtest-history")
    public ResponseEntity<Map<String, Object>> getBacktestHistory(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String timeframe) {

        try {
            List<BacktestResult> results = backtestService.getBacktestResults(symbol, timeframe);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("results", results);
            response.put("totalResults", results.size());

            if (symbol != null) response.put("filteredBySymbol", symbol);
            if (timeframe != null) response.put("filteredByTimeframe", timeframe);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting backtest history: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error retrieving backtest history: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Clear backtest history (for testing)
     * DELETE /api/ml/backtest-history
     */
    @DeleteMapping("/backtest-history")
    public ResponseEntity<Map<String, Object>> clearBacktestHistory() {
        try {
            backtestService.clearBacktestHistory();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Backtest history cleared");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error clearing backtest history: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error clearing backtest history: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}