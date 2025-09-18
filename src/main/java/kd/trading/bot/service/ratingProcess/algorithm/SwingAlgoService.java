package kd.trading.bot.service.ratingProcess.algorithm;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import kd.trading.bot.util.IndicatorUtil;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    ApplicationEventPublisher publisher;

    // OPTIMALIZÁLT Constants for 3x leverage trading
    static double MIN_LONG_SCORE = 4.0;   // 6.0 → 4.0 (Több signal)
    static double MIN_SHORT_SCORE = -4.0; // -6.0 → -4.0
    static double RISK_REWARD_RATIO = 1.5; // 2.0 → 1.5 (Lazább követelmény)

    public CoinAnalysis analyzeCoin(String symbol, double lastPrice) {
        try {
            // Get multi-timeframe data + 15m for volatility
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

            // ÚJ: 15m data a gyorsabb volatilitás méréshez
            List<Double> closes15m = extractCloses(fifteenMinKlines);
            List<Double> highs15m = extractHighs(fifteenMinKlines);
            List<Double> lows15m = extractLows(fifteenMinKlines);

            double currentPrice = closes4h.get(closes4h.size() - 1);

            // === TREND ANALYSIS (Multi-timeframe) ===
            TrendAnalysis trendAnalysis = analyzeTrends(closes4h, closesDaily, closesHourly, currentPrice);

            // === MOMENTUM ANALYSIS ===
            MomentumAnalysis momentumAnalysis = analyzeMomentum(closes4h);

            // === VOLUME ANALYSIS ===
            VolumeAnalysis volumeAnalysis = analyzeVolume(volumes4h);

            // === SUPPORT/RESISTANCE & RISK MANAGEMENT ===
            RiskAnalysis riskAnalysis = analyzeRisk(highs4h, lows4h, closes4h,
                    highs15m, lows15m, closes15m, currentPrice);

            // === MARKET STRUCTURE ===
            StructureAnalysis structureAnalysis = analyzeMarketStructure(highs4h, lows4h, closes4h);

            // === COMPREHENSIVE SCORING ===
            double longScore = calculateLongScore(trendAnalysis, momentumAnalysis,
                    volumeAnalysis, riskAnalysis, structureAnalysis);
            double shortScore = calculateShortScore(trendAnalysis, momentumAnalysis,
                    volumeAnalysis, riskAnalysis, structureAnalysis);

            // === SIGNAL DECISION ===
            Signal signal = determineSignal(longScore, shortScore, trendAnalysis,
                    momentumAnalysis, riskAnalysis, volumeAnalysis);

            double finalScore = signal == Signal.LONG ? longScore :
                    signal == Signal.SHORT ? shortScore : 0.0;

            log.info("Analysis for {}: LongScore={}, ShortScore={}, Signal={}, " +
                            "Trend={}, RSI={}, RiskReward={}, Vol15m={} Rule={}",
                    symbol, longScore, shortScore, signal,
                    trendAnalysis.getPrimaryTrend(), momentumAnalysis.getRsi(),
                    riskAnalysis.getRiskRewardRatio(),
                    riskAnalysis.getShortTermVolatility(), trendAnalysis.getRule());
            if (trendAnalysis.getRule() > 0){
                String logMessage = String.format("Analysis for %s: LongScore=%s, ShortScore=%s, Signal=%s, " +
                                "Trend=%s, RSI=%s, RiskReward=%s, Vol15m=%s Rule=%s",
                        symbol, longScore, shortScore, signal,
                        trendAnalysis.getPrimaryTrend(), momentumAnalysis.getRsi(),
                        riskAnalysis.getRiskRewardRatio(),
                        riskAnalysis.getShortTermVolatility(), trendAnalysis.getRule());
                tradeWithRule(logMessage);
            }
            return new CoinAnalysis(symbol, finalScore, signal, lastPrice);

        } catch (Exception e) {
            log.error("Failed to analyze swing coin {}", symbol, e);
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        }
    }

    private void tradeWithRule(String message) {
        publisher.publishEvent(new TradeClosedUpdateEvent(this, message));
    }

    private VolumeAnalysis analyzeVolume(List<Double> volumes4h) {
        double currentVolume = volumes4h.get(volumes4h.size() - 1);
        double avgVolume20 = volumes4h.subList(volumes4h.size() - 20, volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgVolume50 = volumes4h.subList(Math.max(0, volumes4h.size() - 50), volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double volumeRatio = avgVolume20 > 0 ? currentVolume / avgVolume20 : 0.0;
        boolean strongVolume = volumeRatio > 1.3; // 1.5 → 1.3 (Lazább)
        boolean strongBullishVolume = volumeRatio > 1.8; // 2.0 → 1.8
        boolean strongBearishVolume = volumeRatio > 1.5; // 1.8 → 1.5
        boolean aboveAverageVolume = volumeRatio > 0.8; // 1.0 → 0.8

        // Calculate volume percentile (last 50 periods)
        List<Double> recentVolumes = volumes4h.subList(Math.max(0, volumes4h.size() - 50), volumes4h.size());
        long belowCurrent = recentVolumes.stream().mapToLong(v -> v < currentVolume ? 1 : 0).sum();
        double volumePercentile = (double) belowCurrent / recentVolumes.size() * 100;

        boolean volumeBreakout = volumePercentile > 75; // 80 → 75
        boolean volumeDrying = volumeRatio < 0.7; // 0.6 → 0.7

        // ÚJ: Volume trend az utolsó 5 gyertyán
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
                .volumeTrendUp(volumeTrendUp) // ÚJ
                .volumeMomentum(volumeMomentum) // ÚJ
                .build();
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

        // 4h ATR
        double atr = indicatorUtil.calculateATR(highs4h, lows4h, closes4h, 14);
        double volatilityPercent = (atr / currentPrice) * 100;

        // ÚJ: 15m ATR gyorsabb volatilitás méréshez
        double atr15m = indicatorUtil.calculateATR(highs15m, lows15m, closes15m, 14);
        double shortTermVolatility = (atr15m / currentPrice) * 100;

        // OPTIMALIZÁLT: Lazább volatilitás küszöbök
        boolean goodVolatility = volatilityPercent > 1.0 && volatilityPercent < 12.0; // 2.0-8.0 → 1.0-12.0
        boolean goodShortTermVolatility = shortTermVolatility > 0.5 && shortTermVolatility < 8.0;

        boolean nearSupport = distanceFromSupport < 2.5; // 2.0 → 2.5
        boolean nearResistance = distanceFromResistance < 2.5; // 2.0 → 2.5

        // Risk/Reward calculation for 3x leverage
        double riskRewardRatio = 0.0;
        if (distanceFromSupport > 0.8) { // 1.0 → 0.8 (Lazább)
            riskRewardRatio = distanceFromResistance / distanceFromSupport;
        }

        boolean optimalRiskReward = riskRewardRatio >= RISK_REWARD_RATIO;
        boolean highRisk = volatilityPercent > 15.0 || (nearSupport && nearResistance); // 10.0 → 15.0

        // Stop loss and take profit levels
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
                .shortTermVolatility(shortTermVolatility) // ÚJ
                .goodVolatility(goodVolatility)
                .goodShortTermVolatility(goodShortTermVolatility) // ÚJ
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
        // OPTIMALIZÁLT: Rövidebb lookback period gyorsabb reagáláshoz
        boolean higherHighs = indicatorUtil.isHigherHighsPattern(highs4h, 8); // 10 → 8
        boolean lowerLows = indicatorUtil.isLowerLowsPattern(lows4h, 8); // 10 → 8
        boolean higherLows = indicatorUtil.isHigherLowsPattern(lows4h, 8); // 10 → 8
        boolean lowerHighs = indicatorUtil.isLowerHighsPattern(highs4h, 8); // 10 → 8

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

        // OPTIMALIZÁLT: Lazább trend meghatározás
        if (currentPrice > ema200_daily * 0.98 && ema20_4h > ema50_4h * 0.995) { // Kis tolerancia
            primaryTrend = "BULLISH";
        } else if (currentPrice < ema200_daily * 1.02 && ema20_4h < ema50_4h * 1.005) {
            primaryTrend = "BEARISH";
        }

        // Short-term trend (hourly)
        if (ema10_1h > ema20_1h && currentPrice > ema10_1h * 0.99) { // Kis tolerancia
            shortTermTrend = "BULLISH";
        } else if (ema10_1h < ema20_1h && currentPrice < ema10_1h * 1.01) {
            shortTermTrend = "BEARISH";
        }

        boolean trendAlignment = primaryTrend.equals(shortTermTrend) && !primaryTrend.equals("NEUTRAL");

        // ÚJ: Trend erősség kalkuláció
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
                .trendStrength(trendStrength) // ÚJ
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

        // OPTIMALIZÁLT: Szélesebb RSI zónák több signal-ért
        boolean rsiBullishZone = rsi > 30 && rsi < 75; // 40-70 → 30-75
        boolean rsiBearishZone = rsi > 25 && rsi < 70; // 30-60 → 25-70
        boolean rsiOversold = rsi < 30; // 35 → 30
        boolean rsiOverbought = rsi > 70; // 65 → 70

        // ÚJ: RSI momentum (változás irány)
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
                .rsiRising(rsiRising) // ÚJ
                .build();
    }

    private double calculateLongScore(TrendAnalysis trend, MomentumAnalysis momentum,
                                      VolumeAnalysis volume, RiskAnalysis risk,
                                      StructureAnalysis structure) {
        double score = 0.0;

        // OPTIMALIZÁLT: Trend factors (35% weight, csökkentve 40%-ról)
        if (trend.getPrimaryTrend().equals("BULLISH")) score += 3.0; // 4.0 → 3.0
        if (trend.getShortTermTrend().equals("BULLISH")) score += 1.8; // 2.0 → 1.8
        if (trend.isTrendAlignment() && trend.getPrimaryTrend().equals("BULLISH")) score += 1.5; // 2.0 → 1.5
        if (trend.getTrendStrength() > 5.0) score += 0.5; // ÚJ: Erős trend bonus

        // OPTIMALIZÁLT: Momentum factors (35% weight, növelve 30%-ról)
        if (momentum.isMacdBullish() && momentum.isRsiBullishZone()) score += 2.5; // 3.0 → 2.5
        if (momentum.isRsiOversold()) score += 1.5; // 2.0 → 1.5
        if (momentum.isRsiOverbought()) score -= 2.0; // 3.0 → 2.0
        if (momentum.isRsiRising() && momentum.isRsiBullishZone()) score += 0.8; // ÚJ

        // OPTIMALIZÁLT: Structure factors (15% weight)
        if (structure.isHigherHighs()) score += 1.0; // 1.5 → 1.0
        if (structure.isBullishPattern()) score += 1.0;
        if (structure.isBreakoutPattern()) score += 0.8; // ÚJ

        // OPTIMALIZÁLT: Volume confirmation (15% weight, növelve 10%-ről)
        if (volume.isStrongBullishVolume()) score += 1.2; // 1.0 → 1.2
        if (volume.isVolumeBreakout()) score += 1.0; // ÚJ
        if (volume.isVolumeTrendUp()) score += 0.5; // ÚJ
        if (volume.isVolumeMomentum()) score += 0.3; // ÚJ

        // Risk management - kevésbé szigorú
        if (risk.getRiskRewardRatio() >= 1.5) score += 0.5; // RISK_REWARD_RATIO használat
        if (risk.isGoodVolatility() || risk.isGoodShortTermVolatility()) score += 0.5;
        if (risk.isNearResistance()) score -= 1.0; // 2.0 → 1.0 (Kevésbé büntető)

        return score;
    }

    private double calculateShortScore(TrendAnalysis trend, MomentumAnalysis momentum,
                                       VolumeAnalysis volume, RiskAnalysis risk,
                                       StructureAnalysis structure) {
        double score = 0.0;

        // OPTIMALIZÁLT: Trend factors (35% weight)
        if (trend.getPrimaryTrend().equals("BEARISH")) score -= 3.0; // 4.0 → 3.0
        if (trend.getShortTermTrend().equals("BEARISH")) score -= 1.8; // 2.0 → 1.8
        if (trend.isTrendAlignment() && trend.getPrimaryTrend().equals("BEARISH")) score -= 1.5; // 2.0 → 1.5
        if (trend.getTrendStrength() > 5.0) score -= 0.5; // ÚJ: Erős trend bonus

        // OPTIMALIZÁLT: Momentum factors (35% weight)
        if (momentum.isMacdBearish() && momentum.isRsiBearishZone()) score -= 2.5; // 3.0 → 2.5
        if (momentum.isRsiOverbought()) score -= 1.5; // 2.0 → 1.5
        if (momentum.isRsiOversold()) score += 2.0; // 3.0 → 2.0
        if (!momentum.isRsiRising() && momentum.isRsiBearishZone()) score -= 0.8; // ÚJ

        // OPTIMALIZÁLT: Structure factors (15% weight)
        if (structure.isLowerLows()) score -= 1.0; // 1.5 → 1.0
        if (structure.isBearishPattern()) score -= 1.0;
        if (structure.isBreakoutPattern()) score -= 0.8; // ÚJ

        // OPTIMALIZÁLT: Volume confirmation (15% weight)
        if (volume.isStrongBearishVolume()) score -= 1.2; // 1.0 → 1.2
        if (volume.isVolumeBreakout()) score -= 1.0; // ÚJ
        if (!volume.isVolumeTrendUp()) score -= 0.5; // ÚJ
        if (volume.isVolumeMomentum()) score -= 0.3; // ÚJ

        // Risk management - kevésbé szigorú
        if (risk.getRiskRewardRatio() >= 1.5) score -= 0.5; // RISK_REWARD_RATIO használat
        if (risk.isGoodVolatility() || risk.isGoodShortTermVolatility()) score -= 0.5;
        if (risk.isNearSupport()) score += 1.0; // 2.0 → 1.0 (Kevésbé büntető)

        return score;
    }

    private Signal determineSignal(double longScore, double shortScore,
                                   TrendAnalysis trend, MomentumAnalysis momentum,
                                   RiskAnalysis risk, VolumeAnalysis volume) {

        trend.setRule(0);
        // OPTIMALIZÁLT: Lazább safety filters
        if (risk.getRiskRewardRatio() < 1.2) { // 1.5 → 1.2
            return Signal.NO_TRADE;
        }

        // ELTÁVOLÍTVA: Volatilitás szűrő (túl szigorú volt)
        // if (!risk.isGoodVolatility()) return Signal.NO_TRADE;

        // OPTIMALIZÁLT: Enyhébb long feltételek
        if (longScore >= 6.0 &&
                (trend.getPrimaryTrend().equals("BULLISH") ||
                        trend.getShortTermTrend().equals("BULLISH")) && // OR helyett AND
                momentum.isMacdBullish()) { // resistance check eltávolítva
            trend.setRule(1);
            return Signal.LONG;
        }

        // OPTIMALIZÁLT: Enyhébb short feltételek
        if (shortScore <= -9.0 &&
                (trend.getPrimaryTrend().equals("BEARISH") ||
                        trend.getShortTermTrend().equals("BEARISH")) && // OR helyett AND
                momentum.isMacdBearish()) { // support check eltávolítva
            trend.setRule(2);
            return Signal.LONG;
        }

        // ÚJ: Momentum-based belépés (gyorsabb reagálás)
        if (longScore >= 3.0 &&
                momentum.isRsiBullishZone() &&
                momentum.isMacdBullish() &&
                momentum.isRsiRising() &&
                risk.getRiskRewardRatio() >= 1.3) {
            trend.setRule(3);
            return Signal.SHORT;
        }

        //ez jó
        if (shortScore <= -5.0 &&
                momentum.isRsiBearishZone() &&
                momentum.isMacdBearish() &&
                !momentum.isRsiRising() &&
                risk.getRiskRewardRatio() >= 1.3) {
            trend.setRule(4);
            return Signal.SHORT;
        }

        //vagy ez
        // ÚJ: Volume breakout alapú belépés
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
            return Signal.LONG;
        }

        // Counter-trend opportunities (recovery trades) - MEGTARTVA
        if (longScore >= 4.0 &&
                trend.getPrimaryTrend().equals("BULLISH") &&
                momentum.isRsiOversold() &&
                risk.getRiskRewardRatio() >= 2.0) { // 2.5 → 2.0
            trend.setRule(7);
            return Signal.SHORT;
        }

        if (shortScore <= -4.0 &&
                trend.getPrimaryTrend().equals("BEARISH") &&
                momentum.isRsiOverbought() &&
                risk.getRiskRewardRatio() >= 2.0) { // 2.5 → 2.0
            trend.setRule(8);
            return Signal.LONG;
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