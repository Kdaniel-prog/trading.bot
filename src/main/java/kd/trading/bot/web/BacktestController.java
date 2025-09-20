package kd.trading.bot.web;

import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestRuleAnalysis;
import kd.trading.bot.model.backtest.BacktestConfiguration;
import kd.trading.bot.service.backtest.BacktestService;
import kd.trading.bot.service.backtest.FeatherDataLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Slf4j
public class BacktestController {

    private final BacktestService backtestService;
    private final FeatherDataLoader featherDataLoader;

    /**
     * Get available symbols for backtesting
     */
    @GetMapping("/symbols")
    public ResponseEntity<List<String>> getAvailableSymbols() {
        List<String> symbols = featherDataLoader.getAvailableSymbols();
        return ResponseEntity.ok(symbols);
    }

    /**
     * Get available timeframes for a symbol
     */
    @GetMapping("/symbols/{symbol}/timeframes")
    public ResponseEntity<List<String>> getAvailableTimeframes(@PathVariable String symbol) {
        List<String> timeframes = featherDataLoader.getAvailableTimeframes(symbol);
        return ResponseEntity.ok(timeframes);
    }

    /**
     * Get data range for symbol/timeframe
     */
    @GetMapping("/symbols/{symbol}/timeframes/{timeframe}/range")
    public ResponseEntity<Map<String, LocalDateTime>> getDataRange(
            @PathVariable String symbol,
            @PathVariable String timeframe) {
        Map<String, LocalDateTime> range = featherDataLoader.getDataRange(symbol, timeframe);
        return ResponseEntity.ok(range);
    }

    /**
     * Run backtest for single symbol
     */
    @PostMapping("/run")
    public ResponseEntity<BacktestResult> runBacktest(@RequestBody BacktestRequest request) {
        try {
            log.info("Starting backtest for: {}", request);

            BacktestResult result = backtestService.runBacktest(
                    request.getSymbol(),
                    request.getTimeframe(),
                    request.getStartDate(),
                    request.getEndDate()
            );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Backtest failed: {}", e.getMessage(), e);

            BacktestResult errorResult = BacktestResult.builder()
                    .symbol(request.getSymbol())
                    .timeframe(request.getTimeframe())
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();

            return ResponseEntity.badRequest().body(errorResult);
        }
    }

    /**
     * Run backtest for multiple symbols
     */
    @PostMapping("/run/multi")
    public ResponseEntity<List<BacktestResult>> runMultiSymbolBacktest(@RequestBody MultiSymbolBacktestRequest request) {
        try {
            log.info("Starting multi-symbol backtest for {} symbols", request.getSymbols().size());

            List<BacktestResult> results = backtestService.runMultiSymbolBacktest(
                    request.getSymbols(),
                    request.getTimeframe(),
                    request.getStartDate(),
                    request.getEndDate()
            );

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("Multi-symbol backtest failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Analyze trading rules performance from backtest result
     */
    @PostMapping("/analyze/rules")
    public ResponseEntity<Map<Integer, BacktestRuleAnalysis>> analyzeByRules(@RequestBody BacktestResult result) {
        try {
            Map<Integer, BacktestRuleAnalysis> analysis = backtestService.analyzeByTradingRules(result);
            return ResponseEntity.ok(analysis);

        } catch (Exception e) {
            log.error("Rule analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Quick backtest with default parameters
     */
    @GetMapping("/quick/{symbol}")
    public ResponseEntity<BacktestResult> quickBacktest(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "4h") String timeframe,
            @RequestParam(defaultValue = "180") int daysBack) {

        try {
            LocalDateTime endDate = LocalDateTime.now();
            LocalDateTime startDate = endDate.minusDays(daysBack);

            BacktestResult result = backtestService.runBacktest(symbol, timeframe, startDate, endDate);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Quick backtest failed for {}: {}", symbol, e.getMessage());

            BacktestResult errorResult = BacktestResult.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();

            return ResponseEntity.badRequest().body(errorResult);
        }
    }

    /**
     * Get backtest summary statistics
     */
    @PostMapping("/summary")
    public ResponseEntity<BacktestSummary> getBacktestSummary(@RequestBody List<BacktestResult> results) {
        try {
            BacktestSummary summary = calculateSummary(results);
            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            log.error("Summary calculation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    private BacktestSummary calculateSummary(List<BacktestResult> results) {
        List<BacktestResult> successfulResults = results.stream()
                .filter(BacktestResult::isSuccess)
                .toList();

        if (successfulResults.isEmpty()) {
            return BacktestSummary.builder()
                    .totalBacktests(results.size())
                    .successfulBacktests(0)
                    .build();
        }

        double avgReturn = successfulResults.stream()
                .mapToDouble(BacktestResult::getTotalReturnPercent)
                .average()
                .orElse(0.0);

        double avgWinRate = successfulResults.stream()
                .mapToDouble(BacktestResult::getWinRate)
                .average()
                .orElse(0.0);

        double avgSharpe = successfulResults.stream()
                .mapToDouble(BacktestResult::getSharpeRatio)
                .average()
                .orElse(0.0);

        double avgMaxDrawdown = successfulResults.stream()
                .mapToDouble(BacktestResult::getMaxDrawdownPercent)
                .average()
                .orElse(0.0);

        int totalTrades = successfulResults.stream()
                .mapToInt(BacktestResult::getTotalTrades)
                .sum();

        BacktestResult bestPerformer = successfulResults.stream()
                .max((r1, r2) -> Double.compare(r1.getTotalReturnPercent(), r2.getTotalReturnPercent()))
                .orElse(null);

        BacktestResult worstPerformer = successfulResults.stream()
                .min((r1, r2) -> Double.compare(r1.getTotalReturnPercent(), r2.getTotalReturnPercent()))
                .orElse(null);

        return BacktestSummary.builder()
                .totalBacktests(results.size())
                .successfulBacktests(successfulResults.size())
                .averageReturnPercent(avgReturn)
                .averageWinRate(avgWinRate)
                .averageSharpeRatio(avgSharpe)
                .averageMaxDrawdownPercent(avgMaxDrawdown)
                .totalTrades(totalTrades)
                .bestPerformer(bestPerformer)
                .worstPerformer(worstPerformer)
                .build();
    }

    // Request/Response DTOs

    @lombok.Data
    public static class BacktestRequest {
        private String symbol;
        private String timeframe;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startDate;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endDate;
        private BacktestConfiguration configuration;
    }

    @lombok.Data
    public static class MultiSymbolBacktestRequest {
        private List<String> symbols;
        private String timeframe;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startDate;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endDate;
        private BacktestConfiguration configuration;
    }

    @lombok.Data
    @lombok.Builder
    public static class BacktestSummary {
        private int totalBacktests;
        private int successfulBacktests;
        private double averageReturnPercent;
        private double averageWinRate;
        private double averageSharpeRatio;
        private double averageMaxDrawdownPercent;
        private int totalTrades;
        private BacktestResult bestPerformer;
        private BacktestResult worstPerformer;

        public double getSuccessRate() {
            return totalBacktests > 0 ? (double) successfulBacktests / totalBacktests * 100 : 0.0;
        }
    }
}