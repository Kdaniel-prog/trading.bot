package kd.trading.bot.service.ratingProcess.algorithm;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.util.IndicatorUtil;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SwingAlgoService {

    BinanceRestClient restClient;
    IndicatorUtil indicatorUtil;

    // Constants for 3x leverage trading
    static double MIN_LONG_SCORE = 6.0;
    static double MIN_SHORT_SCORE = -6.0;
    static double RISK_REWARD_RATIO = 2.0; // 6% profit vs 3% loss

    public CoinAnalysis analyzeCoin(String symbol, double lastPrice) {
        try {
            // Get multi-timeframe data
            List<List<Object>> fourHourKlines = restClient.getKlines(symbol, "4h", 300);
            List<List<Object>> dailyKlines = restClient.getKlines(symbol, "1d", 200);
            List<List<Object>> hourlyKlines = restClient.getKlines(symbol, "1h", 100);

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

            double currentPrice = closes4h.get(closes4h.size() - 1);

            // === TREND ANALYSIS (Multi-timeframe) ===
            TrendAnalysis trendAnalysis = analyzeTrends(closes4h, closesDaily, closesHourly, currentPrice);

            // === MOMENTUM ANALYSIS ===
            MomentumAnalysis momentumAnalysis = analyzeMomentum(closes4h);

            // === VOLUME ANALYSIS ===
            VolumeAnalysis volumeAnalysis = analyzeVolume(volumes4h);

            // === SUPPORT/RESISTANCE & RISK MANAGEMENT ===
            RiskAnalysis riskAnalysis = analyzeRisk(highs4h, lows4h, closes4h, currentPrice);

            // === MARKET STRUCTURE ===
            StructureAnalysis structureAnalysis = analyzeMarketStructure(highs4h, lows4h, closes4h);

            // === COMPREHENSIVE SCORING ===
            double longScore = calculateLongScore(trendAnalysis, momentumAnalysis,
                    volumeAnalysis, riskAnalysis, structureAnalysis);
            double shortScore = calculateShortScore(trendAnalysis, momentumAnalysis,
                    volumeAnalysis, riskAnalysis, structureAnalysis);

            // === SIGNAL DECISION ===
            Signal signal = determineSignal(longScore, shortScore, trendAnalysis,
                    momentumAnalysis, riskAnalysis);

            double finalScore = signal == Signal.LONG ? longScore :
                    signal == Signal.SHORT ? shortScore : 0.0;

            log.info("Analysis for {}: LongScore={}, ShortScore={}, Signal={}, " +
                            "Trend={}, RSI={}, RiskReward={}",
                    symbol, longScore, shortScore, signal,
                    trendAnalysis.getPrimaryTrend(), momentumAnalysis.getRsi(), riskAnalysis.getRiskRewardRatio());

            return new CoinAnalysis(symbol, finalScore, signal, lastPrice);

        } catch (Exception e) {
            log.error("Failed to analyze swing coin {}", symbol, e);
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        }
    }

    private VolumeAnalysis analyzeVolume(List<Double> volumes4h) {
        double currentVolume = volumes4h.get(volumes4h.size() - 1);
        double avgVolume20 = volumes4h.subList(volumes4h.size() - 20, volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgVolume50 = volumes4h.subList(Math.max(0, volumes4h.size() - 50), volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double volumeRatio = avgVolume20 > 0 ? currentVolume / avgVolume20 : 0.0;
        boolean strongVolume = volumeRatio > 1.5;
        boolean strongBullishVolume = volumeRatio > 2.0;
        boolean strongBearishVolume = volumeRatio > 1.8;
        boolean aboveAverageVolume = volumeRatio > 1.0;

        // Calculate volume percentile (last 50 periods)
        List<Double> recentVolumes = volumes4h.subList(Math.max(0, volumes4h.size() - 50), volumes4h.size());
        long belowCurrent = recentVolumes.stream().mapToLong(v -> v < currentVolume ? 1 : 0).sum();
        double volumePercentile = (double) belowCurrent / recentVolumes.size() * 100;

        boolean volumeBreakout = volumePercentile > 80;
        boolean volumeDrying = volumeRatio < 0.6;

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
                .build();
    }

    private RiskAnalysis analyzeRisk(List<Double> highs4h, List<Double> lows4h,
                                     List<Double> closes4h, double currentPrice) {
        double[] srLevels = indicatorUtil.calculateSupportResistance(highs4h, lows4h, closes4h);
        double nearestSupport = srLevels[0];
        double nearestResistance = srLevels[1];

        double distanceFromSupport = (currentPrice - nearestSupport) / currentPrice * 100;
        double distanceFromResistance = (nearestResistance - currentPrice) / currentPrice * 100;

        double atr = indicatorUtil.calculateATR(highs4h, lows4h, closes4h, 14);
        double volatilityPercent = (atr / currentPrice) * 100;
        boolean goodVolatility = volatilityPercent > 2.0 && volatilityPercent < 8.0;

        boolean nearSupport = distanceFromSupport < 2.0;
        boolean nearResistance = distanceFromResistance < 2.0;

        // Risk/Reward calculation for 3x leverage (6% profit target, 3% stop loss)
        double riskRewardRatio = 0.0;
        if (distanceFromSupport > 1.0) {
            riskRewardRatio = distanceFromResistance / distanceFromSupport;
        }

        boolean optimalRiskReward = riskRewardRatio >= RISK_REWARD_RATIO;
        boolean highRisk = volatilityPercent > 10.0 || nearSupport || nearResistance;

        // Stop loss and take profit levels
        double stopLossLevel = currentPrice - (atr * 1.5); // 1.5 ATR stop loss
        double takeProfitLevel = currentPrice + (atr * 3.0); // 3 ATR take profit

        return RiskAnalysis.builder()
                .nearestSupport(nearestSupport)
                .nearestResistance(nearestResistance)
                .distanceFromSupport(distanceFromSupport)
                .distanceFromResistance(distanceFromResistance)
                .riskRewardRatio(riskRewardRatio)
                .atr(atr)
                .volatilityPercent(volatilityPercent)
                .goodVolatility(goodVolatility)
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
        boolean higherHighs = indicatorUtil.isHigherHighsPattern(highs4h, 10);
        boolean lowerLows = indicatorUtil.isLowerLowsPattern(lows4h, 10);
        boolean higherLows = indicatorUtil.isHigherLowsPattern(lows4h, 10);
        boolean lowerHighs = indicatorUtil.isLowerHighsPattern(highs4h, 10);

        boolean bullishPattern = higherHighs && higherLows;
        boolean bearishPattern = lowerLows && lowerHighs;
        boolean consolidation = !higherHighs && !lowerLows && !higherLows && !lowerHighs;

        String marketStructure = "SIDEWAYS";
        if (bullishPattern) {
            marketStructure = "UPTREND";
        } else if (bearishPattern) {
            marketStructure = "DOWNTREND";
        }

        // Structure strength calculation
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

        // Primary trend (daily timeframe)
        if (currentPrice > ema200_daily && ema20_4h > ema50_4h) {
            primaryTrend = "BULLISH";
        } else if (currentPrice < ema200_daily && ema20_4h < ema50_4h) {
            primaryTrend = "BEARISH";
        }

        // Short-term trend (hourly)
        if (ema10_1h > ema20_1h && currentPrice > ema10_1h) {
            shortTermTrend = "BULLISH";
        } else if (ema10_1h < ema20_1h && currentPrice < ema10_1h) {
            shortTermTrend = "BEARISH";
        }

        boolean trendAlignment = primaryTrend.equals(shortTermTrend) && !primaryTrend.equals("NEUTRAL");

        return TrendAnalysis.builder()
                .primaryTrend(primaryTrend)
                .shortTermTrend(shortTermTrend)
                .trendAlignment(trendAlignment)
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

        // RSI levels optimized for swing trading with 3x leverage
        boolean rsiBullishZone = rsi > 40 && rsi < 70; // Not oversold/overbought
        boolean rsiBearishZone = rsi > 30 && rsi < 60;
        boolean rsiOversold = rsi < 35;
        boolean rsiOverbought = rsi > 65;

        return MomentumAnalysis.builder()
                .rsi(rsi)
                .macdBullish(macdBullish)
                .macdBearish(macdBearish)
                .rsiBullishZone(rsiBullishZone)
                .rsiBearishZone(rsiBearishZone)
                .rsiOversold(rsiOversold)
                .rsiOverbought(rsiOverbought)
                .build();
    }

    private double calculateLongScore(TrendAnalysis trend, MomentumAnalysis momentum,
                                      VolumeAnalysis volume, RiskAnalysis risk,
                                      StructureAnalysis structure) {
        double score = 0.0;

        // Trend factors (40% weight)
        if (trend.getPrimaryTrend().equals("BULLISH")) score += 4.0;
        if (trend.getShortTermTrend().equals("BULLISH")) score += 2.0;
        if (trend.isTrendAlignment() && trend.getPrimaryTrend().equals("BULLISH")) score += 2.0;

        // Momentum factors (30% weight)
        if (momentum.isMacdBullish() && momentum.isRsiBullishZone()) score += 3.0;
        if (momentum.isRsiOversold()) score += 2.0; // Bounce opportunity
        if (momentum.isRsiOverbought()) score -= 3.0; // Avoid overbought

        // Structure factors (15% weight)
        if (structure.isHigherHighs()) score += 1.5;
        if (structure.isBullishPattern()) score += 1.0;

        // Volume confirmation (10% weight)
        if (volume.isStrongBullishVolume()) score += 1.0;

        // Risk management (5% weight)
        if (risk.getRiskRewardRatio() >= RISK_REWARD_RATIO) score += 1.0;
        if (risk.isNearResistance()) score -= 2.0;
        if (risk.isGoodVolatility()) score += 0.5;

        return score;
    }

    private double calculateShortScore(TrendAnalysis trend, MomentumAnalysis momentum,
                                       VolumeAnalysis volume, RiskAnalysis risk,
                                       StructureAnalysis structure) {
        double score = 0.0;

        // Trend factors (40% weight)
        if (trend.getPrimaryTrend().equals("BEARISH")) score -= 4.0;
        if (trend.getShortTermTrend().equals("BEARISH")) score -= 2.0;
        if (trend.isTrendAlignment() && trend.getPrimaryTrend().equals("BEARISH")) score -= 2.0;

        // Momentum factors (30% weight)
        if (momentum.isMacdBearish() && momentum.isRsiBearishZone()) score -= 3.0;
        if (momentum.isRsiOverbought()) score -= 2.0; // Reversal opportunity
        if (momentum.isRsiOversold()) score += 3.0; // Avoid oversold bounce

        // Structure factors (15% weight)
        if (structure.isLowerLows()) score -= 1.5;
        if (structure.isBearishPattern()) score -= 1.0;

        // Volume confirmation (10% weight)
        if (volume.isStrongBearishVolume()) score -= 1.0;

        // Risk management (5% weight)
        if (risk.getRiskRewardRatio() >= RISK_REWARD_RATIO) score -= 1.0;
        if (risk.isNearSupport()) score += 2.0;
        if (risk.isGoodVolatility()) score -= 0.5;

        return score;
    }

    private Signal determineSignal(double longScore, double shortScore,
                                   TrendAnalysis trend, MomentumAnalysis momentum,
                                   RiskAnalysis risk) {

        // Safety filters - avoid extreme market conditions
        if (!risk.isGoodVolatility() || risk.getRiskRewardRatio() < 1.5) {
            return Signal.NO_TRADE;
        }

        // Avoid trading during momentum extremes
        if (momentum.isRsiOverbought() && momentum.isRsiOversold()) {
            return Signal.NO_TRADE;
        }

        // Long signal conditions
        if (longScore >= MIN_LONG_SCORE &&
                trend.getPrimaryTrend().equals("BULLISH") &&
                momentum.isMacdBullish() &&
                !risk.isNearResistance()) {
            return Signal.LONG;
        }

        // Short signal conditions
        if (shortScore <= MIN_SHORT_SCORE &&
                trend.getPrimaryTrend().equals("BEARISH") &&
                momentum.isMacdBearish() &&
                !risk.isNearSupport()) {
            return Signal.SHORT;
        }

        // Counter-trend opportunities (recovery trades)
        // Long on oversold in strong uptrend
        if (longScore >= 4.0 &&
                trend.getPrimaryTrend().equals("BULLISH") &&
                momentum.isRsiOversold() &&
                risk.getRiskRewardRatio() >= 2.5) {
            return Signal.LONG;
        }

        // Short on overbought in strong downtrend
        if (shortScore <= -4.0 &&
                trend.getPrimaryTrend().equals("BEARISH") &&
                momentum.isRsiOverbought() &&
                risk.getRiskRewardRatio() >= 2.5) {
            return Signal.SHORT;
        }

        return Signal.NO_TRADE;
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
