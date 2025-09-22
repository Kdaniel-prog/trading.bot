package kd.trading.bot.service.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import kd.trading.bot.enums.Direction;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestTrade;
import kd.trading.bot.model.backtest.TrainingDataPoint;
import kd.trading.bot.service.ratingProcess.algorithm.SwingAlgoService;
import kd.trading.bot.util.IndicatorUtil;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BacktestService {

    final IndicatorUtil indicatorUtil;
    final SwingAlgoService swingAlgoService;

    @Getter
    final FeatherDataLoader featherDataLoader;

    // FIXED: Consistent timeframe list as constant
    private static final List<String> SUPPORTED_TIMEFRAMES = List.of("30m", "1h", "4h", "1d");

    @Value("${backtest.initial.balance:10000.0}")
    private double initialBalance;

    @Value("${backtest.fee.rate:0.0004}")
    private double feeRate;

    @Value("${backtest.position.size.percent:0.1}")
    private double positionSizePercent;

    @Value("${backtest.risk.stop.loss.percent:3.0}")
    private double stopLossPercent;

    @Value("${backtest.risk.take.profit.percent:6.0}")
    private double takeProfitPercent;

    @Value("${backtest.risk.max.holding.hours:240}")
    private long maxHoldingHours;

    @Value("${backtest.directional.min.confidence:0.65}")
    private double minDirectionalConfidence;

    @Value("${backtest.directional.min.score:5.0}")
    private double minDirectionalScore;

    @Value("${backtest.execution.delay.candles:1}")
    private int executionDelayCandles;

    @Value("${backtest.execution.slippage.percent:0.05}")
    private double slippagePercent;

    /**
     * FIXED: Single backtest method that returns BacktestResult (not List)
     */
    public BacktestResult runBacktest(String symbol, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Starting backtest for {} from {} to {}", symbol, startDate, endDate);

        // Default to 4h timeframe for main analysis
        String mainTimeframe = "4h";

        try {
            List<HistoricalCandle> mainData =
                    featherDataLoader.loadHistoricalData(symbol, mainTimeframe, startDate, endDate);

            if (mainData.size() < 300) {
                return createErrorResult(symbol, mainTimeframe,
                        "Insufficient data: " + mainData.size() + " candles");
            }

            Map<String, List<HistoricalCandle>> multiTfData =
                    loadRealTimeframeData(symbol, startDate, endDate);

            BacktestResult result = simulateRealisticTrading(
                    symbol, mainTimeframe, mainData, multiTfData, startDate, endDate);

            log.info("Backtest finished for {}: {} trades, {}% PnL",
                    symbol, result.getTotalTrades(), result.getTotalReturnPercent());

            return result;

        } catch (Exception e) {
            log.error("Backtest failed for {}: {}", symbol, e.getMessage());
            return createErrorResult(symbol, mainTimeframe, e.getMessage());
        }
    }

    /**
     * Load all timeframe data for multi-timeframe analysis
     */
    private Map<String, List<HistoricalCandle>> loadRealTimeframeData(
            String symbol, LocalDateTime startDate, LocalDateTime endDate) {

        Map<String, List<HistoricalCandle>> timeframeData = new HashMap<>();
        LocalDateTime extendedStart = startDate.minusDays(100);

        for (String timeframe : SUPPORTED_TIMEFRAMES) {
            try {
                List<HistoricalCandle> data = featherDataLoader.loadHistoricalData(
                        symbol, timeframe, extendedStart, endDate);
                timeframeData.put(timeframe, data);
                log.debug("Loaded {} data: {} candles", timeframe, data.size());
            } catch (Exception e) {
                log.warn("Failed to load {} data for {}: {}", timeframe, symbol, e.getMessage());
                timeframeData.put(timeframe, Collections.emptyList());
            }
        }

        return timeframeData;
    }

    /**
     * FIXED: Realistic trading simulation with proper training data collection
     */
    private BacktestResult simulateRealisticTrading(String symbol, String timeframe,
                                                    List<HistoricalCandle> mainData,
                                                    Map<String, List<HistoricalCandle>> timeframeData,
                                                    LocalDateTime startDate, LocalDateTime endDate) {

        List<BacktestTrade> trades = new ArrayList<>();
        List<TrainingDataPoint> trainingData = new ArrayList<>();

        double currentBalance = initialBalance;
        Position currentPosition = null;
        int totalSignals = 0;
        int tradingStartIndex = Math.max(200, mainData.size() / 10);

        for (int i = tradingStartIndex; i < mainData.size() - executionDelayCandles; i++) {
            HistoricalCandle currentCandle = mainData.get(i);

            // Analyze with historical data only
            CoinAnalysis analysis = analyzeWithHistoricalDataOnly(
                    symbol, currentCandle, i, mainData, timeframeData);

            if (analysis == null) continue;

            totalSignals++;
            Direction direction = analysis.getDirection();

            // Create training data point for this signal
            TrainingDataPoint dataPoint = createTrainingDataPoint(
                    currentCandle, analysis, i, mainData, timeframeData);

            // Position management
            if (currentPosition == null) {
                if (shouldOpenPosition(direction, analysis)) {
                    currentPosition = openPositionWithRealism(
                            symbol, analysis, mainData, i, currentBalance, direction);
                    if (currentPosition != null) {
                        currentBalance -= calculateFees(currentPosition, currentPosition.getEntryPrice());
                        log.debug("Opened {} position for {} at {}",
                                direction, symbol, currentPosition.getEntryPrice());
                    }
                }

                // If no position opened, mark as NO_TRADE
                if (currentPosition == null) {
                    dataPoint.setActualOutcome("NO_TRADE");
                    dataPoint.setActualPnl(0.0);
                    trainingData.add(dataPoint);
                }
            } else {
                // Check if should close position
                if (shouldClosePosition(currentPosition, analysis, currentCandle, direction)) {
                    BacktestTrade trade = closePositionWithRealism(currentPosition, mainData, i);
                    if (trade != null) {
                        trades.add(trade);
                        currentBalance += trade.getPnl();
                        currentBalance -= calculateFees(currentPosition, trade.getExitPrice());

                        // Set actual outcome based on trade result
                        dataPoint.setActualOutcome(determineActualOutcome(trade.getPnlPercent()));
                        dataPoint.setActualPnl(trade.getPnlPercent());
                        trainingData.add(dataPoint);

                        log.debug("Closed position: PnL={}%", trade.getPnlPercent());
                        currentPosition = null;
                    }
                } else {
                    // Position still holding
                    dataPoint.setActualOutcome("HOLDING");
                    dataPoint.setActualPnl(calculatePnlPercent(currentPosition, currentCandle.getClose()));
                    trainingData.add(dataPoint);
                }
            }
        }

        // Close remaining position
        if (currentPosition != null) {
            BacktestTrade finalTrade = closePositionWithRealism(currentPosition, mainData, mainData.size() - 1);
            if (finalTrade != null) {
                trades.add(finalTrade);
                currentBalance += finalTrade.getPnl();
            }
        }

        // FIXED: Export training data with proper validation
        log.info("Collected {} training data points for {}", trainingData.size(), symbol);
        if (!trainingData.isEmpty()) {
            exportTrainingDataAsJson(trainingData, symbol, timeframe);
        }

        return calculateBacktestMetrics(symbol, timeframe, trades, currentBalance,
                startDate, endDate, totalSignals);
    }

    /**
     * Create training data point with real technical indicators
     */
    private TrainingDataPoint createTrainingDataPoint(HistoricalCandle currentCandle, CoinAnalysis analysis,
                                                      int currentIndex, List<HistoricalCandle> mainData,
                                                      Map<String, List<HistoricalCandle>> timeframeData) {

        // Calculate real technical indicators using IndicatorUtil
        Map<String, Object> indicators = calculateRealTechnicalIndicators(
                currentCandle.getTimestamp(), timeframeData, currentIndex, mainData);

        // Add analysis indicators
        Map<String, Object> analysisIndicators = extractIndicatorsFromAnalysis(analysis);
        indicators.putAll(analysisIndicators);

        return TrainingDataPoint.builder()
                .timestamp(currentCandle.getTimestamp())
                .symbol(analysis.getSymbol())
                .price(currentCandle.getClose())
                .predictedDirection(analysis.getDirection())
                .predictedSignal(convertDirectionToSignal(analysis.getDirection()))
                .confidence(analysis.getMlConfidence())
                .score(analysis.getScore())
                .technicalIndicators(indicators)
                .tradingRule(analysis.getTradingRule())
                .actualOutcome(null) // Set later
                .actualPnl(null)     // Set later
                .build();
    }

    /**
     * FIXED: Calculate real technical indicators using IndicatorUtil
     */
    private Map<String, Object> calculateRealTechnicalIndicators(LocalDateTime timestamp,
                                                                 Map<String, List<HistoricalCandle>> timeframeData,
                                                                 int currentIndex,
                                                                 List<HistoricalCandle> mainData) {
        Map<String, Object> indicators = new HashMap<>();

        try {
            // Get 4H data up to current timestamp
            List<HistoricalCandle> h4Data = getHistoricalDataUpTo(timeframeData.get("4h"), timestamp);
            if (h4Data.size() >= 50) {
                List<Double> closes = h4Data.stream()
                        .map(HistoricalCandle::getClose)
                        .collect(Collectors.toList());
                List<Double> highs = h4Data.stream()
                        .map(HistoricalCandle::getHigh)
                        .collect(Collectors.toList());
                List<Double> lows = h4Data.stream()
                        .map(HistoricalCandle::getLow)
                        .collect(Collectors.toList());
                List<Double> volumes = h4Data.stream()
                        .map(HistoricalCandle::getVolume)
                        .toList();

                // Use IndicatorUtil for calculations
                double rsi = indicatorUtil.RSI(closes, 14);
                double ema20 = indicatorUtil.EMA(closes, 20);
                double ema50 = indicatorUtil.EMA(closes, 50);
                double sma20 = indicatorUtil.SMA(closes, 20);
                double[] macd = indicatorUtil.MACD(closes, 12, 26, 9);
                double atr = indicatorUtil.calculateATR(highs, lows, closes, 14);
                double[] supportResistance = indicatorUtil.calculateSupportResistance(highs, lows, closes);

                // Store calculated indicators
                indicators.put("rsi", rsi);
                indicators.put("ema20", ema20);
                indicators.put("ema50", ema50);
                indicators.put("sma20", sma20);
                indicators.put("macd_line", macd[0]);
                indicators.put("macd_signal", macd[1]);
                indicators.put("macd_histogram", macd[2]);
                indicators.put("atr", atr);
                indicators.put("support_level", supportResistance[0]);
                indicators.put("resistance_level", supportResistance[1]);

                // Price position indicators
                double currentPrice = closes.get(closes.size() - 1);
                indicators.put("current_price", currentPrice);
                indicators.put("price_above_ema20", currentPrice > ema20);
                indicators.put("price_above_ema50", currentPrice > ema50);
                indicators.put("ema20_above_ema50", ema20 > ema50);

                // Volume analysis
                double avgVolume = volumes.stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
                double currentVolume = volumes.get(volumes.size() - 1);
                double volumeRatio = avgVolume > 0 ? currentVolume / avgVolume : 1.0;
                indicators.put("volume_ratio", volumeRatio);
                indicators.put("high_volume", volumeRatio > 1.5);

                // Pattern recognition
                indicators.put("higher_highs", indicatorUtil.isHigherHighsPattern(highs, 20));
                indicators.put("lower_lows", indicatorUtil.isLowerLowsPattern(lows, 20));
                indicators.put("breakout_pattern", indicatorUtil.isBreakoutPattern(highs, lows, closes));
                indicators.put("reversal_pattern", indicatorUtil.isReversalPattern(highs, lows, closes));

                // Momentum and volatility
                double momentum = indicatorUtil.calculateMomentum(closes, 10);
                double volatility = indicatorUtil.calculateVolatility(closes, 20);
                indicators.put("momentum", momentum);
                indicators.put("volatility_percent", volatility);

                log.debug("Calculated technical indicators for {}: RSI={}, EMA20={}, Volume={}",
                        timestamp, rsi, ema20, volumeRatio);
            }

            // Get daily data for long-term trends
            List<HistoricalCandle> dailyData = getHistoricalDataUpTo(timeframeData.get("1d"), timestamp);
            if (dailyData.size() >= 200) {
                List<Double> dailyCloses = dailyData.stream()
                        .map(HistoricalCandle::getClose)
                        .collect(Collectors.toList());

                double ema200Daily = indicatorUtil.EMA(dailyCloses, Math.min(200, dailyCloses.size()));
                indicators.put("ema200_daily", ema200Daily);

                double currentPrice = dailyCloses.get(dailyCloses.size() - 1);
                indicators.put("price_above_ema200", currentPrice > ema200Daily);
            }

        } catch (Exception e) {
            log.error("Error calculating technical indicators: {}", e.getMessage());
            // Set safe defaults
            indicators.put("rsi", 50.0);
            indicators.put("ema20", 0.0);
            indicators.put("error", "calculation_failed");
        }

        return indicators;
    }

    /**
     * FIXED: Export training data as proper JSON array
     */
    private void exportTrainingDataAsJson(List<TrainingDataPoint> trainingData, String symbol, String timeframe) {
        if (trainingData.isEmpty()) {
            log.warn("No training data to export for {} {}", symbol, timeframe);
            return;
        }

        try {
            // Create export directory
            File exportDir = new File("src/main/resources/data/training");
            if (!exportDir.exists()) {
                boolean created = exportDir.mkdirs();
                log.info("Created training data directory: {}", exportDir.getAbsolutePath());
            }

            // Generate filename
            String fileName = String.format("training_data_%s_%s_%d.json",
                    symbol.replace("/", "").replace("_", ""),
                    timeframe,
                    System.currentTimeMillis());
            File outputFile = new File(exportDir, fileName);

            // Filter valid data points
            List<TrainingDataPoint> validData = trainingData.stream()
                    .filter(point -> point != null &&
                            point.getTechnicalIndicators() != null &&
                            !point.getTechnicalIndicators().isEmpty())
                    .collect(Collectors.toList());

            if (validData.isEmpty()) {
                log.error("No valid training data points after filtering for {} {}", symbol, timeframe);
                return;
            }

            // FIXED: Create proper JSON structure (array, not single object)
            Map<String, Object> exportWrapper = new HashMap<>();
            exportWrapper.put("metadata", Map.of(
                    "symbol", symbol,
                    "timeframe", timeframe,
                    "generated_at", LocalDateTime.now().toString(),
                    "total_points", validData.size(),
                    "version", "2.0"
            ));
            exportWrapper.put("training_data", validData); // This creates the array structure

            // Configure ObjectMapper
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            // Write to file
            mapper.writeValue(outputFile, exportWrapper);

            log.info("Training data exported: {} ({} points)", outputFile.getName(), validData.size());

            // Log sample for verification
            if (!validData.isEmpty()) {
                TrainingDataPoint sample = validData.get(0);
                log.info("Sample: symbol={}, outcome={}, indicators={}",
                        sample.getSymbol(), sample.getActualOutcome(),
                        sample.getTechnicalIndicators().size());
            }

        } catch (Exception e) {
            log.error("Failed to export training data for {} {}: {}", symbol, timeframe, e.getMessage(), e);
        }
    }

    // === HELPER METHODS (unchanged but needed) ===

    private CoinAnalysis analyzeWithHistoricalDataOnly(String symbol, HistoricalCandle currentCandle,
                                                       int currentIndex, List<HistoricalCandle> mainData,
                                                       Map<String, List<HistoricalCandle>> timeframeData) {
        try {
            List<List<Object>> thirtyMinKlines = buildHistoricalKlines(
                    timeframeData.get("30m"), currentCandle.getTimestamp(), 200);
            List<List<Object>> hourlyKlines = buildHistoricalKlines(
                    timeframeData.get("1h"), currentCandle.getTimestamp(), 100);
            List<List<Object>> fourHourKlines = buildHistoricalKlines(
                    timeframeData.get("4h"), currentCandle.getTimestamp(), 300);
            List<List<Object>> dailyKlines = buildHistoricalKlines(
                    timeframeData.get("1d"), currentCandle.getTimestamp(), 200);

            if (fourHourKlines.size() < 50 || dailyKlines.size() < 30) {
                return null;
            }

            return swingAlgoService.analyzeHistoricalCoin(
                    symbol, currentCandle.getClose(),
                    fourHourKlines, dailyKlines, hourlyKlines, thirtyMinKlines);

        } catch (Exception e) {
            log.warn("Analysis failed for {} at {}: {}", symbol, currentCandle.getTimestamp(), e.getMessage());
            return null;
        }
    }

    private List<List<Object>> buildHistoricalKlines(List<HistoricalCandle> timeframeData,
                                                     LocalDateTime currentTime, int lookback) {
        if (timeframeData == null || timeframeData.isEmpty()) {
            return Collections.emptyList();
        }

        List<HistoricalCandle> historicalOnly = timeframeData.stream()
                .filter(candle -> candle.getTimestamp().isBefore(currentTime))
                .toList();

        if (historicalOnly.isEmpty()) {
            return Collections.emptyList();
        }

        int startIndex = Math.max(0, historicalOnly.size() - lookback);

        return historicalOnly.subList(startIndex, historicalOnly.size()).stream()
                .map(candle -> List.<Object>of(
                        candle.getTimestamp().toEpochSecond(ZoneOffset.UTC) * 1000,
                        candle.getOpen(),
                        candle.getHigh(),
                        candle.getLow(),
                        candle.getClose(),
                        candle.getVolume()
                ))
                .collect(Collectors.toList());
    }

    private Map<String, Object> extractIndicatorsFromAnalysis(CoinAnalysis analysis) {
        Map<String, Object> indicators = new HashMap<>();
        if (analysis == null) return indicators;

        try {
            indicators.put("analysis_ml_confidence", analysis.getMlConfidence());
            indicators.put("analysis_direction", analysis.getDirection().name());
            indicators.put("analysis_score", analysis.getScore());
            indicators.put("analysis_last_price", analysis.getLastPrice());
            indicators.put("analysis_trading_rule", analysis.getTradingRule());

            TechnicalIndicators t = analysis.getTechnicalIndicators();
            if (t != null) {
                indicators.put("analysis_rsi", t.getRsi());
                indicators.put("analysis_ema20_4h", t.getEma20_4h());
                indicators.put("analysis_ema50_4h", t.getEma50_4h());
                indicators.put("analysis_volume_ratio", t.getVolumeRatio());
                indicators.put("analysis_macd_line", t.getMacdLine());
                indicators.put("analysis_macd_signal", t.getMacdSignal());
                indicators.put("analysis_trend_strength", t.getTrendStrength());
                indicators.put("analysis_bullish_structure", t.isBullishMarketStructure());
                indicators.put("analysis_bearish_structure", t.isBearishMarketStructure());
            }

        } catch (Exception e) {
            log.error("Error extracting indicators from analysis: {}", e.getMessage());
        }

        return indicators;
    }

    private List<HistoricalCandle> getHistoricalDataUpTo(List<HistoricalCandle> data, LocalDateTime timestamp) {
        if (data == null) return Collections.emptyList();
        return data.stream()
                .filter(c -> c.getTimestamp().isBefore(timestamp) || c.getTimestamp().isEqual(timestamp))
                .collect(Collectors.toList());
    }

    // Position management methods remain the same
    private boolean shouldOpenPosition(Direction direction, CoinAnalysis analysis) {
        return direction != Direction.HOLD &&
                analysis.getScore() >= minDirectionalScore &&
                analysis.getMlConfidence() >= minDirectionalConfidence;
    }

    private Position openPositionWithRealism(String symbol, CoinAnalysis analysis,
                                             List<HistoricalCandle> mainData, int signalIndex,
                                             double balance, Direction direction) {
        int executionIndex = Math.min(signalIndex + executionDelayCandles, mainData.size() - 1);
        HistoricalCandle executionCandle = mainData.get(executionIndex);

        double executionPrice = executionCandle.getOpen();
        if (direction == Direction.LONG) {
            executionPrice *= (1 + slippagePercent / 100.0);
        } else {
            executionPrice *= (1 - slippagePercent / 100.0);
        }

        double notional = balance * positionSizePercent;
        double positionSize = notional / executionPrice;

        return Position.builder()
                .symbol(symbol)
                .side(convertDirectionToSignal(direction))
                .entryPrice(executionPrice)
                .positionSize(positionSize)
                .entryTime(executionCandle.getTimestamp())
                .tradingRule(analysis.getTradingRule())
                .score(analysis.getScore())
                .direction(direction)
                .build();
    }

    private boolean shouldClosePosition(Position position, CoinAnalysis analysis,
                                        HistoricalCandle candle, Direction currentDirection) {
        double pnlPercent = calculatePnlPercent(position, candle.getClose());
        if (pnlPercent <= -stopLossPercent || pnlPercent >= takeProfitPercent) {
            return true;
        }

        long hours = ChronoUnit.HOURS.between(position.getEntryTime(), candle.getTimestamp());
        if (hours >= maxHoldingHours) {
            return true;
        }

        Direction posDirection = getPositionDirection(position);
        return (posDirection == Direction.LONG && currentDirection == Direction.SHORT) ||
                (posDirection == Direction.SHORT && currentDirection == Direction.LONG);
    }

    private BacktestTrade closePositionWithRealism(Position position, List<HistoricalCandle> mainData, int currentIndex) {
        int executionIndex = Math.min(currentIndex + executionDelayCandles, mainData.size() - 1);
        HistoricalCandle executionCandle = mainData.get(executionIndex);

        double exitPrice = executionCandle.getOpen();
        Direction posDirection = getPositionDirection(position);
        if (posDirection == Direction.LONG) {
            exitPrice *= (1 - slippagePercent / 100.0);
        } else {
            exitPrice *= (1 + slippagePercent / 100.0);
        }

        double pnl = calculateUnrealizedPnl(position, exitPrice);
        double pnlPercent = calculatePnlPercent(position, exitPrice);

        return BacktestTrade.builder()
                .symbol(position.getSymbol())
                .side(position.getSide())
                .entryPrice(position.getEntryPrice())
                .exitPrice(exitPrice)
                .positionSize(position.getPositionSize())
                .entryTime(position.getEntryTime())
                .exitTime(executionCandle.getTimestamp())
                .pnl(pnl)
                .pnlPercent(pnlPercent)
                .tradingRule(position.getTradingRule())
                .score(position.getScore())
                .direction(posDirection)
                .build();
    }

    private double calculatePnlPercent(Position position, double currentPrice) {
        if (position.getSide() == Signal.LONG) {
            return (currentPrice - position.getEntryPrice()) / position.getEntryPrice() * 100;
        } else {
            return (position.getEntryPrice() - currentPrice) / position.getEntryPrice() * 100;
        }
    }

    private String determineActualOutcome(double pnlPercent) {
        if (pnlPercent > 3.0) return "STRONG_WIN";
        if (pnlPercent > 0.5) return "WIN";
        if (pnlPercent > -0.5) return "NEUTRAL";
        if (pnlPercent > -3.0) return "LOSS";
        return "STRONG_LOSS";
    }

    // Standard helper methods
    private BacktestResult calculateBacktestMetrics(String symbol, String timeframe, List<BacktestTrade> trades,
                                                    double finalBalance, LocalDateTime startDate, LocalDateTime endDate,
                                                    int totalSignals) {
        double totalReturnPercent = (finalBalance - initialBalance) / initialBalance * 100.0;
        long winningTrades = trades.stream().mapToLong(t -> t.getPnl() > 0 ? 1 : 0).sum();
        double winRate = trades.isEmpty() ? 0.0 : (double) winningTrades / trades.size() * 100.0;

        return BacktestResult.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .startDate(startDate)
                .endDate(endDate)
                .initialBalance(initialBalance)
                .finalBalance(finalBalance)
                .totalReturnPercent(totalReturnPercent)
                .totalTrades(trades.size())
                .winningTrades((int) winningTrades)
                .winRate(winRate)
                .trades(trades)
                .success(true)
                .totalSignals(totalSignals)
                .build();
    }

    private double calculateFees(Position position, double price) {
        return Math.abs(position.getPositionSize() * price * feeRate);
    }

    private double calculateUnrealizedPnl(Position position, double currentPrice) {
        if (position.getSide() == Signal.LONG) {
            return position.getPositionSize() * (currentPrice - position.getEntryPrice());
        } else {
            return position.getPositionSize() * (position.getEntryPrice() - currentPrice);
        }
    }

    private Direction getPositionDirection(Position position) {
        return position.getDirection() != null ? position.getDirection() :
                (position.getSide() == Signal.LONG ? Direction.LONG : Direction.SHORT);
    }

    private Signal convertDirectionToSignal(Direction direction) {
        return switch (direction) {
            case LONG -> Signal.LONG;
            case SHORT -> Signal.SHORT;
            case HOLD -> Signal.NO_TRADE;
        };
    }

    private BacktestResult createErrorResult(String symbol, String timeframe, String errorMessage) {
        return BacktestResult.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}