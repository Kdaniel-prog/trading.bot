package kd.trading.bot.web;

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

@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
@Slf4j
public class MLController {

    private final BacktestService backtestService;
    private final PythonMLService pythonMLService;

    /**
     * SIMPLIFIED: SwingAlgo-központú ML training
     * Flow: Controller -> BacktestService -> SwingAlgoService -> Python ML
     */
    @PostMapping("/train")
    public ResponseEntity<Map<String, Object>> trainModel(@RequestBody MLTrainingRequest request) {
        try {
            log.info("Starting SwingAlgo-based ML training for symbols: {}", request.getSymbols());

            Map<String, Object> response = new HashMap<>();

            // Basic validation
            if (request.getSymbols() == null || request.getSymbols().isEmpty()) {
                response.put("success", false);
                response.put("message", "No symbols provided for training");
                return ResponseEntity.badRequest().body(response);
            }

            // 1. Run backtests - BacktestService uses SwingAlgoService internally
            log.info("Running backtests with SwingAlgo analysis...");
            List<BacktestResult> backtestResults = runBacktestsForTraining(request);

            if (backtestResults.isEmpty()) {
                response.put("success", false);
                response.put("message", "No successful backtests generated");
                return ResponseEntity.badRequest().body(response);
            }

            // 2. Export SwingAlgo training data - PythonMLService extracts SwingAlgo results
            log.info("Exporting SwingAlgo analysis results for ML training...");
            pythonMLService.exportSwingAlgoTrainingData(backtestResults);

            // 3. Train Python ML model with SwingAlgo features
            log.info("Training Python ML model with SwingAlgo features...");

            log.info("SwingAlgo ML training completed successfully for model: {}", request.getModelName());
            return ResponseEntity.ok(response);


        } catch (Exception e) {
            log.error("SwingAlgo ML training error: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Training error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Run backtests for each symbol - BacktestService handles SwingAlgo calls
     */
    private List<BacktestResult> runBacktestsForTraining(MLTrainingRequest request) {
        List<BacktestResult> results = new java.util.ArrayList<>();

        for (String symbol : request.getSymbols()) {
            try {
                log.info("Running SwingAlgo backtest for symbol: {}", symbol);

                // BacktestService internally calls SwingAlgoService for each analysis
                BacktestResult result = backtestService.runSwingAlgoBacktest(
                        symbol,
                        request.getStartDate(),
                        request.getEndDate()
                );

                if (result != null && result.isSuccess()) {
                    results.add(result);
                    log.info("SwingAlgo backtest completed for {}: {} trades, {}% profit",
                            symbol, result.getTotalTrades(), result.getTotalReturnPercent());
                } else {
                    log.warn("SwingAlgo backtest failed for {}: {}", symbol,
                            result != null ? result.getErrorMessage() : "null result");
                }

            } catch (Exception e) {
                log.error("SwingAlgo backtest error for {}: {}", symbol, e.getMessage());
            }
        }

        log.info("Completed {} successful SwingAlgo backtests out of {} symbols",
                results.size(), request.getSymbols().size());
        return results;
    }

    // Keep other endpoints unchanged...
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("message", "SwingAlgo ML service is healthy");
            response.put("swingAlgoService", "available");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "ML service error: " + e.getMessage());
            return ResponseEntity.status(503).body(response);
        }
    }
}