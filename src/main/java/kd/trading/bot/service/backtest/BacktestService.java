package kd.trading.bot.service.backtest;

import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestRuleAnalysis;
import kd.trading.bot.model.backtest.BacktestTrade;
import kd.trading.bot.service.ratingProcess.algorithm.SwingAlgoService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BacktestService {

    final SwingAlgoService swingAlgoService;
    final FeatherDataLoader featherDataLoader;

    // Configuration from YAML - matched to your existing config structure
    @Value("${backtest.initial.balance:10000.0}")
    private double initialBalance;

    @Value("${backtest.fee.rate:0.0004}")
    private double feeRate;

    @Value("${backtest.position.size.percent:0.1}")
    private double positionSizePercent;

    // Risk management from your trading config
    @Value("${backtest.risk.stop.loss.percent:#{T(Math).abs(${trading.stopLimit:3.0})}}")
    private double stopLossPercent;

    @Value("${backtest.risk.take.profit.percent:${trading.winLimit:6.0}}")
    private double takeProfitPercent;

    @Value("${backtest.risk.max.holding.hours:168}")
    private int maxHoldingHours;

    public BacktestResult runBacktest(String symbol, String timeframe, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Starting backtest for {} {} from {} to {}", symbol, timeframe, startDate, endDate);

        try {
            // 1. Load feather data
            List<HistoricalCandle> candleData = featherDataLoader.loadHistoricalData(symbol, timeframe, startDate, endDate);

            if (candleData.size() < 300) {
                log.warn("Insufficient data for {}: {} candles", symbol, candleData.size());
                return BacktestResult.builder()
                        .symbol(symbol)
                        .timeframe(timeframe)
                        .success(false)
                        .errorMessage("Insufficient data")
                        .build();
            }

            // 2. Create resampled data for multi-timeframe analysis
            Map<String, List<HistoricalCandle>> timeframeData = prepareTimeframeData(candleData, timeframe);

            // 3. Run backtest simulation
            BacktestResult result = simulateTrading(symbol, timeframe, timeframeData, startDate, endDate);

            log.info("Backtest completed for {}: Final PnL: {}%, Trades: {}",
                    symbol, result.getTotalReturnPercent(), result.getTotalTrades());

            return result;

        } catch (Exception e) {
            log.error("Backtest failed for {}: {}", symbol, e.getMessage(), e);
            return BacktestResult.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private Map<String, List<HistoricalCandle>> prepareTimeframeData(List<HistoricalCandle> baseData, String baseTimeframe) {
        Map<String, List<HistoricalCandle>> timeframeData = new HashMap<>();

        timeframeData.put("1h", baseData); // Base data

        // Resample to higher timeframes if needed
        if ("1h".equals(baseTimeframe)) {
            timeframeData.put("4h", swingAlgoService.resample(baseData, 4));
            timeframeData.put("1d", swingAlgoService.resample(baseData, 24));
        } else if ("4h".equals(baseTimeframe)) {
            timeframeData.put("4h", baseData);
            timeframeData.put("1d", swingAlgoService.resample(baseData, 6)); // 4h -> 1d
            // For 1h, we'd need to interpolate or use different approach
            timeframeData.put("1h", baseData); // Simplified - use 4h as 1h
        } else if ("1d".equals(baseTimeframe)) {
            timeframeData.put("1d", baseData);
            timeframeData.put("4h", baseData); // Simplified approach
            timeframeData.put("1h", baseData);
        }

        return timeframeData;
    }

    private BacktestResult simulateTrading(String symbol, String timeframe,
                                           Map<String, List<HistoricalCandle>> timeframeData,
                                           LocalDateTime startDate, LocalDateTime endDate) {

        List<HistoricalCandle> mainData = timeframeData.get(timeframe);
        List<BacktestTrade> trades = new ArrayList<>();

        double currentBalance = initialBalance;
        Position currentPosition = null;

        // Performance tracking
        List<Double> portfolioValues = new ArrayList<>();
        List<Double> returns = new ArrayList<>();
        double maxDrawdown = 0.0;
        double peakValue = initialBalance;

        // Start analysis from index 200 to ensure enough historical data
        int startIndex = Math.max(200, 0);

        for (int i = startIndex; i < mainData.size() - 1; i++) { // -1 to avoid look-ahead bias
            HistoricalCandle currentCandle = mainData.get(i);
            double currentPrice = currentCandle.getClose();

            // Prepare historical data slices for analysis (no look-ahead)
            List<List<Object>> fourHourKlines = prepareKlinesSlice(timeframeData.get("4h"), i, 300);
            List<List<Object>> dailyKlines = prepareKlinesSlice(timeframeData.get("1d"), i, 200);
            List<List<Object>> hourlyKlines = prepareKlinesSlice(timeframeData.get("1h"), i, 100);

            // Skip if insufficient data
            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                continue;
            }

            // Analyze using the same logic as live trading
            CoinAnalysis analysis = swingAlgoService.analyzeHistoricalCoin(
                    symbol, currentPrice, fourHourKlines, dailyKlines, hourlyKlines);

            // Position management
            if (currentPosition == null && analysis.getSignal() != Signal.NO_TRADE) {
                // Open new position
                currentPosition = openPosition(symbol, analysis, currentPrice, currentBalance, currentCandle.getTimestamp());
                currentBalance -= Math.abs(((Position) currentPosition).getPositionSize() * currentPrice * feeRate); // Entry fee

            } else if (currentPosition != null) {
                // Check exit conditions
                boolean shouldExit = shouldExitPosition(currentPosition, analysis, currentPrice, currentCandle);

                if (shouldExit) {
                    // Close position
                    BacktestTrade trade = closePosition(currentPosition, currentPrice, currentCandle.getTimestamp());
                    trades.add(trade);

                    currentBalance += trade.getPnl();
                    currentBalance -= Math.abs(trade.getPositionSize() * currentPrice * feeRate); // Exit fee

                    currentPosition = null;
                }
            }

            // Track portfolio performance
            double portfolioValue = currentBalance;
            if (currentPosition != null) {
                double unrealizedPnl = calculateUnrealizedPnl(currentPosition, currentPrice);
                portfolioValue += unrealizedPnl;
            }

            portfolioValues.add(portfolioValue);

            // Calculate return
            if (!portfolioValues.isEmpty() && portfolioValues.size() > 1) {
                double prevValue = portfolioValues.get(portfolioValues.size() - 2);
                double returnPct = (portfolioValue - prevValue) / prevValue;
                returns.add(returnPct);
            }

            // Track drawdown
            if (portfolioValue > peakValue) {
                peakValue = portfolioValue;
            } else {
                double drawdown = (peakValue - portfolioValue) / peakValue;
                maxDrawdown = Math.max(maxDrawdown, drawdown);
            }
        }

        // Close any remaining position
        if (currentPosition != null) {
            HistoricalCandle lastCandle = mainData.get(mainData.size() - 1);
            BacktestTrade trade = closePosition(currentPosition, lastCandle.getClose(), lastCandle.getTimestamp());
            trades.add(trade);
            currentBalance += trade.getPnl();
        }

        // Calculate final metrics
        return calculateBacktestMetrics(symbol, timeframe, trades, portfolioValues, returns,
                maxDrawdown, startDate, endDate, currentBalance);
    }

    private List<List<Object>> prepareKlinesSlice(List<HistoricalCandle> data, int currentIndex, int lookback) {
        int startIdx = Math.max(0, currentIndex - lookback);
        int endIdx = Math.min(currentIndex + 1, data.size()); // +1 to include current candle

        return data.subList(startIdx, endIdx).stream()
                .map(candle -> List.<Object>of(
                        candle.getTimestamp().toEpochSecond(ZoneOffset.UTC) * 1000, // timestamp
                        String.valueOf(candle.getOpen()),
                        String.valueOf(candle.getHigh()),
                        String.valueOf(candle.getLow()),
                        String.valueOf(candle.getClose()),
                        String.valueOf(candle.getVolume())
                ))
                .collect(java.util.stream.Collectors.toList());
    }

    private Position openPosition(String symbol, CoinAnalysis analysis, double price,
                                  double balance, LocalDateTime timestamp) {

        double positionSize = (balance * positionSizePercent) / price;

        return Position.builder()
                .symbol(symbol)
                .side(analysis.getSignal())
                .entryPrice(price)
                .positionSize(positionSize)
                .entryTime(timestamp)
                .tradingRule(analysis.getTradingRule())
                .score(analysis.getScore())
                .build();
    }

    private boolean shouldExitPosition(Position position, CoinAnalysis analysis,
                                       double currentPrice, HistoricalCandle candle) {

        // Exit conditions:
        // 1. Opposite signal
        if ((position.getSide() == Signal.LONG && analysis.getSignal() == Signal.SHORT) ||
                (position.getSide() == Signal.SHORT && analysis.getSignal() == Signal.LONG)) {
            return true;
        }

        // 2. Risk management - Stop loss / Take profit
        double pnlPercent = calculatePnlPercent(position, currentPrice);

        if (pnlPercent <= -5.0) { // 5% stop loss
            return true;
        }

        if (pnlPercent >= 8.0) { // 8% take profit
            return true;
        }

        // 3. Time-based exit (optional)
        long hoursInPosition = java.time.Duration.between(position.getEntryTime(), candle.getTimestamp()).toHours();
        if (hoursInPosition >= 168) { // 7 days max
            return true;
        }

        return false;
    }

    private BacktestTrade closePosition(Position position, double exitPrice, LocalDateTime exitTime) {
        double pnl = calculateUnrealizedPnl(position, exitPrice);
        double pnlPercent = calculatePnlPercent(position, exitPrice);

        return BacktestTrade.builder()
                .symbol(position.getSymbol())
                .side(position.getSide())
                .entryPrice(position.getEntryPrice())
                .exitPrice(exitPrice)
                .positionSize(position.getPositionSize())
                .entryTime(position.getEntryTime())
                .exitTime(exitTime)
                .pnl(pnl)
                .pnlPercent(pnlPercent)
                .tradingRule(position.getTradingRule())
                .score(position.getScore())
                .build();
    }

    private double calculateUnrealizedPnl(Position position, double currentPrice) {
        if (position.getSide() == Signal.LONG) {
            return position.getPositionSize() * (currentPrice - position.getEntryPrice());
        } else {
            return position.getPositionSize() * (position.getEntryPrice() - currentPrice);
        }
    }

    private double calculatePnlPercent(Position position, double currentPrice) {
        if (position.getSide() == Signal.LONG) {
            return (currentPrice - position.getEntryPrice()) / position.getEntryPrice() * 100;
        } else {
            return (position.getEntryPrice() - currentPrice) / position.getEntryPrice() * 100;
        }
    }

    private BacktestResult calculateBacktestMetrics(String symbol, String timeframe,
                                                    List<BacktestTrade> trades,
                                                    List<Double> portfolioValues,
                                                    List<Double> returns,
                                                    double maxDrawdown,
                                                    LocalDateTime startDate, LocalDateTime endDate,
                                                    double finalBalance) {

        double totalReturnPercent = (finalBalance - initialBalance) / initialBalance * 100;

        // Win/Loss statistics
        long winningTrades = trades.stream().mapToLong(t -> t.getPnl() > 0 ? 1 : 0).sum();
        long losingTrades = trades.size() - winningTrades;
        double winRate = trades.isEmpty() ? 0.0 : (double) winningTrades / trades.size() * 100;

        // Average P&L
        double avgWin = trades.stream().filter(t -> t.getPnl() > 0).mapToDouble(BacktestTrade::getPnlPercent).average().orElse(0.0);
        double avgLoss = trades.stream().filter(t -> t.getPnl() < 0).mapToDouble(BacktestTrade::getPnlPercent).average().orElse(0.0);

        // Sharpe ratio (simplified)
        double avgReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double returnStd = calculateStandardDeviation(returns);
        double sharpeRatio = returnStd > 0 ? avgReturn / returnStd : 0.0;

        // Profit factor
        double grossProfit = trades.stream().filter(t -> t.getPnl() > 0).mapToDouble(BacktestTrade::getPnl).sum();
        double grossLoss = Math.abs(trades.stream().filter(t -> t.getPnl() < 0).mapToDouble(BacktestTrade::getPnl).sum());
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : Double.MAX_VALUE;

        return BacktestResult.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .startDate(startDate)
                .endDate(endDate)
                .initialBalance(initialBalance)
                .finalBalance(finalBalance)
                .totalReturnPercent(totalReturnPercent)
                .maxDrawdownPercent(maxDrawdown * 100)
                .totalTrades(trades.size())
                .winningTrades((int) winningTrades)
                .losingTrades((int) losingTrades)
                .winRate(winRate)
                .averageWinPercent(avgWin)
                .averageLossPercent(avgLoss)
                .profitFactor(profitFactor)
                .sharpeRatio(sharpeRatio)
                .trades(trades)
                .success(true)
                .build();
    }

    private double calculateStandardDeviation(List<Double> values) {
        if (values.isEmpty()) return 0.0;

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }

    /**
     * Run backtest for multiple symbols and compare results
     */
    public List<BacktestResult> runMultiSymbolBacktest(List<String> symbols, String timeframe,
                                                       LocalDateTime startDate, LocalDateTime endDate) {

        return symbols.parallelStream()
                .map(symbol -> runBacktest(symbol, timeframe, startDate, endDate))
                .filter(BacktestResult::isSuccess)
                .sorted((r1, r2) -> Double.compare(r2.getTotalReturnPercent(), r1.getTotalReturnPercent()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Analyze strategy performance by trading rules
     */
    public Map<Integer, BacktestRuleAnalysis> analyzeByTradingRules(BacktestResult result) {
        Map<Integer, List<BacktestTrade>> tradesByRule = result.getTrades().stream()
                .collect(java.util.stream.Collectors.groupingBy(BacktestTrade::getTradingRule));

        Map<Integer, BacktestRuleAnalysis> analysis = new HashMap<>();

        for (Map.Entry<Integer, List<BacktestTrade>> entry : tradesByRule.entrySet()) {
            Integer rule = entry.getKey();
            List<BacktestTrade> ruleTrades = entry.getValue();

            long wins = ruleTrades.stream().mapToLong(t -> t.getPnl() > 0 ? 1 : 0).sum();
            double totalPnl = ruleTrades.stream().mapToDouble(BacktestTrade::getPnl).sum();
            double avgPnlPercent = ruleTrades.stream().mapToDouble(BacktestTrade::getPnlPercent).average().orElse(0.0);
            double winRate = ruleTrades.isEmpty() ? 0.0 : (double) wins / ruleTrades.size() * 100;

            BacktestRuleAnalysis ruleAnalysis = BacktestRuleAnalysis.builder()
                    .tradingRule(rule)
                    .totalTrades(ruleTrades.size())
                    .winningTrades((int) wins)
                    .winRate(winRate)
                    .totalPnl(totalPnl)
                    .averagePnlPercent(avgPnlPercent)
                    .trades(ruleTrades)
                    .build();

            analysis.put(rule, ruleAnalysis);
        }

        return analysis;
    }

    // Add these method implementations to your BacktestService class:

    private final List<BacktestResult> backtestHistory = new ArrayList<>();


    /**
     * String-based method for compatibility with MLController
     */
    public BacktestResult runBacktest(String symbol, String timeframe, String startDate, String endDate) {
        try {
            LocalDateTime startDateTime = LocalDateTime.parse(startDate);
            LocalDateTime endDateTime = LocalDateTime.parse(endDate);

            BacktestResult result = runBacktest(symbol, timeframe, startDateTime, endDateTime);

            // Store successful results for ML training
            if (result.isSuccess()) {
                backtestHistory.add(result);
                // Keep only last 100 results to avoid memory issues
                if (backtestHistory.size() > 100) {
                    backtestHistory.remove(0);
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to parse dates for backtest: {}", e.getMessage());
            return BacktestResult.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .success(false)
                    .errorMessage("Invalid date format: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Get all backtest results for ML training
     */
    public List<BacktestResult> getAllBacktestResults() {
        return new ArrayList<>(backtestHistory);
    }

    /**
     * Get backtest results filtered by criteria
     */
    public List<BacktestResult> getBacktestResults(String symbol, String timeframe) {
        return backtestHistory.stream()
                .filter(result -> symbol == null || symbol.equals(result.getSymbol()))
                .filter(result -> timeframe == null || timeframe.equals(result.getTimeframe()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Clear backtest history (for testing purposes)
     */
    public void clearBacktestHistory() {
        backtestHistory.clear();
    }

    /**
     * Get total number of stored backtest results
     */
    public int getBacktestHistorySize() {
        return backtestHistory.size();
    }
}