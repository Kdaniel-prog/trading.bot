package kd.trading.bot.service.backtest;

import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestTrade;
import kd.trading.bot.model.SwingAlgoTrainingPoint;
import kd.trading.bot.service.ratingProcess.algorithm.SwingAlgoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestService {

    private final SwingAlgoService swingAlgoService;
    private final FeatherDataLoader featherDataLoader;

    // Trading parameters
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

    @Value("${backtest.risk.max.holding.hours:168}")
    private long maxHoldingHours;

    @Value("${backtest.execution.delay.candles:1}")
    private int executionDelayCandles;

    @Value("${backtest.execution.slippage.percent:0.05}")
    private double slippagePercent;

    /**
     * MAIN METHOD: SwingAlgo-focused backtest
     */
    public BacktestResult runSwingAlgoBacktest(String symbol, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Starting SwingAlgo-focused backtest for {} from {} to {}", symbol, startDate, endDate);

        try {
            // 1. Load historical data for all timeframes
            Map<String, List<HistoricalCandle>> multiTfData = loadMultiTimeframeData(symbol, startDate, endDate);

            if (!hasMinimumData(multiTfData)) {
                return createErrorResult(symbol, "Insufficient multi-timeframe data");
            }

            // 2. Run SwingAlgo-based trading simulation
            SwingAlgoBacktestResult swingResult = runSwingAlgoSimulation(symbol, multiTfData, startDate, endDate);

            // 3. Calculate performance metrics
            BacktestResult result = calculateBacktestMetrics(symbol, swingResult, startDate, endDate);

            log.info("SwingAlgo backtest completed for {}: {} trades, {}% profit, {} analysis points",
                    symbol, result.getTotalTrades(), result.getTotalReturnPercent(), swingResult.getAnalysisPoints().size());

            // 4. Store SwingAlgo analysis points for ML training
            result.setSwingAlgoAnalysisPoints(swingResult.getAnalysisPoints());

            return result;

        } catch (Exception e) {
            log.error("SwingAlgo backtest failed for {}: {}", symbol, e.getMessage(), e);
            return createErrorResult(symbol, "SwingAlgo backtest error: " + e.getMessage());
        }
    }

    /**
     * Load multi-timeframe data for SwingAlgoService
     */
    private Map<String, List<HistoricalCandle>> loadMultiTimeframeData(String symbol, LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, List<HistoricalCandle>> data = new HashMap<>();

        // Extended start date for technical indicators (need ~200 candles history)
        LocalDateTime extendedStart = startDate.minusDays(200);
        String[] timeframes = {"4h", "1d", "1h", "30m"};

        for (String timeframe : timeframes) {
            try {
                List<HistoricalCandle> candleData = featherDataLoader.loadHistoricalData(
                        symbol, timeframe, extendedStart, endDate);
                data.put(timeframe, candleData);
                log.debug("Loaded {} {} candles for SwingAlgo analysis", candleData.size(), timeframe);
            } catch (Exception e) {
                log.warn("Failed to load {} data for {}: {}", timeframe, symbol, e.getMessage());
                data.put(timeframe, Collections.emptyList());
            }
        }

        return data;
    }

    /**
     * CORE: SwingAlgo-based trading simulation
     */
    private SwingAlgoBacktestResult runSwingAlgoSimulation(String symbol, Map<String, List<HistoricalCandle>> multiTfData,
                                                           LocalDateTime startDate, LocalDateTime endDate) {

        List<BacktestTrade> trades = new ArrayList<>();
        List<SwingAlgoTrainingPoint> analysisPoints = new ArrayList<>();

        List<HistoricalCandle> mainData = multiTfData.get("4h"); // Use 4h as main timeframe
        double currentBalance = initialBalance;
        Position currentPosition = null;

        // Start analysis after enough historical data for indicators
        int analysisStartIndex = Math.max(300, mainData.size() / 5);

        log.info("SwingAlgo simulation: {} total candles, starting analysis at index {}",
                mainData.size(), analysisStartIndex);

        for (int i = analysisStartIndex; i < mainData.size() - executionDelayCandles; i++) {
            HistoricalCandle currentCandle = mainData.get(i);
            LocalDateTime currentTime = currentCandle.getTimestamp();

            // Skip if before start date
            if (currentTime.isBefore(startDate)) continue;

            try {
                // MAIN: Call SwingAlgo for TRAINING analysis (separate from live trading)
                CoinAnalysis swingAnalysis = performSwingAlgoTrainingAnalysis(
                        symbol, currentCandle, currentTime, multiTfData);

                if (swingAnalysis == null) {
                    log.debug("SwingAlgo returned null analysis for {} at {}", symbol, currentTime);
                    continue;
                }

                // Create training point from SwingAlgo analysis
                SwingAlgoTrainingPoint trainingPoint = createSwingAlgoTrainingPoint(
                        swingAnalysis, currentCandle, currentTime);

                // Position management based on SwingAlgo signals
                if (currentPosition == null) {
                    // Try to open position based on SwingAlgo signal
                    if (shouldOpenPositionFromSwingAlgo(swingAnalysis)) {
                        currentPosition = openPosition(swingAnalysis, mainData, i, currentBalance);
                        if (currentPosition != null) {
                            currentBalance -= calculateFees(currentPosition.getPositionSize() * currentPosition.getEntryPrice());
                            trainingPoint.setWasTradeOpened(true);
                            log.debug("Opened {} position at {} based on SwingAlgo signal",
                                    swingAnalysis.getSignal(), currentPosition.getEntryPrice());
                        }
                    }

                    // Set actual outcome as NO_TRADE if no position opened
                    if (currentPosition == null) {
                        trainingPoint.setActualOutcome("NO_TRADE");
                        trainingPoint.setActualPnlPercent(0.0);
                        trainingPoint.setWasTradeOpened(false);
                    }

                } else {
                    // Check if should close position
                    if (shouldClosePosition(currentPosition, swingAnalysis, currentCandle)) {
                        BacktestTrade trade = closePosition(currentPosition, mainData, i);
                        if (trade != null) {
                            trades.add(trade);
                            currentBalance += trade.getPnl();

                            // Log detailed fee breakdown for first few trades
                            if (trades.size() <= 3) {
                                logTradeExample(trade);
                            }

                            // Update training point with actual trade outcome
                            trainingPoint.setActualOutcome(determineTradeOutcome(trade.getPnlPercent()));
                            trainingPoint.setActualPnlPercent(trade.getPnlPercent());
                            trainingPoint.setTradeDurationHours(trade.getHoldingTimeHours());
                            trainingPoint.setWasTradeOpened(true);

                            // Set exit reason based on why trade was closed
                            trainingPoint.setExitReason(determineExitReason(trade, currentPosition, currentCandle));

                            log.debug("Closed position: PnL={}%, Duration={}h, Outcome={}, Reason={}",
                                    trade.getPnlPercent(), trade.getHoldingTimeHours(),
                                    trainingPoint.getActualOutcome(), trainingPoint.getExitReason());
                            currentPosition = null;
                        }
                    } else {
                        // Position still holding
                        double unrealizedPnl = calculateUnrealizedPnlPercent(currentPosition, currentCandle.getClose());
                        trainingPoint.setActualOutcome("HOLDING");
                        trainingPoint.setActualPnlPercent(unrealizedPnl);
                        trainingPoint.setTradeDurationHours(ChronoUnit.HOURS.between(currentPosition.getEntryTime(), currentTime));
                        trainingPoint.setWasTradeOpened(true);
                    }
                }

                // Add SwingAlgo analysis to training data
                analysisPoints.add(trainingPoint);

            } catch (Exception e) {
                log.warn("SwingAlgo analysis failed at {}: {}", currentTime, e.getMessage());
            }
        }

        // Close any remaining position
        if (currentPosition != null) {
            BacktestTrade finalTrade = closePosition(currentPosition, mainData, mainData.size() - 1);
            if (finalTrade != null) {
                trades.add(finalTrade);
                currentBalance += finalTrade.getPnl();
            }
        }

        return SwingAlgoBacktestResult.builder()
                .trades(trades)
                .analysisPoints(analysisPoints)
                .finalBalance(currentBalance)
                .totalAnalysisPoints(analysisPoints.size())
                .build();
    }

    /**
     * SEPARATED METHOD FOR TRAINING: SwingAlgo analysis for backtesting/training
     * This method is specifically for generating training data from historical analysis
     */
    private CoinAnalysis performSwingAlgoTrainingAnalysis(String symbol, HistoricalCandle currentCandle,
                                                          LocalDateTime currentTime, Map<String, List<HistoricalCandle>> multiTfData) {
        try {
            // Build historical klines data up to current time (no look-ahead bias)
            List<List<Object>> fourHourKlines = buildHistoricalKlines(
                    filterDataBeforeTime(multiTfData.get("4h"), currentTime), 300);
            List<List<Object>> dailyKlines = buildHistoricalKlines(
                    filterDataBeforeTime(multiTfData.get("1d"), currentTime), 200);
            List<List<Object>> hourlyKlines = buildHistoricalKlines(
                    filterDataBeforeTime(multiTfData.get("1h"), currentTime), 100);
            List<List<Object>> thirtyMinKlines = buildHistoricalKlines(
                    filterDataBeforeTime(multiTfData.get("30m"), currentTime), 50);

            // Validate minimum data requirements
            if (fourHourKlines.size() < 50 || dailyKlines.size() < 30) {
                return null;
            }

            log.debug("Training analysis for {}: 4h={}, daily={}, hourly={}, 30m={} candles",
                    symbol, fourHourKlines.size(), dailyKlines.size(), hourlyKlines.size(), thirtyMinKlines.size());

            // Call SwingAlgoService with historical data for TRAINING
            CoinAnalysis analysis = swingAlgoService.analyzeHistoricalCoin(
                    symbol,
                    currentCandle.getClose(),
                    fourHourKlines,
                    dailyKlines,
                    hourlyKlines,
                    thirtyMinKlines
            );

            if (analysis != null) {
                log.debug("SwingAlgo training analysis result for {} at {}: signal={}, score={}, confidence={}",
                        symbol, currentTime, analysis.getSignal(), analysis.getScore(), analysis.getMlConfidence());
            }

            return analysis;

        } catch (Exception e) {
            log.warn("SwingAlgo training analysis error for {} at {}: {}", symbol, currentTime, e.getMessage());
            return null;
        }
    }

    /**
     * SEPARATED METHOD FOR LIVE TRADING: SwingAlgo analysis for real-time trading
     * This method would be used in live trading scenarios
     */
    private CoinAnalysis performSwingAlgoLiveAnalysis(String symbol, double currentPrice,
                                                      Map<String, List<HistoricalCandle>> multiTfData) {
        try {
            // For live trading, use most recent data (no time filtering needed)
            List<List<Object>> fourHourKlines = buildHistoricalKlines(multiTfData.get("4h"), 300);
            List<List<Object>> dailyKlines = buildHistoricalKlines(multiTfData.get("1d"), 200);
            List<List<Object>> hourlyKlines = buildHistoricalKlines(multiTfData.get("1h"), 100);
            List<List<Object>> thirtyMinKlines = buildHistoricalKlines(multiTfData.get("30m"), 50);

            // Validate minimum data requirements
            if (fourHourKlines.size() < 50 || dailyKlines.size() < 30) {
                log.warn("Insufficient data for live SwingAlgo analysis: 4h={}, daily={}",
                        fourHourKlines.size(), dailyKlines.size());
                return null;
            }

            log.debug("Live analysis for {}: 4h={}, daily={}, hourly={}, 30m={} candles",
                    symbol, fourHourKlines.size(), dailyKlines.size(), hourlyKlines.size(), thirtyMinKlines.size());

            // Call SwingAlgoService for LIVE trading
            CoinAnalysis analysis = swingAlgoService.analyzeHistoricalCoin(
                    symbol,
                    currentPrice,
                    fourHourKlines,
                    dailyKlines,
                    hourlyKlines,
                    thirtyMinKlines
            );

            if (analysis != null) {
                log.info("SwingAlgo live analysis result for {}: signal={}, score={}, confidence={}",
                        symbol, analysis.getSignal(), analysis.getScore(), analysis.getMlConfidence());
            }

            return analysis;

        } catch (Exception e) {
            log.error("SwingAlgo live analysis error for {}: {}", symbol, e.getMessage(), e);
            return null;
        }
    }

    /**
     * FIXED: Create training point from SwingAlgo analysis with proper signal mapping
     */
    private SwingAlgoTrainingPoint createSwingAlgoTrainingPoint(CoinAnalysis swingAnalysis,
                                                                HistoricalCandle currentCandle,
                                                                LocalDateTime analysisTime) {

        // FIXED: Properly map the signal from SwingAlgo analysis
        Signal mappedSignal = swingAnalysis.getSignal() != null ? swingAnalysis.getSignal() : Signal.NO_TRADE;

        SwingAlgoTrainingPoint point = SwingAlgoTrainingPoint.builder()
                .timestamp(analysisTime)
                .symbol(swingAnalysis.getSymbol())
                .price(currentCandle.getClose())
                .swingAlgoSignal(mappedSignal)  // FIXED: Properly set the signal
                .swingAlgoScore(swingAnalysis.getScore() != null ? swingAnalysis.getScore() : 0.0)
                .swingAlgoConfidence(swingAnalysis.getMlConfidence())
                .build();

        // Log the signal mapping for debugging
        log.debug("Signal mapping for {}: original={}, mapped={}, score={}, confidence={}",
                swingAnalysis.getSymbol(), swingAnalysis.getSignal(), mappedSignal,
                swingAnalysis.getScore(), swingAnalysis.getMlConfidence());

        // Extract ALL technical indicators from SwingAlgo
        if (swingAnalysis.getTechnicalIndicators() != null) {
            TechnicalIndicators indicators = swingAnalysis.getTechnicalIndicators();

            Map<String, Object> features = new HashMap<>();

            // Core indicators
            addFeatureIfNotNull(features, "rsi", indicators.getRsi());
            addFeatureIfNotNull(features, "ema20_4h", indicators.getEma20_4h());
            addFeatureIfNotNull(features, "ema50_4h", indicators.getEma50_4h());
            addFeatureIfNotNull(features, "ema200_daily", indicators.getEma200_daily());

            // Trend indicators
            addFeatureIfNotNull(features, "primaryTrend", indicators.getPrimaryTrend());
            addFeatureIfNotNull(features, "shortTermTrend", indicators.getShortTermTrend());
            addFeatureIfNotNull(features, "trendAlignment", indicators.isTrendAlignment());
            addFeatureIfNotNull(features, "trendStrength", indicators.getTrendStrength());

            // MACD indicators
            addFeatureIfNotNull(features, "macdLine", indicators.getMacdLine());
            addFeatureIfNotNull(features, "macdSignal", indicators.getMacdSignal());
            addFeatureIfNotNull(features, "macdHistogram", indicators.getMacdHistogram());
            addFeatureIfNotNull(features, "macdBullish", indicators.isMacdBullish());
            addFeatureIfNotNull(features, "macdBearish", indicators.isMacdBearish());

            // Volume indicators
            addFeatureIfNotNull(features, "volumeRatio", indicators.getVolumeRatio());
            addFeatureIfNotNull(features, "strongVolume", indicators.isStrongVolume());

            // Volatility indicators
            addFeatureIfNotNull(features, "atr", indicators.getAtr());
            addFeatureIfNotNull(features, "volatilityPercent", indicators.getVolatilityPercent());
            addFeatureIfNotNull(features, "riskRewardRatio", indicators.getRiskRewardRatio());

            // Support/Resistance
            addFeatureIfNotNull(features, "nearestSupport", indicators.getNearestSupport());
            addFeatureIfNotNull(features, "nearestResistance", indicators.getNearestResistance());

            // Structure indicators
            addFeatureIfNotNull(features, "bullishStructure", indicators.isBullishStructure());
            addFeatureIfNotNull(features, "bearishStructure", indicators.isBearishStructure());
            addFeatureIfNotNull(features, "consolidation", indicators.isConsolidation());


// Add derived features with safe null checks
            double ema20 = indicators.getEma20_4h();
            double ema50 = indicators.getEma50_4h();
            double rsi   = indicators.getRsi();
            Double vol   = indicators.getVolumeRatio();

            if (ema20 > 0) {
                features.put("price_vs_ema20", currentCandle.getClose() / ema20);
            }

            if (ema50 > 0) {
                features.put("price_vs_ema50", currentCandle.getClose() / ema50);
            }

            if (ema50 > 0) {
                features.put("ema_alignment", ema20 > ema50 ? 1.0 : 0.0);
            }

            features.put("rsi_oversold", rsi < 30 ? 1.0 : 0.0);
            features.put("rsi_overbought", rsi > 70 ? 1.0 : 0.0);

            features.put("high_volume", vol > 1.5 ? 1.0 : 0.0);

            point.setSwingAlgoFeatures(features);

            log.debug("Extracted {} SwingAlgo features for {}", features.size(), swingAnalysis.getSymbol());
        } else {
            log.warn("No technical indicators found in SwingAlgo analysis for {}", swingAnalysis.getSymbol());
        }

        return point;
    }

    /**
     * Helper method to safely add features, handling null values
     */
    private void addFeatureIfNotNull(Map<String, Object> features, String key, Object value) {
        if (value != null) {
            if (value instanceof Boolean) {
                features.put(key, ((Boolean) value) ? 1.0 : 0.0);
            } else if (value instanceof Number) {
                features.put(key, ((Number) value).doubleValue());
            } else {
                features.put(key, value.toString());
            }
        }
    }

    // Helper methods for position management
    private boolean shouldOpenPositionFromSwingAlgo(CoinAnalysis analysis) {
        boolean hasValidSignal = analysis.getSignal() != null && analysis.getSignal() != Signal.NO_TRADE;
        boolean hasMinScore = analysis.getScore() != null && analysis.getScore() >= 5.0;
        boolean hasMinConfidence = analysis.getMlConfidence() != null && analysis.getMlConfidence() >= 0.6;

        boolean shouldOpen = hasValidSignal && hasMinScore && hasMinConfidence;

        log.debug("Position opening decision for {}: signal={}, score={}, confidence={}, shouldOpen={}",
                analysis.getSymbol(), analysis.getSignal(), analysis.getScore(), analysis.getMlConfidence(), shouldOpen);

        return shouldOpen;
    }

    private Position openPosition(CoinAnalysis analysis, List<HistoricalCandle> mainData, int signalIndex, double balance) {
        int executionIndex = Math.min(signalIndex + executionDelayCandles, mainData.size() - 1);
        HistoricalCandle executionCandle = mainData.get(executionIndex);

        double executionPrice = executionCandle.getOpen();

        // Apply slippage based on direction
        if (analysis.getSignal() == Signal.LONG) {
            executionPrice *= (1 + slippagePercent / 100.0);
        } else {
            executionPrice *= (1 - slippagePercent / 100.0);
        }

        // Calculate position size based on configured percentage
        double notional = balance * positionSizePercent;
        double positionSize = notional / executionPrice;

        return Position.builder()
                .symbol(analysis.getSymbol())
                .side(analysis.getSignal())
                .entryPrice(executionPrice)
                .positionSize(positionSize)
                .entryTime(executionCandle.getTimestamp())
                .tradingRule(analysis.getTradingRule() != null ? analysis.getTradingRule() : 0)
                .score(analysis.getScore())
                .build();
    }

    private boolean shouldClosePosition(Position position, CoinAnalysis analysis, HistoricalCandle candle) {
        // Apply configured stop loss and take profit
        double pnlPercent = calculateUnrealizedPnlPercent(position, candle.getClose());
        if (pnlPercent <= -stopLossPercent || pnlPercent >= takeProfitPercent) {
            log.debug("Closing position due to SL/TP: PnL={}%, SL={}%, TP={}%",
                    pnlPercent, stopLossPercent, takeProfitPercent);
            return true;
        }

        // Apply configured max holding time
        long hours = ChronoUnit.HOURS.between(position.getEntryTime(), candle.getTimestamp());
        if (hours >= maxHoldingHours) {
            log.debug("Closing position due to max holding time: {}h >= {}h", hours, maxHoldingHours);
            return true;
        }

        // SwingAlgo signal reversal (with null checks)
        if (analysis.getSignal() != null) {
            if (position.getSide() == Signal.LONG && analysis.getSignal() == Signal.SHORT) {
                log.debug("Closing LONG position due to SwingAlgo SHORT signal");
                return true;
            }
            if (position.getSide() == Signal.SHORT && analysis.getSignal() == Signal.LONG) {
                log.debug("Closing SHORT position due to SwingAlgo LONG signal");
                return true;
            }
        }

        return false;
    }

    private BacktestTrade closePosition(Position position, List<HistoricalCandle> mainData, int currentIndex) {
        int executionIndex = Math.min(currentIndex + executionDelayCandles, mainData.size() - 1);
        HistoricalCandle executionCandle = mainData.get(executionIndex);

        double exitPrice = executionCandle.getOpen();

        // Apply slippage when closing
        if (position.getSide() == Signal.LONG) {
            exitPrice *= (1 - slippagePercent / 100.0); // Sell at slightly lower price
        } else {
            exitPrice *= (1 + slippagePercent / 100.0); // Buy to cover at slightly higher price
        }

        double pnl = calculateRealizedPnl(position, exitPrice);
        double pnlPercent = calculatePnlPercent(position, exitPrice);

        // Calculate fees (entry + exit)
        double entryFee = calculateFees(position.getPositionSize() * position.getEntryPrice());
        double exitFee = calculateFees(position.getPositionSize() * exitPrice);
        double totalFees = entryFee + exitFee;

        // Subtract fees from PnL
        double netPnl = pnl - totalFees;
        double netPnlPercent = (netPnl / (position.getPositionSize() * position.getEntryPrice())) * 100;

        return BacktestTrade.builder()
                .symbol(position.getSymbol())
                .side(position.getSide())
                .entryPrice(position.getEntryPrice())
                .exitPrice(exitPrice)
                .positionSize(position.getPositionSize())
                .entryTime(position.getEntryTime())
                .exitTime(executionCandle.getTimestamp())
                .pnl(netPnl) // Net PnL after fees
                .pnlPercent(netPnlPercent) // Net PnL percentage
                .tradingRule(position.getTradingRule())
                .score(position.getScore())
                .build();
    }

    // Helper calculation methods
    private double calculateUnrealizedPnlPercent(Position position, double currentPrice) {
        if (position.getSide() == Signal.LONG) {
            return (currentPrice - position.getEntryPrice()) / position.getEntryPrice() * 100;
        } else {
            return (position.getEntryPrice() - currentPrice) / position.getEntryPrice() * 100;
        }
    }

    private double calculateRealizedPnl(Position position, double exitPrice) {
        if (position.getSide() == Signal.LONG) {
            return position.getPositionSize() * (exitPrice - position.getEntryPrice());
        } else {
            return position.getPositionSize() * (position.getEntryPrice() - exitPrice);
        }
    }

    private double calculatePnlPercent(Position position, double exitPrice) {
        if (position.getSide() == Signal.LONG) {
            return (exitPrice - position.getEntryPrice()) / position.getEntryPrice() * 100;
        } else {
            return (position.getEntryPrice() - exitPrice) / position.getEntryPrice() * 100;
        }
    }

    private double calculateFees(double notional) {
        return notional * feeRate;
    }

    /**
     * EXAMPLE: Realistic fee calculation with detailed logging
     */
    private void logTradeExample(BacktestTrade trade) {
        double entryNotional = trade.getPositionSize() * trade.getEntryPrice();
        double exitNotional = trade.getPositionSize() * trade.getExitPrice();
        double entryFee = entryNotional * feeRate;
        double exitFee = exitNotional * feeRate;
        double totalFees = entryFee + exitFee;
        double grossPnl = calculateRealizedPnl(Position.builder()
                .side(trade.getSide())
                .positionSize(trade.getPositionSize())
                .entryPrice(trade.getEntryPrice())
                .build(), trade.getExitPrice());

        log.debug("Trade fees breakdown for {}: Entry=${:.2f} (fee=${:.3f}), Exit=${:.2f} (fee=${:.3f}), " +
                        "Gross PnL=${:.2f}, Net PnL=${:.2f}, Fees=${:.3f}",
                trade.getSymbol(), entryNotional, entryFee, exitNotional, exitFee,
                grossPnl, trade.getPnl(), totalFees);
    }

    private String determineTradeOutcome(double pnlPercent) {
        if (pnlPercent > 5.0) return "STRONG_WIN";
        if (pnlPercent > 1.0) return "WIN";
        if (pnlPercent > -1.0) return "NEUTRAL";
        if (pnlPercent > -5.0) return "LOSS";
        return "STRONG_LOSS";
    }

    /**
     * Determine why a trade was closed based on configured parameters
     */
    private String determineExitReason(BacktestTrade trade, Position position, HistoricalCandle currentCandle) {
        double pnlPercent = trade.getPnlPercent();
        long durationHours = trade.getHoldingTimeHours();

        // Check configured stop loss and take profit levels
        if (pnlPercent <= -stopLossPercent) {
            return "STOP_LOSS";
        }
        if (pnlPercent >= takeProfitPercent) {
            return "TAKE_PROFIT";
        }

        // Check configured max holding time
        if (durationHours >= maxHoldingHours) {
            return "MAX_TIME";
        }

        // Default to signal reversal
        return "SIGNAL_REVERSAL";
    }

    // Helper methods for data management
    private boolean hasMinimumData(Map<String, List<HistoricalCandle>> data) {
        return data.get("4h").size() >= 300 && data.get("1d").size() >= 200;
    }

    private List<HistoricalCandle> filterDataBeforeTime(List<HistoricalCandle> data, LocalDateTime cutoffTime) {
        return data.stream()
                .filter(candle -> candle.getTimestamp().isBefore(cutoffTime))
                .collect(Collectors.toList());
    }

    private List<List<Object>> buildHistoricalKlines(List<HistoricalCandle> candles, int maxSize) {
        if (candles.isEmpty()) {
            return Collections.emptyList();
        }

        int startIndex = Math.max(0, candles.size() - maxSize);
        return candles.subList(startIndex, candles.size()).stream()
                .map(candle -> List.<Object>of(
                        candle.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC) * 1000,
                        candle.getOpen(),
                        candle.getHigh(),
                        candle.getLow(),
                        candle.getClose(),
                        candle.getVolume()
                ))
                .collect(Collectors.toList());
    }

    private BacktestResult calculateBacktestMetrics(String symbol, SwingAlgoBacktestResult swingResult,
                                                    LocalDateTime startDate, LocalDateTime endDate) {
        List<BacktestTrade> trades = swingResult.getTrades();
        double finalBalance = swingResult.getFinalBalance();

        double totalReturnPercent = (finalBalance - initialBalance) / initialBalance * 100.0;
        long winningTrades = trades.stream().mapToLong(t -> t.getPnl() > 0 ? 1 : 0).sum();
        double winRate = trades.isEmpty() ? 0.0 : (double) winningTrades / trades.size() * 100.0;

        return BacktestResult.builder()
                .symbol(symbol)
                .timeframe("4h")
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
                .totalSignals(swingResult.getTotalAnalysisPoints())
                .swingAlgoAnalysisPoints(swingResult.getAnalysisPoints()) // Add SwingAlgo data
                .build();
    }

    private BacktestResult createErrorResult(String symbol, String errorMessage) {
        return BacktestResult.builder()
                .symbol(symbol)
                .timeframe("4h")
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

}