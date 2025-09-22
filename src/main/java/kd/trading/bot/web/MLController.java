package kd.trading.bot.web;

import kd.trading.bot.model.ml.MLTrainingRequest;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.service.backtest.BacktestService;
import kd.trading.bot.service.backtest.ImprovedTrainingDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
@Slf4j
public class MLController {

    private final BacktestService backtestService;
    private final ImprovedTrainingDataService improvedTrainingDataService;

    /** Train ML model from backtest results (data preparation only for now). */
    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> trainModel(@RequestBody MLTrainingRequest request) {
        try {
            log.info("Starting ML training for symbols: {}", request.getSymbols());

            Map<String, Object> response = new HashMap<>();
            List<BacktestResult> backtestResults = new ArrayList<>();
            int successfulBacktests = 0;

            // Basic validation
            if (request.getSymbols() == null || request.getSymbols().isEmpty()) {
                response.put("success", false);
                response.put("message", "No symbols provided for training");
                return ResponseEntity.badRequest().body(response);
            }

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
                // If not running fresh backtests, attempt to gather existing results via service
                log.info("Collecting existing backtest data for training...");

                for (String symbol : request.getSymbols()) {
                    try {
                        BacktestResult result = backtestService.runBacktest(
                                symbol, request.getStartDate(), request.getEndDate());
                        if (result != null && result.isSuccess()) {
                            backtestResults.add(result);
                            successfulBacktests++;
                        } else {
                            log.warn("No existing backtest data for {} in requested range", symbol);
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

            // 2. Generate/export training data
            log.info("Exporting {} backtest results for training...", backtestResults.size());
            improvedTrainingDataService.generateBalancedTrainingData(backtestResults);
            // 3. Model training/evaluation step can be invoked here when enabled
            // Since training step is currently disabled, return successful data generation info
            response.put("success", true);
            response.put("message", "Training data prepared successfully");
            response.put("successfulBacktests", successfulBacktests);
            response.put("trainingSymbols", request.getSymbols());
            response.put("trainingDataSize", backtestResults.size());
            response.put("totalTrainingPoints", backtestResults.stream()
                    .mapToInt(r -> r.getTrades() != null ? r.getTrades().size() : 0)
                    .sum());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("ML training error: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Training error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

}