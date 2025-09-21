package kd.trading.bot.service.backtest;

import kd.trading.bot.enums.Direction;
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

import static kd.trading.bot.enums.OrderSide.BUY;
import static kd.trading.bot.enums.OrderSide.SELL;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BacktestService {

    final SwingAlgoService swingAlgoService;
    final FeatherDataLoader featherDataLoader;

    // Configuration from YAML
    @Value("${backtest.initial.balance:10000.0}")
    private double initialBalance;

    @Value("${backtest.fee.rate:0.0004}")
    private double feeRate;

    @Value("${backtest.position.size.percent:0.1}")
    private double positionSizePercent;

    // Updated risk management for directional trading
    @Value("${backtest.risk.stop.loss.percent:3.0}")
    private double stopLossPercent;

    @Value("${backtest.risk.take.profit.percent:6.0}")
    private double takeProfitPercent;

    @Value("${backtest.risk.max.holding.hours:120}")
    private int maxHoldingHours;

    // Directional trading thresholds
    @Value("${backtest.directional.min.confidence:0.65}")
    private double minDirectionalConfidence;

    @Value("${backtest.directional.min.score:5.0}")
    private double minDirectionalScore;

    @Value("${backtest.directional.hold.exit.confidence:0.8}")
    private double holdExitConfidence;

    private final List<BacktestResult> backtestHistory = new ArrayList<>();

    public BacktestResult runBacktest(String symbol, String timeframe, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Starting directional backtest for {} {} from {} to {}", symbol, timeframe, startDate, endDate);

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

            // 3. Run directional backtest simulation
            BacktestResult result = simulateDirectionalTrading(symbol, timeframe, timeframeData, startDate, endDate);

            log.info("Directional backtest completed for {}: Final PnL: {}%, Trades: {}, Hold Ratio: {}%",
                    symbol, result.getTotalReturnPercent(), result.getTotalTrades(), result.getHoldRatio());

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

    private BacktestResult simulateDirectionalTrading(String symbol, String timeframe,
                                                      Map<String, List<HistoricalCandle>> timeframeData,
                                                      LocalDateTime startDate, LocalDateTime endDate) {

        List<HistoricalCandle> mainData = timeframeData.get(timeframe);
        List<BacktestTrade> trades = new ArrayList<>();

        double currentBalance = initialBalance;
        Position currentPosition = null;

        // Directional trading statistics
        int totalSignals = 0;
        int holdSignals = 0;
        int longSignals = 0;
        int shortSignals = 0;
        int positionsOpened = 0;

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
            int idx4h = findClosestIndex(timeframeData.get("4h"), currentCandle.getTimestamp());
            int idx1d = findClosestIndex(timeframeData.get("1d"), currentCandle.getTimestamp());
            int idx1h = findClosestIndex(timeframeData.get("1h"), currentCandle.getTimestamp());

            List<List<Object>> fourHourKlines = prepareKlinesSlice(timeframeData.get("4h"), idx4h, 300);
            List<List<Object>> dailyKlines = prepareKlinesSlice(timeframeData.get("1d"), idx1d, 200);
            List<List<Object>> hourlyKlines = prepareKlinesSlice(timeframeData.get("1h"), idx1h, 100);

            // Skip if insufficient data
            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                continue;
            }

            // Analyze using directional logic
            CoinAnalysis analysis = swingAlgoService.analyzeHistoricalCoin(
                    symbol, currentPrice, fourHourKlines, dailyKlines, hourlyKlines);

            Direction direction = analysis.getDirection();
            totalSignals++;

            // Count directional signals
            switch (direction) {
                case HOLD -> holdSignals++;
                case LONG -> longSignals++;
                case SHORT -> shortSignals++;
            }

            // Position management with directional logic
            // Position management with directional logic
            if (currentPosition == null) {
                // Open new position only for LONG or SHORT with sufficient confidence and score
                double mlConfidence = analysis.getMlConfidence(); // Most Double, lehet null
                if (direction != Direction.HOLD && analysis.getScore() >= minDirectionalScore && mlConfidence >= minDirectionalConfidence) {

                    currentPosition = openDirectionalPosition(symbol, analysis, currentPrice,
                            currentBalance, currentCandle.getTimestamp(), direction);
                    currentBalance -= Math.abs(currentPosition.getPositionSize() * currentPrice * feeRate);
                    positionsOpened++;

                    logDirectionalTrade("OPEN", currentPosition, analysis, currentPrice, direction);
                }
            } else {
                // Check exit conditions
                boolean shouldExit = shouldExitDirectionalPosition(currentPosition, analysis,
                        currentPrice, currentCandle, direction);

                if (shouldExit) {
                    BacktestTrade trade = closePosition(currentPosition, currentPrice, currentCandle.getTimestamp());
                    trades.add(trade);

                    currentBalance += trade.getPnl();
                    currentBalance -= Math.abs(trade.getPositionSize() * currentPrice * feeRate);

                    logDirectionalTrade("CLOSE", currentPosition, analysis, currentPrice, direction);
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

        // Calculate final metrics with directional statistics
        BacktestResult result = calculateDirectionalBacktestMetrics(symbol, timeframe, trades, portfolioValues,
                returns, maxDrawdown, startDate, endDate,
                currentBalance, totalSignals, holdSignals,
                longSignals, shortSignals, positionsOpened);

        return result;
    }

    private Position openDirectionalPosition(String symbol, CoinAnalysis analysis, double price,
                                             double balance, LocalDateTime timestamp, Direction direction) {

        double positionSize = (balance * positionSizePercent) / price;
        Signal signal = convertDirectionToSignal(direction);

        return Position.builder()
                .symbol(symbol)
                .side(signal)
                .entryPrice(price)
                .positionSize(positionSize)
                .entryTime(timestamp)
                .tradingRule(analysis.getTradingRule())
                .score(analysis.getScore())
                .direction(direction)
                .build();
    }

    private boolean shouldExitDirectionalPosition(Position position, CoinAnalysis analysis,
                                                  double currentPrice, HistoricalCandle candle,
                                                  Direction currentDirection) {

        Direction positionDirection = getPositionDirection(position);

        // 1. Direction-based exit logic with confidence check
        double mlConfidence = analysis.getMlConfidence(); // wrapper Double, lehet null
        if (mlConfidence > minDirectionalConfidence) {
            // Exit if ML suggests opposite direction
            if ((positionDirection == Direction.LONG && currentDirection == Direction.SHORT) ||
                    (positionDirection == Direction.SHORT && currentDirection == Direction.LONG)) {
                return true;
            }

            // Exit if ML suggests HOLD with very high confidence (market uncertainty)
            if (currentDirection == Direction.HOLD && mlConfidence > holdExitConfidence) {
                return true;
            }
        }
        // 2. Risk management - Stop loss / Take profit
        double pnlPercent = calculatePnlPercent(position, currentPrice);

        if (pnlPercent <= -stopLossPercent) {
            return true;
        }

        if (pnlPercent >= takeProfitPercent) {
            return true;
        }

        // 3. Time-based exit
        long hoursInPosition = java.time.Duration.between(position.getEntryTime(), candle.getTimestamp()).toHours();
        if (hoursInPosition >= maxHoldingHours) {
            return true;
        }

        // 4. Volatility-based exit
        if (analysis.getTechnicalIndicators() != null &&
                analysis.getTechnicalIndicators().getVolatilityPercent() > 12.0) {
            return true; // Exit in extreme volatility
        }

        return false;
    }

    private Direction getPositionDirection(Position position) {
        if (position.getDirection() != null) {
            return position.getDirection();
        }

        // Fallback conversion from Signal
        return switch (position.getSide()) {
            case LONG -> Direction.LONG;
            case SHORT -> Direction.SHORT;
            default -> Direction.HOLD;
        };
    }

    private Signal convertDirectionToSignal(Direction direction) {
        return switch (direction) {
            case LONG -> Signal.LONG;
            case SHORT -> Signal.SHORT;
            case HOLD -> Signal.NO_TRADE;
        };
    }

    private void logDirectionalTrade(String action, Position position, CoinAnalysis analysis,
                                     double currentPrice, Direction direction) {

        Double mlConfidence = analysis.getMlConfidence(); // wrapper Double
        String message = String.format(
                "%s %s position for %s: Price=%.4f, Direction=%s, Confidence=%.2f, Score=%.1f",
                action,
                getPositionDirection(position),
                position.getSymbol(),
                currentPrice,
                direction,
                mlConfidence,
                analysis.getScore()
        );

        log.info("🔄 " + message);
    }

    private int findClosestIndex(List<HistoricalCandle> candles, LocalDateTime timestamp) {
        for (int j = 0; j < candles.size(); j++) {
            if (!candles.get(j).getTimestamp().isBefore(timestamp)) {
                return j;
            }
        }
        return candles.size() - 1; // fallback to last
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
                .direction(getPositionDirection(position))
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

    private BacktestResult calculateDirectionalBacktestMetrics(String symbol, String timeframe,
                                                               List<BacktestTrade> trades,
                                                               List<Double> portfolioValues,
                                                               List<Double> returns,
                                                               double maxDrawdown,
                                                               LocalDateTime startDate, LocalDateTime endDate,
                                                               double finalBalance,
                                                               int totalSignals, int holdSignals,
                                                               int longSignals, int shortSignals,
                                                               int positionsOpened) {

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

        // Directional statistics
        double holdRatio = totalSignals > 0 ? (double) holdSignals / totalSignals * 100 : 0.0;
        double longRatio = totalSignals > 0 ? (double) longSignals / totalSignals * 100 : 0.0;
        double shortRatio = totalSignals > 0 ? (double) shortSignals / totalSignals * 100 : 0.0;
        double positionFillRate = totalSignals > holdSignals ? (double) positionsOpened / (totalSignals - holdSignals) * 100 : 0.0;

        BacktestResult result = BacktestResult.builder()
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
                // Directional specific metrics
                .totalSignals(totalSignals)
                .holdSignals(holdSignals)
                .longSignals(longSignals)
                .shortSignals(shortSignals)
                .holdRatio(holdRatio)
                .longRatio(longRatio)
                .shortRatio(shortRatio)
                .positionsOpened(positionsOpened)
                .positionFillRate(positionFillRate)
                .build();

        // Log directional statistics
        log.info("Directional Backtest Stats for {}:", symbol);
        log.info("  Total Signals: {}, Hold: {} ({:.1f}%), Long: {} ({:.1f}%), Short: {} ({:.1f}%)",
                totalSignals, holdSignals, holdRatio, longSignals, longRatio, shortSignals, shortRatio);
        log.info("  Positions Opened: {} / {} actionable signals ({:.1f}% fill rate)",
                positionsOpened, totalSignals - holdSignals, positionFillRate);

        return result;
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