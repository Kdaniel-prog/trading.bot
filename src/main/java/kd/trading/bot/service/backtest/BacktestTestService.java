package kd.trading.bot.service.backtest;

import kd.trading.bot.config.backtest.BacktestConfigProperties;
import kd.trading.bot.model.*;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestRuleAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestTestService {

    private final BacktestService backtestService;
    private final FeatherDataLoader featherDataLoader;
    private final BacktestConfigProperties config;

    /**
     * Run a comprehensive backtest suite for development/testing
     */
    public void runTestSuite() {
        log.info("========================================");
        log.info("STARTING BACKTEST TEST SUITE");
        log.info("========================================");

        try {
            // 1. Data availability check
            checkDataAvailability();

            // 2. Single symbol tests
            runSingleSymbolTests();

            // 3. Multi-symbol comparison
            runMultiSymbolComparison();

            // 4. Strategy analysis
            runStrategyAnalysis();

            log.info("========================================");
            log.info("BACKTEST TEST SUITE COMPLETED");
            log.info("========================================");

        } catch (Exception e) {
            log.error("Test suite failed: {}", e.getMessage(), e);
        }
    }

    private void checkDataAvailability() {
        log.info("--- DATA AVAILABILITY CHECK ---");

        List<String> symbols = featherDataLoader.getAvailableSymbols();
        log.info("Total symbols available: {}", symbols.size());

        if (symbols.isEmpty()) {
            log.error("No symbols found! Check feather directory: {}", config.getFeatherDirectory());
            return;
        }

        // Check top 10 symbols
        symbols.stream().limit(10).forEach(symbol -> {
            List<String> timeframes = featherDataLoader.getAvailableTimeframes(symbol);
            log.info("{}: {} timeframes available", symbol, timeframes);

            if (timeframes.contains("4h")) {
                Map<String, LocalDateTime> range = featherDataLoader.getDataRange(symbol, "4h");
                if (range.containsKey("start") && range.containsKey("end")) {
                    log.info("  -> 4h data: {} to {}", range.get("start"), range.get("end"));
                }
            }
        });
    }

    private void runSingleSymbolTests() {
        log.info("\n--- SINGLE SYMBOL TESTS ---");

        // Test configurations
        String[] testSymbols = {"BTC_USDT", "ETH_USDT", "BNB_USDT"};
        String[] testTimeframes = {"4h", "1h", "1d"};
        int[] testPeriodsDays = {90, 180, 365}; // 3, 6, 12 months

        List<String> availableSymbols = featherDataLoader.getAvailableSymbols();

        for (String symbol : testSymbols) {
            if (!availableSymbols.contains(symbol)) {
                log.warn("Symbol {} not available, skipping", symbol);
                continue;
            }

            List<String> symbolTimeframes = featherDataLoader.getAvailableTimeframes(symbol);

            for (String timeframe : testTimeframes) {
                if (!symbolTimeframes.contains(timeframe)) {
                    continue;
                }

                for (int days : testPeriodsDays) {
                    LocalDateTime endDate = LocalDateTime.now();
                    LocalDateTime startDate = endDate.minusDays(days);

                    try {
                        log.info("Testing {}-{} for {} days", symbol, timeframe, days);

                        BacktestResult result = backtestService.runBacktest(
                                symbol, timeframe, startDate, endDate);

                        if (result.isSuccess()) {
                            log.info("SUCCESS: {} trades, {:.2f}% return, {:.1f}% win rate",
                                    result.getTotalTrades(),
                                    result.getTotalReturnPercent(),
                                    result.getWinRate());
                        } else {
                            log.warn("FAILED: {}", result.getErrorMessage());
                        }

                    } catch (Exception e) {
                        log.error("Test failed for {}-{}: {}", symbol, timeframe, e.getMessage());
                    }
                }
            }
        }
    }

    private void runMultiSymbolComparison() {
        log.info("\n--- MULTI SYMBOL COMPARISON ---");

        // Get top 10 most common symbols
        List<String> availableSymbols = featherDataLoader.getAvailableSymbols();
        List<String> testSymbols = availableSymbols.stream()
                .filter(s -> s.contains("USDT"))
                .filter(s -> featherDataLoader.isDataAvailable(s, "4h"))
                .limit(10)
                .toList();

        if (testSymbols.size() < 3) {
            log.warn("Not enough symbols for comparison");
            return;
        }

        LocalDateTime endDate = LocalDateTime.now();
        LocalDateTime startDate = endDate.minusDays(180); // 6 months

        try {
            log.info("Comparing {} symbols over 6 months", testSymbols.size());

            List<BacktestResult> results = backtestService.runMultiSymbolBacktest(
                    testSymbols, "4h", startDate, endDate);

            List<BacktestResult> successfulResults = results.stream()
                    .filter(BacktestResult::isSuccess)
                    .toList();

            log.info("Successful backtests: {}/{}", successfulResults.size(), results.size());

            if (!successfulResults.isEmpty()) {
                // Top performers
                log.info("TOP 3 PERFORMERS BY RETURN:");
                successfulResults.stream()
                        .sorted((r1, r2) -> Double.compare(r2.getTotalReturnPercent(), r1.getTotalReturnPercent()))
                        .limit(3)
                        .forEach(result -> {
                            log.info("  {}: {:.2f}% return, {:.1f}% win rate, {} trades",
                                    result.getSymbol(),
                                    result.getTotalReturnPercent(),
                                    result.getWinRate(),
                                    result.getTotalTrades());
                        });

                // Statistics
                double avgReturn = successfulResults.stream()
                        .mapToDouble(BacktestResult::getTotalReturnPercent)
                        .average().orElse(0.0);

                double avgWinRate = successfulResults.stream()
                        .mapToDouble(BacktestResult::getWinRate)
                        .average().orElse(0.0);

                log.info("AVERAGES: {:.2f}% return, {:.1f}% win rate", avgReturn, avgWinRate);
            }

        } catch (Exception e) {
            log.error("Multi-symbol comparison failed: {}", e.getMessage());
        }
    }

    private void runStrategyAnalysis() {
        log.info("\n--- STRATEGY ANALYSIS ---");

        try {
            // Run detailed analysis on BTC or first available symbol
            List<String> availableSymbols = featherDataLoader.getAvailableSymbols();
            String analysisSymbol = availableSymbols.contains("BTC_USDT") ?
                    "BTC_USDT" : availableSymbols.get(0);

            if (!featherDataLoader.isDataAvailable(analysisSymbol, "4h")) {
                log.warn("Cannot run strategy analysis - no 4h data for {}", analysisSymbol);
                return;
            }

            LocalDateTime endDate = LocalDateTime.now();
            LocalDateTime startDate = endDate.minusDays(365); // 1 year

            log.info("Running detailed analysis for {} over 1 year", analysisSymbol);

            BacktestResult result = backtestService.runBacktest(
                    analysisSymbol, "4h", startDate, endDate);

            if (!result.isSuccess()) {
                log.error("Strategy analysis failed: {}", result.getErrorMessage());
                return;
            }

            // Overall performance
            log.info("STRATEGY PERFORMANCE:");
            log.info("  Total Return: {}%", result.getTotalReturnPercent());
            log.info("  Max Drawdown: {}%", result.getMaxDrawdownPercent());
            log.info("  Sharpe Ratio: {}", result.getSharpeRatio());
            log.info("  Total Trades: {}", result.getTotalTrades());
            log.info("  Win Rate: {}%", result.getWinRate());

            // Rule analysis
            if (config.isRuleAnalysisEnabled() && !result.getTrades().isEmpty()) {
                Map<Integer, BacktestRuleAnalysis> ruleAnalysis =
                        backtestService.analyzeByTradingRules(result);

                log.info("TRADING RULES ANALYSIS:");
                ruleAnalysis.entrySet().stream()
                        .sorted((e1, e2) -> Integer.compare(e2.getValue().getTotalTrades(),
                                e1.getValue().getTotalTrades()))
                        .forEach(entry -> {
                            BacktestRuleAnalysis analysis = entry.getValue();
                            log.info("  Rule {}: {} trades | {:.1f}% win rate | {:.2f}% avg PnL",
                                    analysis.getTradingRule(),
                                    analysis.getTotalTrades(),
                                    analysis.getWinRate(),
                                    analysis.getAveragePnlPercent());
                        });
            }

        } catch (Exception e) {
            log.error("Strategy analysis failed: {}", e.getMessage());
        }
    }

    /**
     * Quick test method for specific symbol
     */
    public BacktestResult quickTest(String symbol) {
        return quickTest(symbol, "4h", 90);
    }

    /**
     * Quick test with parameters
     */
    public BacktestResult quickTest(String symbol, String timeframe, int daysBack) {
        log.info("Quick test: {}-{} for {} days", symbol, timeframe, daysBack);

        try {
            LocalDateTime endDate = LocalDateTime.now();
            LocalDateTime startDate = endDate.minusDays(daysBack);

            BacktestResult result = backtestService.runBacktest(symbol, timeframe, startDate, endDate);

            if (result.isSuccess()) {
                log.info("Quick test result: {:.2f}% return, {:.1f}% win rate, {} trades",
                        result.getTotalReturnPercent(), result.getWinRate(), result.getTotalTrades());
            } else {
                log.error("Quick test failed: {}", result.getErrorMessage());
            }

            return result;

        } catch (Exception e) {
            log.error("Quick test error: {}", e.getMessage());
            return BacktestResult.builder()
                    .symbol(symbol)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}