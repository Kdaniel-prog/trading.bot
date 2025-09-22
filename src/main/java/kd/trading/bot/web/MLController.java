package kd.trading.bot.web;

import kd.trading.bot.model.ml.MLPredictionRequest;
import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.model.ml.MLTradeResult;
import kd.trading.bot.model.ml.MLTrainingRequest;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.service.backtest.BacktestService;
import kd.trading.bot.service.ml.PythonMLService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
@Slf4j
public class MLController {

    private final PythonMLService pythonMLService;
    private final BacktestService backtestService;

    /**
     * FIXED: Train ML model from backtest results
     * POST /api/ml/train
     */
    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> trainModel(@RequestBody MLTrainingRequest request) {
        try {
            log.info("Starting ML training for symbols: {}", request.getSymbols());

            Map<String, Object> response = new HashMap<>();
            List<BacktestResult> backtestResults = new ArrayList<>();
            int successfulBacktests = 0;

            // 1. Run backtests to generate training data
            if (request.isRunBacktests()) {
                log.info("Running backtests to generate fresh training data...");

                for (String symbol : request.getSymbols()) {
                    try {
                        // FIXED: BacktestService now returns single BacktestResult
                        BacktestResult backtestResult = backtestService.runBacktest(
                                symbol,
                                request.getStartDate(),
                                request.getEndDate()
                        );

                        if (backtestResult != null && backtestResult.isSuccess()) {
                            successfulBacktests++;
                            backtestResults.add(backtestResult);
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
            } else {
                // If not running fresh backtests, try to collect existing results
                log.info("Collecting existing backtest data for training...");

                for (String symbol : request.getSymbols()) {
                    try {
                        BacktestResult result = backtestService.runBacktest(
                                symbol, request.getStartDate(), request.getEndDate());
                        if (result != null && result.isSuccess()) {
                            backtestResults.add(result);
                            successfulBacktests++;
                        }
                    } catch (Exception e) {
                        log.error("Error collecting backtest data for {}: {}", symbol, e.getMessage());
                    }
                }
            }

            if (backtestResults.isEmpty()) {
                response.put("success", false);
                response.put("message", "No backtest results available for training");
                return ResponseEntity.badRequest().body(response);
            }

            // 2. Export training data to Python format
            log.info("Exporting {} backtest results for training...", backtestResults.size());
            pythonMLService.exportTrainingData(backtestResults);

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
                response.put("totalTrainingPoints", backtestResults.stream()
                        .mapToInt(r -> r.getTrades() != null ? r.getTrades().size() : 0)
                        .sum());

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
     * Get model prediction
     * POST /api/ml/predict
     */
    @PostMapping("/predict")
    public ResponseEntity<Map<String, Object>> getModelPrediction(@RequestBody MLPredictionRequest request) {
        try {
            log.info("Getting ML prediction for {}", request.getSymbol());

            CompletableFuture<MLPredictionResponse> predictionFuture = pythonMLService.getPrediction(
                    request.getSymbol(),
                    request.getFourHourKlines(),
                    request.getDailyKlines(),
                    request.getHourlyKlines(),
                    request.getMlData()
            );

            MLPredictionResponse prediction = predictionFuture.get(30, TimeUnit.SECONDS);

            Map<String, Object> response = new HashMap<>();
            response.put("success", prediction.isSuccess());
            response.put("symbol", request.getSymbol());
            response.put("direction", prediction.getDirection());
            response.put("confidence", prediction.getConfidence());
            response.put("signal", prediction.getPredictedSignal());

            if (prediction.getProbabilities() != null) {
                response.put("probabilities", prediction.getProbabilities());
            }

            if (!prediction.isSuccess()) {
                response.put("error", prediction.getErrorMessage());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ML prediction error for {}: {}", request.getSymbol(), e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Prediction error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Check model status
     * GET /api/ml/status/{modelName}
     */
    @GetMapping("/status/{modelName}")
    public ResponseEntity<Map<String, Object>> getModelStatus(@PathVariable String modelName) {
        try {
            boolean isReady = pythonMLService.isModelReady(modelName);
            Map<String, Object> modelInfo = pythonMLService.getModelInfo(modelName);

            Map<String, Object> response = new HashMap<>();
            response.put("modelName", modelName);
            response.put("ready", isReady);
            response.put("info", modelInfo);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error checking model status: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Status check failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * List available training data files
     * GET /api/ml/training-data
     */
    @GetMapping("/training-data")
    public ResponseEntity<Map<String, Object>> listTrainingData() {
        try {
            File trainingDir = new File("src/main/resources/data/training");
            List<Map<String, Object>> files = new ArrayList<>();

            if (trainingDir.exists() && trainingDir.isDirectory()) {
                File[] jsonFiles = trainingDir.listFiles((dir, name) -> name.endsWith(".json"));

                if (jsonFiles != null) {
                    for (File file : jsonFiles) {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("filename", file.getName());
                        fileInfo.put("size", file.length());
                        fileInfo.put("lastModified", file.lastModified());
                        files.add(fileInfo);
                    }
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("trainingDataFiles", files);
            response.put("totalFiles", files.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error listing training data: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to list training data: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Submit trade result for model improvement
     * POST /api/ml/trade-result
     */
    @PostMapping("/trade-result")
    public ResponseEntity<Map<String, Object>> submitTradeResult(@RequestBody MLTradeResult tradeResult) {
        try {
            log.info("Submitting trade result for {}: {}% PnL",
                    tradeResult.getSymbol(), tradeResult.getPnlPercent());

            CompletableFuture<Boolean> submitFuture = pythonMLService.submitTradeResult(tradeResult);
            Boolean success = submitFuture.get(10, TimeUnit.SECONDS);

            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("message", success ? "Trade result submitted successfully" : "Failed to submit trade result");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error submitting trade result: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to submit trade result: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}