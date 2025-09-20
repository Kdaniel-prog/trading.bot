package kd.trading.bot.service.ratingProcess.algorithm;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.service.ml.PythonMLService;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import kd.trading.bot.util.IndicatorUtil;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SwingAlgoService {

    final BinanceRestClient restClient;
    final IndicatorUtil indicatorUtil;
    final ApplicationEventPublisher publisher;
    final PythonMLService pythonMLService;

    // Trading Constants
    static final double MIN_LONG_SCORE = 4.0;
    static final double MIN_SHORT_SCORE = -4.0;
    static final double RISK_REWARD_RATIO = 1.5;

    // ML Integration - ezeket NEM final-nak kell lenni!
    @Value("${ml.enabled:false}")
    private boolean useMlPredictions;

    @Value("${ml.weight:0.3}")
    private double mlWeight;

    @Value("${ml.confidence.threshold:0.6}")
    private double mlConfidenceThreshold;

    // Trading config from your YAML
    @Value("${trading.moneyUsdt:10000}")
    private double accountBalance;

    @Value("${trading.leverage:1}")
    private double maxLeverage;

    @Value("${trading.stopLimit:2.0}")
    private double stopLossPercent;

    @Value("${trading.winLimit:3.0}")
    private double takeProfitPercent;

    public CoinAnalysis analyzeCoin(String symbol, double lastPrice) {
        try {
            // Get multi-timeframe data
            List<List<Object>> fourHourKlines = restClient.getKlines(symbol, "4h", 300);
            List<List<Object>> dailyKlines = restClient.getKlines(symbol, "1d", 200);
            List<List<Object>> hourlyKlines = restClient.getKlines(symbol, "1h", 100);
            List<List<Object>> fifteenMinKlines = restClient.getKlines(symbol, "15m", 50);

            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, 0.0);
            }

            // Extract price data
            List<Double> closes4h = extractCloses(fourHourKlines);
            List<Double> highs4h = extractHighs(fourHourKlines);
            List<Double> lows4h = extractLows(fourHourKlines);
            List<Double> volumes4h = extractVolumes(fourHourKlines);
            List<Double> closesDaily = extractCloses(dailyKlines);
            List<Double> closesHourly = extractCloses(hourlyKlines);
            List<Double> closes15m = extractCloses(fifteenMinKlines);
            List<Double> highs15m = extractHighs(fifteenMinKlines);
            List<Double> lows15m = extractLows(fifteenMinKlines);

            double currentPrice = closes4h.get(closes4h.size() - 1);

            // === TRADITIONAL ANALYSIS ===
            TrendAnalysis trendAnalysis = analyzeTrends(closes4h, closesDaily, closesHourly, currentPrice);
            MomentumAnalysis momentumAnalysis = analyzeMomentum(closes4h);
            VolumeAnalysis volumeAnalysis = analyzeVolume(volumes4h);
            RiskAnalysis riskAnalysis = analyzeRisk(highs4h, lows4h, closes4h, highs15m, lows15m, closes15m, currentPrice);
            StructureAnalysis structureAnalysis = analyzeMarketStructure(highs4h, lows4h, closes4h);

            // Traditional scoring
            double longScore = calculateLongScore(trendAnalysis, momentumAnalysis, volumeAnalysis, riskAnalysis, structureAnalysis);
            double shortScore = calculateShortScore(trendAnalysis, momentumAnalysis, volumeAnalysis, riskAnalysis, structureAnalysis);

            // Traditional signal
            Signal traditionalSignal = determineTraditionalSignal(longScore, shortScore, trendAnalysis, momentumAnalysis, riskAnalysis, volumeAnalysis);

            // === ML ENHANCED ANALYSIS ===
            CoinAnalysis finalAnalysis;

            if (useMlPredictions) {
                // Prepare data for ML
                Map<String, Object> mlData = prepareMlData(symbol, currentPrice, trendAnalysis,
                        momentumAnalysis, volumeAnalysis, riskAnalysis, structureAnalysis);

                // Get ML prediction asynchronously
                CompletableFuture<MLPredictionResponse> mlFuture = pythonMLService.getPrediction(
                        symbol, fourHourKlines, dailyKlines, hourlyKlines, mlData);

                try {
                    // Wait for ML result (max 5 seconds)
                    MLPredictionResponse mlPrediction = mlFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

                    // Combine traditional + ML
                    finalAnalysis = combineTraditionalAndML(symbol, lastPrice,
                            traditionalSignal, longScore, shortScore, mlPrediction);

                    log.info("🤖 ML Enhanced Analysis for {}: Traditional={}/{:.1f}, ML={}/{:.2f}, Final={}/{:.2f}",
                            symbol, traditionalSignal, Math.max(longScore, Math.abs(shortScore)),
                            mlPrediction.getPredictedSignal(), mlPrediction.getConfidence(),
                            finalAnalysis.getSignal(), finalAnalysis.getScore());

                } catch (Exception e) {
                    log.warn("⚠️ ML prediction failed for {}, using traditional: {}", symbol, e.getMessage());
                    finalAnalysis = new CoinAnalysis(symbol, Math.max(longScore, Math.abs(shortScore)), traditionalSignal, lastPrice);
                }
            } else {
                // Pure traditional analysis
                finalAnalysis = new CoinAnalysis(symbol, Math.max(longScore, Math.abs(shortScore)), traditionalSignal, lastPrice);
            }

            // Log results
            if (trendAnalysis.getRule() > 0) {
                String logMessage = String.format("Enhanced Analysis for %s: Signal=%s, Score=%.2f, Rule=%d, ML=%s",
                        symbol, finalAnalysis.getSignal(), finalAnalysis.getScore(),
                        trendAnalysis.getRule(), useMlPredictions ? "ON" : "OFF");
                tradeWithRule(logMessage);
            }

            return finalAnalysis;

        } catch (Exception e) {
            log.error("Failed to analyze coin {}", symbol, e);
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        }
    }

    private Map<String, Object> prepareMlData(String symbol, double currentPrice,
                                              TrendAnalysis trend, MomentumAnalysis momentum,
                                              VolumeAnalysis volume, RiskAnalysis risk,
                                              StructureAnalysis structure) {
        Map<String, Object> mlData = new HashMap<>();

        mlData.put("symbol", symbol);
        mlData.put("currentPrice", currentPrice);

        // Trend Analysis
        mlData.put("trendAnalysis", Map.of(
                "primaryTrend", trend.getPrimaryTrend(),
                "shortTermTrend", trend.getShortTermTrend(),
                "trendAlignment", trend.isTrendAlignment(),
                "trendStrength", trend.getTrendStrength(),
                "ema20_4h", trend.getEma20_4h(),
                "ema50_4h", trend.getEma50_4h()
        ));

        // Momentum Analysis
        mlData.put("momentumAnalysis", Map.of(
                "rsi", momentum.getRsi(),
                "macdBullish", momentum.isMacdBullish(),
                "macdBearish", momentum.isMacdBearish(),
                "rsiBullishZone", momentum.isRsiBullishZone(),
                "rsiBearishZone", momentum.isRsiBearishZone(),
                "rsiRising", momentum.isRsiRising()
        ));

        // Volume Analysis
        mlData.put("volumeAnalysis", Map.of(
                "volumeRatio", volume.getVolumeRatio(),
                "strongVolume", volume.isStrongVolume(),
                "volumePercentile", volume.getVolumePercentile(),
                "volumeBreakout", volume.isVolumeBreakout(),
                "volumeTrendUp", volume.isVolumeTrendUp()
        ));

        // Risk Analysis
        mlData.put("riskAnalysis", Map.of(
                "riskRewardRatio", risk.getRiskRewardRatio(),
                "volatilityPercent", risk.getVolatilityPercent(),
                "distanceFromSupport", risk.getDistanceFromSupport(),
                "distanceFromResistance", risk.getDistanceFromResistance(),
                "goodVolatility", risk.isGoodVolatility(),
                "nearSupport", risk.isNearSupport()
        ));

        // Structure Analysis
        mlData.put("structureAnalysis", Map.of(
                "higherHighs", structure.isHigherHighs(),
                "lowerLows", structure.isLowerLows(),
                "bullishPattern", structure.isBullishPattern(),
                "bearishPattern", structure.isBearishPattern(),
                "structureStrength", structure.getStructureStrength()
        ));

        return mlData;
    }

    private CoinAnalysis combineTraditionalAndML(String symbol, double lastPrice,
                                                 Signal traditionalSignal, double longScore, double shortScore,
                                                 MLPredictionResponse mlPrediction) {

        // Confidence-based weighting
        double mlConfidence = mlPrediction.getConfidence();
        double traditionalConfidence = Math.max(Math.abs(longScore), Math.abs(shortScore)) / 15.0; // Normalize to 0-1
        traditionalConfidence = Math.min(1.0, traditionalConfidence);

        // Dynamic weighting based on confidence
        double dynamicMLWeight = mlConfidence * mlWeight;
        double traditionalWeight = 1.0 - mlWeight;
        double dynamicTraditionalWeight = traditionalConfidence * traditionalWeight;

        // Normalize weights
        double totalWeight = dynamicMLWeight + dynamicTraditionalWeight;
        if (totalWeight > 0) {
            dynamicMLWeight /= totalWeight;
            dynamicTraditionalWeight /= totalWeight;
        } else {
            dynamicMLWeight = 0.5;
            dynamicTraditionalWeight = 0.5;
        }

        // Determine final signal
        Signal finalSignal = Signal.NO_TRADE;
        double finalScore = 0.0;

        // High confidence ML override
        if (mlConfidence > 0.8) {
            finalSignal = mlPrediction.getPredictedSignal();
            finalScore = mlConfidence * 10; // Scale to traditional score range
        }
        // Both signals agree
        else if (traditionalSignal == mlPrediction.getPredictedSignal() && traditionalSignal != Signal.NO_TRADE) {
            finalSignal = traditionalSignal;
            finalScore = (Math.max(Math.abs(longScore), Math.abs(shortScore)) * dynamicTraditionalWeight) +
                    (mlConfidence * 10 * dynamicMLWeight);
        }
        // ML moderately confident
        else if (mlConfidence > 0.65 && mlPrediction.getPredictedSignal() != Signal.NO_TRADE) {
            finalSignal = mlPrediction.getPredictedSignal();
            finalScore = mlConfidence * 8; // Slightly lower score for disagreement
        }
        // Traditional strong signal
        else if (Math.max(Math.abs(longScore), Math.abs(shortScore)) > 10.0 && traditionalSignal != Signal.NO_TRADE) {
            finalSignal = traditionalSignal;
            finalScore = Math.max(Math.abs(longScore), Math.abs(shortScore)) * 0.8; // Reduce for ML disagreement
        }

        // Create enhanced coin analysis
        CoinAnalysis analysis = new CoinAnalysis(symbol, finalScore, finalSignal, lastPrice);

        // Add ML metadata
        analysis.setMlPrediction(mlPrediction);
        analysis.setMlConfidence(mlConfidence);
        analysis.setTraditionalScore(Math.max(Math.abs(longScore), Math.abs(shortScore)));
        analysis.setCombinationMethod(String.format("ML:%.1f%% Traditional:%.1f%%",
                dynamicMLWeight * 100, dynamicTraditionalWeight * 100));

        return analysis;
    }

    private Signal determineTraditionalSignal(double longScore, double shortScore,
                                              TrendAnalysis trend, MomentumAnalysis momentum,
                                              RiskAnalysis risk, VolumeAnalysis volume) {

        trend.setRule(0);

        if (risk.getRiskRewardRatio() < 1.2) {
            return Signal.NO_TRADE;
        }

        // Traditional signal logic (unchanged)
        if (longScore >= 8.0 &&
                (trend.getPrimaryTrend().equals("BULLISH") ||
                        trend.getShortTermTrend().equals("BULLISH")) &&
                momentum.isMacdBullish()) {
            trend.setRule(1);
            return Signal.SHORT;
        }

        if (shortScore <= -8.0 &&
                (trend.getPrimaryTrend().equals("BEARISH") ||
                        trend.getShortTermTrend().equals("BEARISH")) &&
                momentum.isMacdBearish()) {
            trend.setRule(2);
            return Signal.LONG;
        }

        // Additional traditional rules...
        if (longScore >= 3.0 &&
                momentum.isRsiBullishZone() &&
                momentum.isMacdBullish() &&
                momentum.isRsiRising() &&
                risk.getRiskRewardRatio() >= 1.3) {
            trend.setRule(3);
            return Signal.SHORT;
        }

        if (shortScore <= -5.0 && longScore >= 1.5 && longScore < 2.0 &&
                trend.getPrimaryTrend().equals("NEUTRAL") &&
                momentum.isRsiBearishZone() &&
                momentum.isMacdBearish() &&
                !momentum.isRsiRising() &&
                risk.getRiskRewardRatio() >= 4.0) {
            trend.setRule(4);
            return Signal.SHORT;
        }

        if (longScore >= 2.5 &&
                momentum.isRsiBullishZone() &&
                risk.isGoodShortTermVolatility() &&
                volume.isVolumeBreakout()) {
            trend.setRule(5);
            return Signal.LONG;
        }

        if (shortScore <= -2.5 &&
                momentum.isRsiBearishZone() &&
                risk.isGoodShortTermVolatility() &&
                volume.isVolumeBreakout()) {
            trend.setRule(6);
            return Signal.SHORT;
        }

        if (longScore >= 4.0 &&
                trend.getPrimaryTrend().equals("BULLISH") &&
                momentum.isRsiOversold() &&
                risk.getRiskRewardRatio() >= 2.0) {
            trend.setRule(7);
            return Signal.SHORT;
        }

        if (shortScore <= -4.0 &&
                trend.getPrimaryTrend().equals("BEARISH") &&
                momentum.isRsiOverbought() &&
                risk.getRiskRewardRatio() >= 2.0) {
            trend.setRule(8);
            return Signal.LONG;
        }

        return Signal.NO_TRADE;
    }

    // Existing analysis methods (unchanged)
    private VolumeAnalysis analyzeVolume(List<Double> volumes4h) {
        double currentVolume = volumes4h.get(volumes4h.size() - 1);
        double avgVolume20 = volumes4h.subList(volumes4h.size() - 20, volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgVolume50 = volumes4h.subList(Math.max(0, volumes4h.size() - 50), volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double volumeRatio = avgVolume20 > 0 ? currentVolume / avgVolume20 : 0.0;
        boolean strongVolume = volumeRatio > 1.3;
        boolean strongBullishVolume = volumeRatio > 1.8;
        boolean strongBearishVolume = volumeRatio > 1.5;
        boolean aboveAverageVolume = volumeRatio > 0.8;

        List<Double> recentVolumes = volumes4h.subList(Math.max(0, volumes4h.size() - 50), volumes4h.size());
        long belowCurrent = recentVolumes.stream().mapToLong(v -> v < currentVolume ? 1 : 0).sum();
        double volumePercentile = (double) belowCurrent / recentVolumes.size() * 100;

        boolean volumeBreakout = volumePercentile > 75;
        boolean volumeDrying = volumeRatio < 0.7;

        List<Double> last5Volumes = volumes4h.subList(volumes4h.size() - 5, volumes4h.size());
        boolean volumeTrendUp = last5Volumes.get(4) > last5Volumes.get(0);
        double volumeSlope = (last5Volumes.get(4) - last5Volumes.get(0)) / 4.0;
        boolean volumeMomentum = Math.abs(volumeSlope) > avgVolume20 * 0.1;

        return VolumeAnalysis.builder()
                .currentVolume(currentVolume)
                .averageVolume20(avgVolume20)
                .volumeRatio(volumeRatio)
                .strongVolume(strongVolume)
                .strongBullishVolume(strongBullishVolume)
                .strongBearishVolume(strongBearishVolume)
                .aboveAverageVolume(aboveAverageVolume)
                .volumePercentile(volumePercentile)
                .volumeBreakout(volumeBreakout)
                .volumeDrying(volumeDrying)
                .volumeTrendUp(volumeTrendUp)
                .volumeMomentum(volumeMomentum)
                .build();
    }

    /**
     * Historical analysis for backtesting - same logic as analyzeCoin() but with historical data
     */
    public CoinAnalysis analyzeHistoricalCoin(String symbol, double lastPrice,
                                              List<List<Object>> fourHourKlines,
                                              List<List<Object>> dailyKlines,
                                              List<List<Object>> hourlyKlines) {
        try {
            // Ugyanazok az ellenőrzések mint élő adatoknál
            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
            }

            // Extract price data (same extraction methods)
            List<Double> closes4h = extractCloses(fourHourKlines);
            List<Double> highs4h = extractHighs(fourHourKlines);
            List<Double> lows4h = extractLows(fourHourKlines);
            List<Double> volumes4h = extractVolumes(fourHourKlines);
            List<Double> closesDaily = extractCloses(dailyKlines);
            List<Double> closesHourly = extractCloses(hourlyKlines);

            double currentPrice = closes4h.get(closes4h.size() - 1);

            // === SAME ANALYSIS LOGIC ===
            TrendAnalysis trendAnalysis = analyzeTrends(closes4h, closesDaily, closesHourly, currentPrice);
            MomentumAnalysis momentumAnalysis = analyzeMomentum(closes4h);
            VolumeAnalysis volumeAnalysis = analyzeVolume(volumes4h);
            RiskAnalysis riskAnalysis = analyzeRisk(highs4h, lows4h, closes4h,
                    highs4h, lows4h, closes4h, currentPrice); // Use 4h for 15m approximation
            StructureAnalysis structureAnalysis = analyzeMarketStructure(highs4h, lows4h, closes4h);

            // Traditional scoring (same calculation)
            double longScore = calculateLongScore(trendAnalysis, momentumAnalysis,
                    volumeAnalysis, riskAnalysis, structureAnalysis);
            double shortScore = calculateShortScore(trendAnalysis, momentumAnalysis,
                    volumeAnalysis, riskAnalysis, structureAnalysis);

            // Traditional signal (same logic)
            Signal traditionalSignal = determineTraditionalSignal(longScore, shortScore,
                    trendAnalysis, momentumAnalysis, riskAnalysis, volumeAnalysis);

            // Create analysis result (NO ML for historical - pure traditional)
            CoinAnalysis analysis = new CoinAnalysis(symbol,
                    Math.max(Math.abs(longScore), Math.abs(shortScore)),
                    traditionalSignal, lastPrice);

            // Add detailed scoring for backtesting
            analysis.setLongScore(longScore);
            analysis.setShortScore(shortScore);
            analysis.setTradingRule(trendAnalysis.getRule());

            // Add analysis details for ML training features
            analysis.setTrendAnalysis(trendAnalysis);
            analysis.setMomentumAnalysis(momentumAnalysis);
            analysis.setVolumeAnalysis(volumeAnalysis);
            analysis.setRiskAnalysis(riskAnalysis);
            analysis.setStructureAnalysis(structureAnalysis);

            return analysis;

        } catch (Exception e) {
            log.error("Failed to analyze historical coin {}: {}", symbol, e.getMessage());
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        }
    }

    /**
     * Convert HistoricalCandle list to klines format
     */
    public List<List<String>> convertToKlines(List<HistoricalCandle> candles) {
        return candles.stream()
                .map(candle -> List.of(
                        candle.getTimestamp().toString(),
                        String.valueOf(candle.getOpen()),
                        String.valueOf(candle.getHigh()),
                        String.valueOf(candle.getLow()),
                        String.valueOf(candle.getClose()),
                        String.valueOf(candle.getVolume())
                ))
                .collect(Collectors.toList());
    }

    /**
     * Resample candles to different timeframe (for 4h, 1d conversion)
     */
    public List<HistoricalCandle> resample(List<HistoricalCandle> candles, int hours) {
        if (hours == 1) return candles; // No resampling needed

        List<HistoricalCandle> resampled = new ArrayList<>();

        for (int i = 0; i < candles.size(); i += hours) {
            List<HistoricalCandle> group = candles.subList(i, Math.min(i + hours, candles.size()));

            if (group.isEmpty()) continue;

            // OHLC aggregation
            double open = group.get(0).getOpen();
            double high = group.stream().mapToDouble(HistoricalCandle::getHigh).max().orElse(0.0);
            double low = group.stream().mapToDouble(HistoricalCandle::getLow).min().orElse(0.0);
            double close = group.get(group.size() - 1).getClose();
            double volume = group.stream().mapToDouble(HistoricalCandle::getVolume).sum();

            HistoricalCandle resampledCandle = HistoricalCandle.builder()
                    .timestamp(group.get(0).getTimestamp())
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build();

            resampled.add(resampledCandle);
        }

        return resampled;
    }

    private RiskAnalysis analyzeRisk(List<Double> highs4h, List<Double> lows4h,
                                     List<Double> closes4h, List<Double> highs15m,
                                     List<Double> lows15m, List<Double> closes15m,
                                     double currentPrice) {
        double[] srLevels = indicatorUtil.calculateSupportResistance(highs4h, lows4h, closes4h);
        double nearestSupport = srLevels[0];
        double nearestResistance = srLevels[1];

        double distanceFromSupport = (currentPrice - nearestSupport) / currentPrice * 100;
        double distanceFromResistance = (nearestResistance - currentPrice) / currentPrice * 100;

        double atr = indicatorUtil.calculateATR(highs4h, lows4h, closes4h, 14);
        double volatilityPercent = (atr / currentPrice) * 100;

        double atr15m = indicatorUtil.calculateATR(highs15m, lows15m, closes15m, 14);
        double shortTermVolatility = (atr15m / currentPrice) * 100;

        boolean goodVolatility = volatilityPercent > 1.0 && volatilityPercent < 12.0;
        boolean goodShortTermVolatility = shortTermVolatility > 0.5 && shortTermVolatility < 8.0;

        boolean nearSupport = distanceFromSupport < 2.5;
        boolean nearResistance = distanceFromResistance < 2.5;

        double riskRewardRatio = 0.0;
        if (distanceFromSupport > 0.8) {
            riskRewardRatio = distanceFromResistance / distanceFromSupport;
        }

        boolean optimalRiskReward = riskRewardRatio >= RISK_REWARD_RATIO;
        boolean highRisk = volatilityPercent > 15.0 || (nearSupport && nearResistance);

        double stopLossLevel = currentPrice - (atr * 1.5);
        double takeProfitLevel = currentPrice + (atr * 3.0);

        return RiskAnalysis.builder()
                .nearestSupport(nearestSupport)
                .nearestResistance(nearestResistance)
                .distanceFromSupport(distanceFromSupport)
                .distanceFromResistance(distanceFromResistance)
                .riskRewardRatio(riskRewardRatio)
                .atr(atr)
                .volatilityPercent(volatilityPercent)
                .shortTermVolatility(shortTermVolatility)
                .goodVolatility(goodVolatility)
                .goodShortTermVolatility(goodShortTermVolatility)
                .nearSupport(nearSupport)
                .nearResistance(nearResistance)
                .highRisk(highRisk)
                .optimalRiskReward(optimalRiskReward)
                .stopLossLevel(stopLossLevel)
                .takeProfitLevel(takeProfitLevel)
                .build();
    }

    private StructureAnalysis analyzeMarketStructure(List<Double> highs4h, List<Double> lows4h,
                                                     List<Double> closes4h) {
        boolean higherHighs = indicatorUtil.isHigherHighsPattern(highs4h, 8);
        boolean lowerLows = indicatorUtil.isLowerLowsPattern(lows4h, 8);
        boolean higherLows = indicatorUtil.isHigherLowsPattern(lows4h, 8);
        boolean lowerHighs = indicatorUtil.isLowerHighsPattern(highs4h, 8);

        boolean bullishPattern = higherHighs && higherLows;
        boolean bearishPattern = lowerLows && lowerHighs;
        boolean consolidation = !higherHighs && !lowerLows && !higherLows && !lowerHighs;

        String marketStructure = "SIDEWAYS";
        if (bullishPattern) {
            marketStructure = "UPTREND";
        } else if (bearishPattern) {
            marketStructure = "DOWNTREND";
        }

        double structureStrength = 0.0;
        if (bullishPattern) structureStrength = (higherHighs ? 1.0 : 0.0) + (higherLows ? 1.0 : 0.0);
        if (bearishPattern) structureStrength = (lowerLows ? 1.0 : 0.0) + (lowerHighs ? 1.0 : 0.0);

        boolean breakoutPattern = indicatorUtil.isBreakoutPattern(highs4h, lows4h, closes4h);
        boolean reversalPattern = indicatorUtil.isReversalPattern(highs4h, lows4h, closes4h);
        boolean structureBreak = indicatorUtil.isStructureBreak(highs4h, lows4h, closes4h);

        return StructureAnalysis.builder()
                .higherHighs(higherHighs)
                .lowerLows(lowerLows)
                .higherLows(higherLows)
                .lowerHighs(lowerHighs)
                .bullishPattern(bullishPattern)
                .bearishPattern(bearishPattern)
                .consolidation(consolidation)
                .breakoutPattern(breakoutPattern)
                .reversalPattern(reversalPattern)
                .marketStructure(marketStructure)
                .structureStrength(structureStrength)
                .structureBreak(structureBreak)
                .build();
    }

    private TrendAnalysis analyzeTrends(List<Double> closes4h, List<Double> closesDaily,
                                        List<Double> closesHourly, double currentPrice) {
        double ema20_4h = indicatorUtil.EMA(closes4h, 20);
        double ema50_4h = indicatorUtil.EMA(closes4h, 50);
        double ema200_daily = indicatorUtil.EMA(closesDaily, 200);
        double ema10_1h = indicatorUtil.EMA(closesHourly, 10);
        double ema20_1h = indicatorUtil.EMA(closesHourly, 20);

        String primaryTrend = "NEUTRAL";
        String shortTermTrend = "NEUTRAL";

        if (currentPrice > ema200_daily * 0.98 && ema20_4h > ema50_4h * 0.995) {
            primaryTrend = "BULLISH";
        } else if (currentPrice < ema200_daily * 1.02 && ema20_4h < ema50_4h * 1.005) {
            primaryTrend = "BEARISH";
        }

        if (ema10_1h > ema20_1h && currentPrice > ema10_1h * 0.99) {
            shortTermTrend = "BULLISH";
        } else if (ema10_1h < ema20_1h && currentPrice < ema10_1h * 1.01) {
            shortTermTrend = "BEARISH";
        }

        boolean trendAlignment = primaryTrend.equals(shortTermTrend) && !primaryTrend.equals("NEUTRAL");

        double trendStrength = 0.0;
        if (primaryTrend.equals("BULLISH")) {
            trendStrength = (currentPrice - ema200_daily) / ema200_daily * 100;
        } else if (primaryTrend.equals("BEARISH")) {
            trendStrength = (ema200_daily - currentPrice) / ema200_daily * 100;
        }

        return TrendAnalysis.builder()
                .primaryTrend(primaryTrend)
                .shortTermTrend(shortTermTrend)
                .trendAlignment(trendAlignment)
                .trendStrength(trendStrength)
                .ema20_4h(ema20_4h)
                .ema50_4h(ema50_4h)
                .ema200_daily(ema200_daily)
                .build();
    }

    private MomentumAnalysis analyzeMomentum(List<Double> closes4h) {
        double rsi = indicatorUtil.RSI(closes4h, 14);
        double[] macd = indicatorUtil.MACD(closes4h, 12, 26, 9);
        double macdLine = macd[0];
        double macdSignal = macd[1];
        double macdHist = macd[2];

        boolean macdBullish = macdLine > macdSignal && macdHist > 0;
        boolean macdBearish = macdLine < macdSignal && macdHist < 0;

        boolean rsiBullishZone = rsi > 30 && rsi < 75;
        boolean rsiBearishZone = rsi > 25 && rsi < 70;
        boolean rsiOversold = rsi < 30;
        boolean rsiOverbought = rsi > 70;

        List<Double> recentCloses = closes4h.subList(closes4h.size() - 5, closes4h.size());
        double rsi5PeriodsAgo = indicatorUtil.RSI(recentCloses.subList(0, 4), 14);
        boolean rsiRising = rsi > rsi5PeriodsAgo;

        return MomentumAnalysis.builder()
                .rsi(rsi)
                .macdBullish(macdBullish)
                .macdBearish(macdBearish)
                .rsiBullishZone(rsiBullishZone)
                .rsiBearishZone(rsiBearishZone)
                .rsiOversold(rsiOversold)
                .rsiOverbought(rsiOverbought)
                .rsiRising(rsiRising)
                .build();
    }

    private double calculateLongScore(TrendAnalysis trend, MomentumAnalysis momentum,
                                      VolumeAnalysis volume, RiskAnalysis risk,
                                      StructureAnalysis structure) {
        double score = 0.0;

        if (trend.getPrimaryTrend().equals("BULLISH")) score += 3.0;
        if (trend.getShortTermTrend().equals("BULLISH")) score += 1.8;
        if (trend.isTrendAlignment() && trend.getPrimaryTrend().equals("BULLISH")) score += 1.5;
        if (trend.getTrendStrength() > 5.0) score += 0.5;

        if (momentum.isMacdBullish() && momentum.isRsiBullishZone()) score += 2.5;
        if (momentum.isRsiOversold()) score += 1.5;
        if (momentum.isRsiOverbought()) score -= 2.0;
        if (momentum.isRsiRising() && momentum.isRsiBullishZone()) score += 0.8;

        if (structure.isHigherHighs()) score += 1.0;
        if (structure.isBullishPattern()) score += 1.0;
        if (structure.isBreakoutPattern()) score += 0.8;

        if (volume.isStrongBullishVolume()) score += 1.2;
        if (volume.isVolumeBreakout()) score += 1.0;
        if (volume.isVolumeTrendUp()) score += 0.5;
        if (volume.isVolumeMomentum()) score += 0.3;

        if (risk.getRiskRewardRatio() >= 1.5) score += 0.5;
        if (risk.isGoodVolatility() || risk.isGoodShortTermVolatility()) score += 0.5;
        if (risk.isNearResistance()) score -= 1.0;

        return score;
    }

    private double calculateShortScore(TrendAnalysis trend, MomentumAnalysis momentum,
                                       VolumeAnalysis volume, RiskAnalysis risk,
                                       StructureAnalysis structure) {
        double score = 0.0;

        if (trend.getPrimaryTrend().equals("BEARISH")) score -= 3.0;
        if (trend.getShortTermTrend().equals("BEARISH")) score -= 1.8;
        if (trend.isTrendAlignment() && trend.getPrimaryTrend().equals("BEARISH")) score -= 1.5;
        if (trend.getTrendStrength() > 5.0) score -= 0.5;

        if (momentum.isMacdBearish() && momentum.isRsiBearishZone()) score -= 2.5;
        if (momentum.isRsiOverbought()) score -= 1.5;
        if (momentum.isRsiOversold()) score += 2.0;
        if (!momentum.isRsiRising() && momentum.isRsiBearishZone()) score -= 0.8;

        if (structure.isLowerLows()) score -= 1.0;
        if (structure.isBearishPattern()) score -= 1.0;
        if (structure.isBreakoutPattern()) score -= 0.8;

        if (volume.isStrongBearishVolume()) score -= 1.2;
        if (volume.isVolumeBreakout()) score -= 1.0;
        if (!volume.isVolumeTrendUp()) score -= 0.5;
        if (volume.isVolumeMomentum()) score -= 0.3;

        if (risk.getRiskRewardRatio() >= 1.5) score -= 0.5;
        if (risk.isGoodVolatility() || risk.isGoodShortTermVolatility()) score -= 0.5;
        if (risk.isNearSupport()) score += 1.0;

        return score;
    }

    private void tradeWithRule(String message) {
        publisher.publishEvent(new TradeClosedUpdateEvent(this, message));
    }

    // Extract methods for cleaner code
    private List<Double> extractCloses(List<List<Object>> klines) {
        return klines.stream()
                .map(k -> Double.parseDouble(k.get(4).toString()))
                .toList();
    }

    private List<Double> extractHighs(List<List<Object>> klines) {
        return klines.stream()
                .map(k -> Double.parseDouble(k.get(2).toString()))
                .toList();
    }

    private List<Double> extractLows(List<List<Object>> klines) {
        return klines.stream()
                .map(k -> Double.parseDouble(k.get(3).toString()))
                .toList();
    }

    private List<Double> extractVolumes(List<List<Object>> klines) {
        return klines.stream()
                .map(k -> Double.parseDouble(k.get(5).toString()))
                .toList();
    }
}