package kd.trading.bot.runner;

import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestRuleAnalysis;
import kd.trading.bot.service.backtest.BacktestService;
import kd.trading.bot.service.backtest.FeatherDataLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "backtest.run.on.startup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class SimpleBacktestRunner implements CommandLineRunner {

    private final BacktestService backtestService;
    private final FeatherDataLoader featherDataLoader;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== STARTING BACKTEST RUNNER ===");

        // 1. Check available data
        checkAvailableData();

        // 2. Run single symbol test
        runSingleSymbolTest();

        // 3. Run multi-symbol test
        runMultiSymbolTest();

        log.info("=== BACKTEST RUNNER COMPLETED ===");
    }

    private void checkAvailableData() {
        log.info("Checking available data...");

        List<String> symbols = featherDataLoader.getAvailableSymbols();
        log.info("Available symbols ({}): {}", symbols.size(),
                symbols.subList(0, Math.min(10, symbols.size())));

        if (symbols.isEmpty()) {
            log.error("No symbols found! Check your feather data directory.");
            return;
        }

        // Check timeframes for first symbol
        String testSymbol = symbols.get(0);
        List<String> timeframes = featherDataLoader.getAvailableTimeframes(testSymbol);
        log.info("Available timeframes for {}: {}", testSymbol, timeframes);

        // Check data range
        if (!timeframes.isEmpty()) {
            String testTimeframe = timeframes.contains("4h") ? "4h" : timeframes.get(0);
            Map<String, LocalDateTime> range = featherDataLoader.getDataRange(testSymbol, testTimeframe);
            log.info("Data range for {}-{}: {} to {}", testSymbol, testTimeframe,
                    range.get("start"), range.get("end"));
        }
    }

    private void runSingleSymbolTest() {
        log.info("\n=== SINGLE SYMBOL BACKTEST ===");

        try {
            // Test parameters
            String symbol = "BTC_USDT";
            String timeframe = "4h";
            LocalDateTime endDate = LocalDateTime.now();
            LocalDateTime startDate = endDate.minusDays(180); // 6 months back

            // Check if data exists
            if (!featherDataLoader.isDataAvailable(symbol, timeframe)) {
                log.warn("Data not available for {}-{}", symbol, timeframe);
                return;
            }

            log.info("Running backtest for {} {} from {} to {}",
                    symbol, timeframe, startDate, endDate);

            // Run backtest
            BacktestResult result = backtestService.runBacktest(symbol, timeframe, startDate, endDate);

            // Print results
            printBacktestResult(result);

            // Analyze by trading rules
            if (result.isSuccess() && !result.getTrades().isEmpty()) {
                Map<Integer, BacktestRuleAnalysis> ruleAnalysis =
                        backtestService.analyzeByTradingRules(result);
                printRuleAnalysis(ruleAnalysis);
            }

        } catch (Exception e) {
            log.error("Single symbol backtest failed: {}", e.getMessage(), e);
        }
    }

    private void runMultiSymbolTest() {
        log.info("\n=== MULTI SYMBOL BACKTEST ===");

        try {
            // Test with top symbols
            List<String> testSymbols = Arrays.asList(
                    "BTC_USDT", "ETH_USDT", "BNB_USDT", "ADA_USDT", "DOT_USDT"
            );

            String timeframe = "4h";
            LocalDateTime endDate = LocalDateTime.now();
            LocalDateTime startDate = endDate.minusDays(90); // 3 months

            // Filter available symbols
            List<String> availableSymbols = featherDataLoader.getAvailableSymbols();
            List<String> validSymbols = testSymbols.stream()
                    .filter(availableSymbols::contains)
                    .filter(s -> featherDataLoader.isDataAvailable(s, timeframe))
                    .toList();

            if (validSymbols.isEmpty()) {
                log.warn("No valid symbols found for multi-symbol test");
                return;
            }

            log.info("Running multi-symbol backtest for: {}", validSymbols);

            // Run backtest
            List<BacktestResult> results = backtestService.runMultiSymbolBacktest(
                    validSymbols, timeframe, startDate, endDate);

            // Print summary
            printMultiSymbolSummary(results);

        } catch (Exception e) {
            log.error("Multi-symbol backtest failed: {}", e.getMessage(), e);
        }
    }

    private void printBacktestResult(BacktestResult result) {
        if (!result.isSuccess()) {
            log.error("Backtest failed for {}: {}", result.getSymbol(), result.getErrorMessage());
            return;
        }

        log.info("\n--- BACKTEST RESULTS for {} ---", result.getSymbol());
        log.info("Period: {} to {}", result.getStartDate(), result.getEndDate());
        log.info("Initial Balance: ${:,.2f}", result.getInitialBalance());
        log.info("Final Balance: ${:,.2f}", result.getFinalBalance());
        log.info("Total Return: {:.2f}%", result.getTotalReturnPercent());
        log.info("Max Drawdown: {:.2f}%", result.getMaxDrawdownPercent());
        log.info("Total Trades: {}", result.getTotalTrades());
        log.info("Win Rate: {:.1f}%", result.getWinRate());
        log.info("Average Win: {:.2f}%", result.getAverageWinPercent());
        log.info("Average Loss: {:.2f}%", result.getAverageLossPercent());
        log.info("Profit Factor: {:.2f}", result.getProfitFactor());
        log.info("Sharpe Ratio: {:.2f}", result.getSharpeRatio());
        log.info("Avg Holding Time: {:.1f} hours", result.getAverageHoldingTimeHours());

        // Show recent trades
        if (!result.getTrades().isEmpty()) {
            log.info("\n--- LAST 5 TRADES ---");
            result.getTrades().stream()
                    .skip(Math.max(0, result.getTrades().size() - 5))
                    .forEach(trade -> {
                        log.info("{} {} @ {:.4f} -> {:.4f} | PnL: {:.2f}% | Rule: {} | Hours: {}",
                                trade.getSide(), trade.getSymbol(),
                                trade.getEntryPrice(), trade.getExitPrice(),
                                trade.getPnlPercent(), trade.getTradingRule(),
                                trade.getHoldingTimeHours());
                    });
        }
    }

    private void printRuleAnalysis(Map<Integer, BacktestRuleAnalysis> ruleAnalysis) {
        log.info("\n--- TRADING RULES ANALYSIS ---");

        ruleAnalysis.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().getTotalTrades(), e1.getValue().getTotalTrades()))
                .forEach(entry -> {
                    BacktestRuleAnalysis analysis = entry.getValue();
                    log.info("Rule {}: {} | Trades: {} | Win Rate: {:.1f}% | Avg PnL: {:.2f}%",
                            analysis.getTradingRule(),
                            analysis.getRuleDescription(),
                            analysis.getTotalTrades(),
                            analysis.getWinRate(),
                            analysis.getAveragePnlPercent());
                });
    }

    private void printMultiSymbolSummary(List<BacktestResult> results) {
        List<BacktestResult> successful = results.stream()
                .filter(BacktestResult::isSuccess)
                .toList();

        if (successful.isEmpty()) {
            log.warn("No successful backtests in multi-symbol test");
            return;
        }

        log.info("\n--- MULTI SYMBOL SUMMARY ---");
        log.info("Successful: {}/{}", successful.size(), results.size());

        double avgReturn = successful.stream()
                .mapToDouble(BacktestResult::getTotalReturnPercent)
                .average().orElse(0.0);

        double avgWinRate = successful.stream()
                .mapToDouble(BacktestResult::getWinRate)
                .average().orElse(0.0);

        int totalTrades = successful.stream()
                .mapToInt(BacktestResult::getTotalTrades)
                .sum();

        log.info("Average Return: {:.2f}%", avgReturn);
        log.info("Average Win Rate: {:.1f}%", avgWinRate);
        log.info("Total Trades: {}", totalTrades);

        // Top performers
        log.info("\n--- TOP PERFORMERS ---");
        successful.stream()
                .sorted((r1, r2) -> Double.compare(r2.getTotalReturnPercent(), r1.getTotalReturnPercent()))
                .limit(3)
                .forEach(result -> {
                    log.info("{}: {:.2f}% return, {:.1f}% win rate, {} trades",
                            result.getSymbol(), result.getTotalReturnPercent(),
                            result.getWinRate(), result.getTotalTrades());
                });
    }
}