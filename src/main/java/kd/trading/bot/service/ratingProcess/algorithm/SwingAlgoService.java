package kd.trading.bot.service.ratingProcess.algorithm;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.enums.Direction;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.service.ml.PythonMLService;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import kd.trading.bot.util.IndicatorUtil;
import lombok.AccessLevel;
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

    // ML Configuration
    @Value("${ml.enabled:true}")
    private boolean useMlPredictions;

    @Value("${ml.confidence.threshold:0.6}")
    private double mlConfidenceThreshold;

    // Trading config
    @Value("${trading.moneyUsdt:10000}")
    private double accountBalance;

    @Value("${trading.leverage:1}")
    private double maxLeverage;

    @Value("${trading.stopLimit:2.0}")
    private double stopLossPercent;

    @Value("${trading.winLimit:3.0}")
    private double takeProfitPercent;

    /**
     * Main analysis method - CSAK KALKULÁL, nem dönt!
     * A trading döntést a Python ML hozza meg
     */
    public CoinAnalysis analyzeCoin(String symbol, double lastPrice) {
        try {
            // Get multi-timeframe data
            List<List<Object>> fourHourKlines = restClient.getKlines(symbol, "4h", 300);
            List<List<Object>> dailyKlines = restClient.getKlines(symbol, "1d", 200);
            List<List<Object>> hourlyKlines = restClient.getKlines(symbol, "1h", 100);
            List<List<Object>> fifteenMinKlines = restClient.getKlines(symbol, "15m", 50);

            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                return createNoTradeAnalysis(symbol, lastPrice, "Insufficient data");
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

            // === PURE TECHNICAL ANALYSIS - NO TRADING DECISIONS ===
            TechnicalIndicators indicators = calculateAllIndicators(
                    closes4h, highs4h, lows4h, volumes4h,
                    closesDaily, closesHourly, closes15m, highs15m, lows15m, currentPrice
            );

            // Create comprehensive analysis object
            CoinAnalysis analysis = new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
            analysis.setTechnicalIndicators(indicators);

            return analysis;

        } catch (Exception e) {
            log.error("Failed to analyze coin {}: {}", symbol, e.getMessage(), e);
            return createNoTradeAnalysis(symbol, lastPrice, "Analysis failed: " + e.getMessage());
        }
    }

    /**
     * Generate directional signals for backtesting/training
     */
    private Direction generateBacktestDirectionalSignal(TechnicalIndicators indicators) {
        double longScore = 0.0;
        double shortScore = 0.0;
        double holdScore = 0.5; // Bias towards HOLD for conservative approach
        double threshold = 2.5; // REDUCED from 3.5

        // === TREND ANALYSIS ===
        if ("BULLISH".equals(indicators.getPrimaryTrend())) {
            longScore += 2.5;
            if ("BULLISH".equals(indicators.getShortTermTrend())) {
                longScore += 1.5; // Trend alignment
            }
        } else if ("BEARISH".equals(indicators.getPrimaryTrend())) {
            shortScore += 2.5;
            if ("BEARISH".equals(indicators.getShortTermTrend())) {
                shortScore += 1.5; // Trend alignment
            }
        }

        // === MOMENTUM ANALYSIS ===
        double rsi = indicators.getRsi();

        // RSI oversold/overbought conditions
        if (rsi < 35 && rsi > 25) {
            longScore += 2.0; // Potential bounce
        } else if (rsi > 65 && rsi < 75) {
            shortScore += 2.0; // Potential reversal
        }

        // RSI trend
        if (indicators.isRsiRising() && rsi > 40) {
            longScore += 1.0;
        } else if (!indicators.isRsiRising() && rsi < 60) {
            shortScore += 1.0;
        }

        // MACD signals
        if (indicators.isMacdBullish() && indicators.getMacdHistogram() > 0) {
            longScore += 1.5;
        } else if (indicators.isMacdBearish() && indicators.getMacdHistogram() < 0) {
            shortScore += 1.5;
        }

        // === VOLUME CONFIRMATION ===
        if (indicators.getVolumeRatio() > 1.4) {
            // Strong volume supports both directions
            longScore += 1.0;
            shortScore += 1.0;
        } else if (indicators.getVolumeRatio() < 0.8) {
            // Low volume - prefer HOLD
            holdScore += 1.0;
        }

        // === MARKET STRUCTURE ===
        if (indicators.isBullishStructure()) {
            longScore += 1.5;
        } else if (indicators.isBearishStructure()) {
            shortScore += 1.5;
        } else if (indicators.isConsolidation()) {
            holdScore += 1.5;
        }

        // === RISK/REWARD ANALYSIS ===
        if (indicators.getRiskRewardRatio() < 1.2) {
            holdScore += 1.0; // Poor risk/reward
        } else if (indicators.getRiskRewardRatio() > 2.0) {
            // Good risk/reward supports current trend
            if (longScore > shortScore) {
                longScore += 1.0;
            } else if (shortScore > longScore) {
                shortScore += 1.0;
            }
        }

        // === VOLATILITY FILTER ===
        if (indicators.getVolatilityPercent() > 8.0) {
            holdScore += 0.5; // High volatility - be cautious
        }

        // === DECISION LOGIC ===
        double maxScore = Math.max(Math.max(longScore, shortScore), holdScore);

        // Require clear winner with sufficient confidence
        if (maxScore < threshold) {
            return Direction.HOLD;
        }

        // Make it easier to generate LONG/SHORT signals
        if (longScore >= 2.5 && longScore > shortScore + 0.3) {
            return Direction.LONG;
        } else if (shortScore >= 2.5 && shortScore > longScore + 0.3) {
            return Direction.SHORT;
        } else {
            return Direction.HOLD;
        }
    }

    /**
     * Generate synthetic probabilities for training data
     */
    private Map<String, Double> generateSyntheticProbabilities(Direction direction, double score) {
        Map<String, Double> probabilities = new HashMap<>();

        // Convert score (0-10) to confidence (0.5-0.95)
        double confidence = Math.min(0.95, 0.5 + (score / 10.0) * 0.45);

        if (direction == Direction.LONG) {
            probabilities.put("LONG", confidence);
            probabilities.put("SHORT", (1.0 - confidence) * 0.3);
            probabilities.put("HOLD", (1.0 - confidence) * 0.7);
        } else if (direction == Direction.SHORT) {
            probabilities.put("SHORT", confidence);
            probabilities.put("LONG", (1.0 - confidence) * 0.3);
            probabilities.put("HOLD", (1.0 - confidence) * 0.7);
        } else {
            probabilities.put("HOLD", 0.7);
            probabilities.put("LONG", 0.15);
            probabilities.put("SHORT", 0.15);
        }

        return probabilities;
    }

    /**
     * Calculate score for directional signal
     */
    private double calculateDirectionalSignalScore(TechnicalIndicators indicators, Direction direction) {
        if (direction == Direction.HOLD) {
            return 0.0;
        }

        double score = 5.0; // Base score

        if (direction == Direction.LONG) {
            // Long-specific scoring
            if ("BULLISH".equals(indicators.getPrimaryTrend())) score += 2.0;
            if ("BULLISH".equals(indicators.getShortTermTrend())) score += 1.0;
            if (indicators.isTrendAlignment()) score += 1.0;

            if (indicators.getRsi() > 30 && indicators.getRsi() < 70) score += 1.0;
            if (indicators.isMacdBullish()) score += 1.5;
            if (indicators.isRsiRising()) score += 0.5;

            if (indicators.getVolumeRatio() > 1.3) score += 1.0;
            if (indicators.isVolumeBreakout()) score += 0.5;

            if (indicators.getRiskRewardRatio() > 2.0) score += 1.0;
            if (indicators.getVolatilityPercent() < 8.0) score += 0.5;

        } else if (direction == Direction.SHORT) {
            // Short-specific scoring (mirror logic)
            if ("BEARISH".equals(indicators.getPrimaryTrend())) score += 2.0;
            if ("BEARISH".equals(indicators.getShortTermTrend())) score += 1.0;
            if (indicators.isTrendAlignment()) score += 1.0;

            if (indicators.getRsi() > 30 && indicators.getRsi() < 70) score += 1.0;
            if (indicators.isMacdBearish()) score += 1.5;
            if (!indicators.isRsiRising()) score += 0.5;

            if (indicators.getVolumeRatio() > 1.3) score += 1.0;
            if (indicators.isVolumeBreakout()) score += 0.5;

            if (indicators.getRiskRewardRatio() > 2.0) score += 1.0;
            if (indicators.getVolatilityPercent() < 8.0) score += 0.5;
        }

        // Cap between 0-10
        return Math.max(0.0, Math.min(10.0, score));
    }


    // JAVÍTOTT calculateAllIndicators metódus
    private TechnicalIndicators calculateAllIndicators(List<Double> closes4h, List<Double> highs4h,
                                                       List<Double> lows4h, List<Double> volumes4h,
                                                       List<Double> closesDaily, List<Double> closesHourly,
                                                       List<Double> closes15m, List<Double> highs15m,
                                                       List<Double> lows15m, double currentPrice) {

        TechnicalIndicators indicators = new TechnicalIndicators();
        indicators.setCurrentPrice(currentPrice);

        // === KRITIKUS: ELLENŐRIZD AZ INPUT ADATOKAT ===
        log.debug("Input data sizes - 4h: {}, daily: {}, hourly: {}",
                closes4h.size(), closesDaily.size(), closesHourly.size());

        if (closes4h.size() < 50) {
            log.warn("Insufficient 4h data for indicators: {}", closes4h.size());
            return createDefaultIndicators(currentPrice);
        }

        try {
            // === TREND INDICATORS - VALÓDI SZÁMÍTÁSOK ===
            Double ema20_4h = calculateEMAManually(closes4h, 20);
            Double ema50_4h = calculateEMAManually(closes4h, 50);
            Double ema200_daily = closesDaily.size() >= 200 ?
                    calculateEMAManually(closesDaily, 200) : currentPrice;

            // NULL CHECK ÉS LOG
            if (ema20_4h == null || ema50_4h == null) {
                log.error("EMA calculation failed! ema20_4h={}, ema50_4h={}", ema20_4h, ema50_4h);
                return createDefaultIndicators(currentPrice);
            }

            indicators.setEma20_4h(ema20_4h);
            indicators.setEma50_4h(ema50_4h);
            indicators.setEma200_daily(ema200_daily);

            // === RSI - VALÓDI SZÁMÍTÁS ===
            Double rsi = calculateRSIManually(closes4h, 14);
            if (rsi == null || Double.isNaN(rsi)) {
                log.error("RSI calculation failed for data size: {}", closes4h.size());
                rsi = 50.0; // Csak ha tényleg fail
            }
            indicators.setRsi(rsi);

            log.info("CALCULATED RSI: {} from {} candles", rsi, closes4h.size());

            // === MACD - VALÓDI SZÁMÍTÁS ===
            MacdResult macdResult = calculateMACDManually(closes4h, 12, 26, 9);
            if (macdResult != null) {
                indicators.setMacdLine(macdResult.getMacdLine());
                indicators.setMacdSignal(macdResult.getSignalLine());
                indicators.setMacdHistogram(macdResult.getHistogram());
                indicators.setMacdBullish(macdResult.getHistogram() > 0 && macdResult.getMacdLine() > macdResult.getSignalLine());
                indicators.setMacdBearish(macdResult.getHistogram() < 0 && macdResult.getMacdLine() < macdResult.getSignalLine());
            } else {
                log.error("MACD calculation failed!");
                setDefaultMACD(indicators);
            }

            // === VOLUME - VALÓDI SZÁMÍTÁS ===
            if (volumes4h.size() >= 20) {
                double currentVolume = volumes4h.get(volumes4h.size() - 1);
                double avgVolume20 = volumes4h.subList(volumes4h.size() - 20, volumes4h.size())
                        .stream().mapToDouble(Double::doubleValue).average().orElse(1.0);

                double volumeRatio = avgVolume20 > 0 ? currentVolume / avgVolume20 : 1.0;

                indicators.setCurrentVolume(currentVolume);
                indicators.setAverageVolume20(avgVolume20);
                indicators.setVolumeRatio(volumeRatio);
                indicators.setStrongVolume(volumeRatio > 1.3);

                log.info("CALCULATED Volume: current={}, avg20={}, ratio={}",
                        currentVolume, avgVolume20, volumeRatio);
            } else {
                setDefaultVolume(indicators);
            }

            // === TREND CLASSIFICATION - VALÓDI LOGIKA ===
            String primaryTrend = calculatePrimaryTrendFixed(currentPrice, ema200_daily, ema20_4h, ema50_4h);
            String shortTermTrend = calculateShortTermTrendFixed(currentPrice, ema20_4h, ema50_4h);

            indicators.setPrimaryTrend(primaryTrend);
            indicators.setShortTermTrend(shortTermTrend);
            indicators.setTrendAlignment(!primaryTrend.equals("NEUTRAL") && primaryTrend.equals(shortTermTrend));

            double trendStrength = calculateTrendStrengthFixed(currentPrice, ema200_daily, primaryTrend);
            indicators.setTrendStrength(trendStrength);

            log.info("CALCULATED Trends: primary={}, short={}, strength={}",
                    primaryTrend, shortTermTrend, trendStrength);

            // === RSI ZONES - VALÓDI SZÁMÍTÁS ===
            indicators.setRsiBullishZone(rsi > 30 && rsi < 70);
            indicators.setRsiBearishZone(rsi > 30 && rsi < 70);
            indicators.setRsiOversold(rsi < 30);
            indicators.setRsiOverbought(rsi > 70);

            // RSI trend - JAVÍTOTT
            if (closes4h.size() >= 20) {
                Double rsi5ago = calculateRSIManually(closes4h.subList(0, closes4h.size() - 5), 14);
                indicators.setRsiRising(rsi5ago != null && rsi > rsi5ago);
            } else {
                indicators.setRsiRising(false);
            }

            // === VOLATILITY ÉS SUPPORT/RESISTANCE ===
            calculateVolatilityAndSR(indicators, highs4h, lows4h, closes4h, currentPrice);

            // === MARKET STRUCTURE ===
            calculateMarketStructure(indicators, highs4h, lows4h, closes4h);

            log.info("=== FINAL INDICATOR VALUES ===");
            log.info("RSI: {}, EMA20: {}, EMA50: {}", indicators.getRsi(), indicators.getEma20_4h(), indicators.getEma50_4h());
            log.info("Primary Trend: {}, Volume Ratio: {}", indicators.getPrimaryTrend(), indicators.getVolumeRatio());
            log.info("MACD: line={}, signal={}, histogram={}",
                    indicators.getMacdLine(), indicators.getMacdSignal(), indicators.getMacdHistogram());

            return indicators;

        } catch (Exception e) {
            log.error("Technical indicator calculation failed: {}", e.getMessage(), e);
            return createDefaultIndicators(currentPrice);
        }
    }

// === MANUÁLIS SZÁMÍTÁSI METÓDUSOK ===

    private Double calculateEMAManually(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return null;

        try {
            double multiplier = 2.0 / (period + 1);
            double ema = prices.get(0); // Start with first price

            for (int i = 1; i < prices.size(); i++) {
                ema = (prices.get(i) * multiplier) + (ema * (1 - multiplier));
            }

            return ema;
        } catch (Exception e) {
            log.error("EMA calculation error for period {}: {}", period, e.getMessage());
            return null;
        }
    }

    private Double calculateRSIManually(List<Double> prices, int period) {
        if (prices == null || prices.size() < period + 1) return null;

        try {
            List<Double> gains = new ArrayList<>();
            List<Double> losses = new ArrayList<>();

            // Calculate gains and losses
            for (int i = 1; i < prices.size(); i++) {
                double change = prices.get(i) - prices.get(i - 1);
                gains.add(Math.max(0, change));
                losses.add(Math.max(0, -change));
            }

            if (gains.size() < period) return 50.0;

            // Calculate average gain and loss
            double avgGain = gains.subList(0, period).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0.0);
            double avgLoss = losses.subList(0, period).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0.0);

            // Apply smoothing for remaining periods
            for (int i = period; i < gains.size(); i++) {
                avgGain = (avgGain * (period - 1) + gains.get(i)) / period;
                avgLoss = (avgLoss * (period - 1) + losses.get(i)) / period;
            }

            if (avgLoss == 0) return 100.0;

            double rs = avgGain / avgLoss;
            double rsi = 100.0 - (100.0 / (1.0 + rs));

            return Math.max(0, Math.min(100, rsi));

        } catch (Exception e) {
            log.error("RSI calculation error: {}", e.getMessage());
            return 50.0;
        }
    }

    private MacdResult calculateMACDManually(List<Double> prices, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (prices == null || prices.size() < Math.max(fastPeriod, slowPeriod) + signalPeriod) return null;

        try {
            Double emaFast = calculateEMAManually(prices, fastPeriod);
            Double emaSlow = calculateEMAManually(prices, slowPeriod);

            if (emaFast == null || emaSlow == null) return null;

            double macdLine = emaFast - emaSlow;

            // Simplified signal line (should be EMA of MACD line, but using approximation)
            double signalLine = macdLine * 0.8; // Simplified
            double histogram = macdLine - signalLine;

            return new MacdResult(macdLine, signalLine, histogram);

        } catch (Exception e) {
            log.error("MACD calculation error: {}", e.getMessage());
            return null;
        }
    }

    // === HELPER CLASSES ===
    @lombok.Data
    @lombok.AllArgsConstructor
    private static class MacdResult {
        private double macdLine;
        private double signalLine;
        private double histogram;
    }

    // === DEFAULT VALUE SETTERS ===
    private TechnicalIndicators createDefaultIndicators(double currentPrice) {
        log.warn("Creating default indicators due to calculation failure");
        TechnicalIndicators indicators = new TechnicalIndicators();
        indicators.setCurrentPrice(currentPrice);
        indicators.setEma20_4h(currentPrice);
        indicators.setEma50_4h(currentPrice);
        indicators.setEma200_daily(currentPrice);
        indicators.setRsi(50.0);
        setDefaultMACD(indicators);
        setDefaultVolume(indicators);
        indicators.setPrimaryTrend("NEUTRAL");
        indicators.setShortTermTrend("NEUTRAL");
        indicators.setTrendAlignment(false);
        indicators.setTrendStrength(0.0);
        return indicators;
    }

    private void setDefaultMACD(TechnicalIndicators indicators) {
        indicators.setMacdLine(0.0);
        indicators.setMacdSignal(0.0);
        indicators.setMacdHistogram(0.0);
        indicators.setMacdBullish(false);
        indicators.setMacdBearish(false);
    }

    private void setDefaultVolume(TechnicalIndicators indicators) {
        indicators.setCurrentVolume(1000.0);
        indicators.setAverageVolume20(1000.0);
        indicators.setVolumeRatio(1.0);
        indicators.setStrongVolume(false);
    }

    // === JAVÍTOTT TREND SZÁMÍTÁSOK ===
    private String calculatePrimaryTrendFixed(double currentPrice, double ema200, double ema20, double ema50) {
        try {
            if (currentPrice > ema200 && ema20 > ema50 && (ema20 - ema50) / ema50 > 0.005) {
                return "BULLISH";
            } else if (currentPrice < ema200 && ema20 < ema50 && (ema50 - ema20) / ema50 > 0.005) {
                return "BEARISH";
            }
            return "NEUTRAL";
        } catch (Exception e) {
            log.error("Primary trend calculation error: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    private String calculateShortTermTrendFixed(double currentPrice, double ema20, double ema50) {
        try {
            double priceDiffPct = Math.abs(currentPrice - ema20) / ema20;
            double emaDiffPct = Math.abs(ema20 - ema50) / ema50;

            if (currentPrice > ema20 && ema20 > ema50 && emaDiffPct > 0.002) {
                return "BULLISH";
            } else if (currentPrice < ema20 && ema20 < ema50 && emaDiffPct > 0.002) {
                return "BEARISH";
            }
            return "NEUTRAL";
        } catch (Exception e) {
            log.error("Short term trend calculation error: {}", e.getMessage());
            return "NEUTRAL";
        }
    }

    private double calculateTrendStrengthFixed(double currentPrice, double ema200, String trend) {
        try {
            double strength = Math.abs((currentPrice - ema200) / ema200) * 100;
            return Math.min(10.0, strength); // Cap at 10%
        } catch (Exception e) {
            log.error("Trend strength calculation error: {}", e.getMessage());
            return 0.0;
        }
    }

    // === VOLATILITY ÉS SUPPORT/RESISTANCE ===
    private void calculateVolatilityAndSR(TechnicalIndicators indicators, List<Double> highs,
                                          List<Double> lows, List<Double> closes, double currentPrice) {
        try {
            if (highs.size() < 14 || lows.size() < 14 || closes.size() < 14) {
                setDefaultVolatility(indicators, currentPrice);
                return;
            }

            // ATR számítás (Average True Range)
            double atr = calculateATRManually(highs, lows, closes, 14);
            indicators.setAtr(atr);
            indicators.setVolatilityPercent((atr / currentPrice) * 100);

            // Support/Resistance számítás - utolsó 50 candle alapján
            int lookback = Math.min(50, highs.size());
            List<Double> recentHighs = highs.subList(highs.size() - lookback, highs.size());
            List<Double> recentLows = lows.subList(lows.size() - lookback, lows.size());

            double resistance = recentHighs.stream().mapToDouble(Double::doubleValue).max().orElse(currentPrice * 1.05);
            double support = recentLows.stream().mapToDouble(Double::doubleValue).min().orElse(currentPrice * 0.95);

            indicators.setNearestSupport(support);
            indicators.setNearestResistance(resistance);

            // Távolságok százalékban
            double distanceFromSupport = ((currentPrice - support) / currentPrice) * 100;
            double distanceFromResistance = ((resistance - currentPrice) / currentPrice) * 100;

            indicators.setDistanceFromSupport(Math.max(0, distanceFromSupport));
            indicators.setDistanceFromResistance(Math.max(0, distanceFromResistance));

            // Risk/Reward ratio
            if (distanceFromSupport > 0.5) {
                indicators.setRiskRewardRatio(distanceFromResistance / distanceFromSupport);
            } else {
                indicators.setRiskRewardRatio(0.0);
            }

            log.info("CALCULATED Volatility: ATR={}, Vol%={}, Support={}, Resistance={}, R/R={}",
                    atr, indicators.getVolatilityPercent(), support, resistance, indicators.getRiskRewardRatio());

        } catch (Exception e) {
            log.error("Volatility/SR calculation error: {}", e.getMessage());
            setDefaultVolatility(indicators, currentPrice);
        }
    }

    private double calculateATRManually(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (highs.size() < period + 1) return 0.0;

        List<Double> trueRanges = new ArrayList<>();

        for (int i = 1; i < highs.size(); i++) {
            double high = highs.get(i);
            double low = lows.get(i);
            double prevClose = closes.get(i - 1);

            double tr1 = high - low;
            double tr2 = Math.abs(high - prevClose);
            double tr3 = Math.abs(low - prevClose);

            double trueRange = Math.max(tr1, Math.max(tr2, tr3));
            trueRanges.add(trueRange);
        }

        if (trueRanges.size() < period) return 0.0;

        // ATR as simple moving average of True Range
        return trueRanges.subList(trueRanges.size() - period, trueRanges.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private void setDefaultVolatility(TechnicalIndicators indicators, double currentPrice) {
        indicators.setAtr(currentPrice * 0.02); // 2% default ATR
        indicators.setVolatilityPercent(2.0);
        indicators.setNearestSupport(currentPrice * 0.95);
        indicators.setNearestResistance(currentPrice * 1.05);
        indicators.setDistanceFromSupport(5.0);
        indicators.setDistanceFromResistance(5.0);
        indicators.setRiskRewardRatio(1.0);
    }

    // === MARKET STRUCTURE ===
    private void calculateMarketStructure(TechnicalIndicators indicators, List<Double> highs,
                                          List<Double> lows, List<Double> closes) {
        try {
            if (highs.size() < 20 || lows.size() < 20) {
                setDefaultMarketStructure(indicators);
                return;
            }

            int lookback = Math.min(20, highs.size());

            // Higher Highs pattern - utolsó 8 candle
            boolean higherHighs = isHigherHighsPatternManual(highs, 8);
            indicators.setHigherHighs(higherHighs);

            // Lower Lows pattern - utolsó 8 candle
            boolean lowerLows = isLowerLowsPatternManual(lows, 8);
            indicators.setLowerLows(lowerLows);

            // Higher Lows pattern
            boolean higherLows = isHigherLowsPatternManual(lows, 8);
            indicators.setHigherLows(higherLows);

            // Lower Highs pattern
            boolean lowerHighs = isLowerHighsPatternManual(highs, 8);
            indicators.setLowerHighs(lowerHighs);

            // Compound patterns
            indicators.setBullishStructure(higherHighs && higherLows);
            indicators.setBearishStructure(lowerLows && lowerHighs);
            indicators.setConsolidation(!higherHighs && !lowerLows && !higherLows && !lowerHighs);

            log.info("CALCULATED Market Structure: HH={}, LL={}, HL={}, LH={}, Bullish={}, Bearish={}, Consolidation={}",
                    higherHighs, lowerLows, higherLows, lowerHighs,
                    indicators.isBullishStructure(), indicators.isBearishStructure(), indicators.isConsolidation());

        } catch (Exception e) {
            log.error("Market structure calculation error: {}", e.getMessage());
            setDefaultMarketStructure(indicators);
        }
    }

    private boolean isHigherHighsPatternManual(List<Double> highs, int lookback) {
        if (highs.size() < lookback) return false;

        List<Double> recentHighs = highs.subList(highs.size() - lookback, highs.size());

        // Egyszerű logika: utolsó 3 high > előző 3 high átlaga
        int mid = lookback / 2;
        double firstHalf = recentHighs.subList(0, mid).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double secondHalf = recentHighs.subList(mid, lookback).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return secondHalf > firstHalf * 1.001; // 0.1% threshold
    }

    private boolean isLowerLowsPatternManual(List<Double> lows, int lookback) {
        if (lows.size() < lookback) return false;

        List<Double> recentLows = lows.subList(lows.size() - lookback, lows.size());

        int mid = lookback / 2;
        double firstHalf = recentLows.subList(0, mid).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double secondHalf = recentLows.subList(mid, lookback).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return secondHalf < firstHalf * 0.999; // 0.1% threshold
    }

    private boolean isHigherLowsPatternManual(List<Double> lows, int lookback) {
        if (lows.size() < lookback) return false;

        List<Double> recentLows = lows.subList(lows.size() - lookback, lows.size());

        int mid = lookback / 2;
        double firstHalf = recentLows.subList(0, mid).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double secondHalf = recentLows.subList(mid, lookback).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return secondHalf > firstHalf * 1.001; // 0.1% threshold
    }

    private boolean isLowerHighsPatternManual(List<Double> highs, int lookback) {
        if (highs.size() < lookback) return false;

        List<Double> recentHighs = highs.subList(highs.size() - lookback, highs.size());

        int mid = lookback / 2;
        double firstHalf = recentHighs.subList(0, mid).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double secondHalf = recentHighs.subList(mid, lookback).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return secondHalf < firstHalf * 0.999; // 0.1% threshold
    }

    private void setDefaultMarketStructure(TechnicalIndicators indicators) {
        indicators.setHigherHighs(false);
        indicators.setLowerLows(false);
        indicators.setHigherLows(false);
        indicators.setLowerHighs(false);
        indicators.setBullishStructure(false);
        indicators.setBearishStructure(false);
        indicators.setConsolidation(true);
    }


    /**
     * JAVÍTOTT preparePythonMLData metódus debug loggal
     */
    private Map<String, Object> preparePythonMLData(String symbol, double currentPrice, TechnicalIndicators indicators) {
        Map<String, Object> mlData = new HashMap<>();

        // === DEBUG: LOG TECHNICAL INDICATORS ===
        log.info("=== TECHNICAL INDICATORS DEBUG for {} ===", symbol);
        log.info("Current Price: {}", currentPrice);
        log.info("RSI: {}", indicators.getRsi());
        log.info("EMA20 4h: {}", indicators.getEma20_4h());
        log.info("EMA50 4h: {}", indicators.getEma50_4h());
        log.info("EMA200 Daily: {}", indicators.getEma200_daily());
        log.info("Primary Trend: {}", indicators.getPrimaryTrend());
        log.info("Short Term Trend: {}", indicators.getShortTermTrend());
        log.info("MACD Line: {}, Signal: {}, Histogram: {}",
                indicators.getMacdLine(), indicators.getMacdSignal(), indicators.getMacdHistogram());
        log.info("Volume Ratio: {}", indicators.getVolumeRatio());
        log.info("ATR: {}, Volatility%: {}", indicators.getAtr(), indicators.getVolatilityPercent());
        log.info("Risk/Reward: {}", indicators.getRiskRewardRatio());
        log.info("Trend Alignment: {}", indicators.isTrendAlignment());
        log.info("============================================");

        mlData.put("symbol", symbol);
        mlData.put("currentPrice", currentPrice);
        mlData.put("timestamp", System.currentTimeMillis());

        // === TREND FEATURES ===
        mlData.put("ema20_4h", indicators.getEma20_4h());
        mlData.put("ema50_4h", indicators.getEma50_4h());
        mlData.put("ema200_daily", indicators.getEma200_daily());

        // KRITIKUS: Null check és validáció
        double ema20_4h = safeDouble(indicators.getEma20_4h(), currentPrice);
        double ema50_4h = safeDouble(indicators.getEma50_4h(), currentPrice);
        double ema200_daily = safeDouble(indicators.getEma200_daily(), currentPrice);

        // Price ratios with validation
        mlData.put("price_vs_ema20_4h", ema20_4h > 0 ? currentPrice / ema20_4h : 1.0);
        mlData.put("price_vs_ema50_4h", ema50_4h > 0 ? currentPrice / ema50_4h : 1.0);
        mlData.put("price_vs_ema200_daily", ema200_daily > 0 ? currentPrice / ema200_daily : 1.0);
        mlData.put("ema20_vs_ema50_4h", ema50_4h > 0 ? ema20_4h / ema50_4h : 1.0);

        // Trend scores
        mlData.put("primaryTrendScore", convertTrendToScore(indicators.getPrimaryTrend()));
        mlData.put("shortTermTrendScore", convertTrendToScore(indicators.getShortTermTrend()));
        mlData.put("trendAlignment", indicators.isTrendAlignment() ? 1.0 : 0.0);

        Double trendStrengthVal = indicators.getTrendStrength();
        mlData.put("trendStrength", (trendStrengthVal != null) ? trendStrengthVal : 0.0);

        // === MOMENTUM FEATURES ===
        Double rsiVal = indicators.getRsi();
        double rsi = (rsiVal != null) ? rsiVal : 50.0;
        mlData.put("rsi", rsi);
        mlData.put("rsiNormalized", (rsi - 50.0) / 50.0);

        Double macdLineVal = indicators.getMacdLine();
        double macdLine = (macdLineVal != null) ? macdLineVal : 0.0;

        Double macdSignalVal = indicators.getMacdSignal();
        double macdSignal = (macdSignalVal != null) ? macdSignalVal : 0.0;

        Double macdHistVal = indicators.getMacdHistogram();
        double macdHist = (macdHistVal != null) ? macdHistVal : 0.0;


        mlData.put("macdLine", macdLine);
        mlData.put("macdSignal", macdSignal);
        mlData.put("macdHistogram", macdHist);
        mlData.put("macdDivergence", macdLine - macdSignal);

        // RSI zones
        mlData.put("rsiOverboughtScore", rsi > 70 ? (rsi - 70) / 30 : 0.0);
        mlData.put("rsiOversoldScore", rsi < 30 ? (30 - rsi) / 30 : 0.0);
        mlData.put("rsiMomentumScore", calculateRsiMomentumScore(indicators));

        // === VOLUME FEATURES ===
        double currentVolume = safeDouble(indicators.getCurrentVolume(), 0.0);
        double averageVolume20 = safeDouble(indicators.getAverageVolume20(), 1.0);
        double volumeRatio = safeDouble(indicators.getVolumeRatio(), 1.0);

        mlData.put("currentVolume", currentVolume);
        mlData.put("averageVolume20", averageVolume20);
        mlData.put("volumeRatio", volumeRatio);
        mlData.put("volumeRatioLog", Math.log(Math.max(0.01, volumeRatio)));
        mlData.put("volumeStrengthScore", calculateVolumeStrengthScore(indicators));
        mlData.put("volumeTrendScore", indicators.isVolumeTrendUp() ? 1.0 : -1.0);

        // === VOLATILITY & RISK FEATURES ===
        double atr = safeDouble(indicators.getAtr(), 0.0);
        double volatilityPercent = safeDouble(indicators.getVolatilityPercent(), 0.0);
        double riskRewardRatio = safeDouble(indicators.getRiskRewardRatio(), 0.0);

        mlData.put("atr", atr);
        mlData.put("atrPercent", volatilityPercent);
        mlData.put("riskRewardRatio", riskRewardRatio);
        mlData.put("riskRewardScore", Math.min(5.0, riskRewardRatio));

        // Support/Resistance
        Double nearestSupportVal = indicators.getNearestSupport();
        double nearestSupport = (nearestSupportVal != null) ? nearestSupportVal : currentPrice * 0.95;

        Double nearestResistanceVal = indicators.getNearestResistance();
        double nearestResistance = (nearestResistanceVal != null) ? nearestResistanceVal : currentPrice * 1.05;

        mlData.put("supportRatio", nearestSupport / currentPrice);
        mlData.put("resistanceRatio", nearestResistance / currentPrice);

        Double distanceFromSupportVal = indicators.getDistanceFromSupport();
        mlData.put("distanceFromSupport", (distanceFromSupportVal != null) ? distanceFromSupportVal : 5.0);

        Double distanceFromResistanceVal = indicators.getDistanceFromResistance();
        mlData.put("distanceFromResistance", (distanceFromResistanceVal != null) ? distanceFromResistanceVal : 5.0);


        // === MARKET STRUCTURE FEATURES ===
        mlData.put("structureBullishScore", calculateStructureBullishScore(indicators));
        mlData.put("structureBearishScore", calculateStructureBearishScore(indicators));
        mlData.put("consolidationScore", indicators.isConsolidation() ? 1.0 : 0.0);

        // === COMPOSITE SCORES ===
        mlData.put("trendMomentumScore", calculateTrendMomentumScore(indicators));
        mlData.put("volumePriceScore", calculateVolumePriceScore(indicators));
        mlData.put("riskAdjustedScore", calculateRiskAdjustedScore(indicators));

        // === DEBUG: LOG PREPARED ML DATA ===
        log.info("=== PREPARED ML DATA for {} ===", symbol);
        mlData.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Number)
                .forEach(entry -> log.info("{}: {}", entry.getKey(), entry.getValue()));
        log.info("=====================================");

        return mlData;
    }

    private static double safeDouble(Double value, double defaultValue) {
        return (value != null) ? value : defaultValue;
    }

    // === HELPER METHODS FOR NUMERICAL CONVERSION ===

    private double convertTrendToScore(String trend) {
        return switch (trend) {
            case "BULLISH" -> 1.0;
            case "BEARISH" -> -1.0;
            default -> 0.0;
        };
    }

    private double calculateRsiMomentumScore(TechnicalIndicators indicators) {
        double rsi = indicators.getRsi();
        double score = 0.0;

        if (indicators.isRsiRising()) score += 0.5;
        if (rsi > 30 && rsi < 70) score += 0.3; // Good zone
        if (rsi > 50) score += 0.2; // Above midline

        return score;
    }

    private double calculateVolumeStrengthScore(TechnicalIndicators indicators) {
        double score = 0.0;
        double ratio = indicators.getVolumeRatio();

        if (ratio > 2.0) score = 1.0;
        else if (ratio > 1.5) score = 0.8;
        else if (ratio > 1.2) score = 0.6;
        else if (ratio > 1.0) score = 0.4;
        else if (ratio > 0.8) score = 0.2;
        else score = 0.0;

        if (indicators.isVolumeBreakout()) score += 0.2;

        return score;
    }

    private double calculateStructureBullishScore(TechnicalIndicators indicators) {
        double score = 0.0;

        if (indicators.isHigherHighs()) score += 0.4;
        if (indicators.isHigherLows()) score += 0.4;
        if (indicators.isBullishStructure()) score += 0.2;

        return score;
    }

    private double calculateStructureBearishScore(TechnicalIndicators indicators) {
        double score = 0.0;

        if (indicators.isLowerLows()) score += 0.4;
        if (indicators.isLowerHighs()) score += 0.4;
        if (indicators.isBearishStructure()) score += 0.2;

        return score;
    }

    private double calculateTrendMomentumScore(TechnicalIndicators indicators) {
        double trendScore = convertTrendToScore(indicators.getPrimaryTrend());
        double momentumScore = (indicators.getRsi() - 50.0) / 50.0;
        double macdScore = indicators.isMacdBullish() ? 0.5 : (indicators.isMacdBearish() ? -0.5 : 0.0);

        return (trendScore + momentumScore + macdScore) / 3.0;
    }

    private double calculateVolumePriceScore(TechnicalIndicators indicators) {
        double volumeScore = Math.min(1.0, indicators.getVolumeRatio() / 2.0);
        double priceScore = convertTrendToScore(indicators.getShortTermTrend());

        return volumeScore * priceScore;
    }

    private double calculateRiskAdjustedScore(TechnicalIndicators indicators) {
        double baseScore = calculateTrendMomentumScore(indicators);
        double riskPenalty = indicators.getVolatilityPercent() > 10.0 ? 0.5 : 0.0;
        double rrBonus = indicators.getRiskRewardRatio() > 2.0 ? 0.2 : 0.0;

        return baseScore - riskPenalty + rrBonus;
    }

    /**
     * Historical analysis - csak számítások, ugyanúgy mint az élő elemzésben
     */
    public CoinAnalysis analyzeHistoricalCoin(String symbol, double lastPrice,
                                              List<List<Object>> fourHourKlines,
                                              List<List<Object>> dailyKlines,
                                              List<List<Object>> hourlyKlines, List<List<Object>> thirtyMinKlines) {
        try {
            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                return createNoTradeAnalysis(symbol, lastPrice, "Insufficient historical data");
            }

            // Extract price data
            List<Double> closes4h = extractCloses(fourHourKlines);
            List<Double> highs4h = extractHighs(fourHourKlines);
            List<Double> lows4h = extractLows(fourHourKlines);
            List<Double> volumes4h = extractVolumes(fourHourKlines);
            List<Double> closesDaily = extractCloses(dailyKlines);
            List<Double> closesHourly = extractCloses(hourlyKlines);

            double currentPrice = closes4h.get(closes4h.size() - 1);

            // Calculate all technical indicators
            TechnicalIndicators indicators = calculateAllIndicators(
                    closes4h, highs4h, lows4h, volumes4h,
                    closesDaily, closesHourly,
                    closes4h, highs4h, lows4h, // Use 4h for 15m approximation
                    currentPrice
            );

            // BACKTEST MODE: Generate directional signals for training data
            Direction direction = generateBacktestDirectionalSignal(indicators);
            Signal signal = convertDirectionToSignal(direction);
            double score = calculateDirectionalSignalScore(indicators, direction);

            // Create analysis result with ACTUAL DIRECTIONAL SIGNALS for backtesting
            CoinAnalysis analysis = new CoinAnalysis(symbol, score, signal, lastPrice);
            analysis.setTechnicalIndicators(indicators);
            analysis.setDirection(direction);
            analysis.setTradingRule(direction != Direction.HOLD ? 1 : 0);

            // Set synthetic probabilities for training
            Map<String, Double> syntheticProbabilities = generateSyntheticProbabilities(direction, score);
            analysis.setProbabilities(syntheticProbabilities);

            // ENHANCED: Calculate ML confidence using multiple factors
            double mlConfidence = calculateSyntheticMLConfidence(direction, score, indicators, syntheticProbabilities);
            analysis.setMlConfidence(mlConfidence);

            // Set analysis reason for debugging
            analysis.setAnalysisReason(String.format("Backtest: %s signal (score=%.1f, conf=%.2f)",
                    direction, score, mlConfidence));

            log.debug("Historical analysis for {}: direction={}, score={}, mlConfidence={}, probabilities={}",
                    symbol, direction, score, mlConfidence, syntheticProbabilities);

            return analysis;
        } catch (Exception e) {
            log.error("Failed to analyze historical coin {}: {}", symbol, e.getMessage());
            return createNoTradeAnalysis(symbol, lastPrice, "Historical analysis failed");
        }
    }

    /**
     * Calculate synthetic ML confidence for backtesting
     * This mimics real ML confidence based on technical analysis strength
     */
    private double calculateSyntheticMLConfidence(Direction direction, double score,
                                                  TechnicalIndicators indicators,
                                                  Map<String, Double> probabilities) {
        if (direction == Direction.HOLD) {
            return 0.0; // No confidence for HOLD signals
        }

        // Base confidence from probabilities
        String directionKey = direction.toString();
        double baseConfidence = probabilities.getOrDefault(directionKey, 0.0);

        // Enhance confidence based on technical strength
        double technicalBonus = 0.0;

        // Trend alignment bonus
        if (indicators.isTrendAlignment()) {
            technicalBonus += 0.1;
        }

        // Strong momentum bonus
        if (direction == Direction.LONG) {
            if (indicators.isMacdBullish() && indicators.getRsi() > 40 && indicators.getRsi() < 70) {
                technicalBonus += 0.15;
            }
        } else if (direction == Direction.SHORT) {
            if (indicators.isMacdBearish() && indicators.getRsi() > 30 && indicators.getRsi() < 60) {
                technicalBonus += 0.15;
            }
        }

        // Volume confirmation bonus
        if (indicators.getVolumeRatio() > 1.3) {
            technicalBonus += 0.1;
        }

        // Good risk/reward bonus
        if (indicators.getRiskRewardRatio() > 2.0) {
            technicalBonus += 0.1;
        }

        // Score-based adjustment (score 0-10 maps to confidence adjustment -0.2 to +0.2)
        double scoreAdjustment = (score - 5.0) / 25.0; // -0.2 to +0.2

        // Calculate final confidence
        double finalConfidence = baseConfidence + technicalBonus + scoreAdjustment;

        // Ensure confidence is within reasonable bounds for backtest
        finalConfidence = Math.max(0.1, Math.min(0.95, finalConfidence));

        // Add some randomization to make it more realistic (±5%)
        double randomFactor = 1.0 + (Math.random() - 0.5) * 0.1; // 0.95 to 1.05
        finalConfidence *= randomFactor;

        return Math.max(0.1, Math.min(0.95, finalConfidence));
    }

    /**
     * Convert Direction enum to Signal enum for backwards compatibility
     */
    private Signal convertDirectionToSignal(Direction direction) {
        return switch (direction) {
            case LONG -> Signal.LONG;
            case SHORT -> Signal.SHORT;
            case HOLD -> Signal.NO_TRADE;
        };
    }

    private double calculateSignalScore(TechnicalIndicators indicators, Signal signal) {
        if (signal == Signal.NO_TRADE) {
            return 0.0;
        }

        double score = 5.0; // Base score

        if (signal == Signal.LONG) {
            // Trend bonus
            if ("BULLISH".equals(indicators.getPrimaryTrend())) score += 2.0;
            if ("BULLISH".equals(indicators.getShortTermTrend())) score += 1.0;
            if (indicators.isTrendAlignment()) score += 1.0;

            // Momentum bonus
            if (indicators.getRsi() > 30 && indicators.getRsi() < 70) score += 1.0;
            if (indicators.isMacdBullish()) score += 1.5;
            if (indicators.isRsiRising()) score += 0.5;

            // Volume bonus
            if (indicators.getVolumeRatio() > 1.3) score += 1.0;
            if (indicators.isVolumeBreakout()) score += 0.5;

            // Risk adjustment
            if (indicators.getRiskRewardRatio() > 2.0) score += 1.0;
            if (indicators.getVolatilityPercent() < 8.0) score += 0.5;

        } else if (signal == Signal.SHORT) {
            // Mirror logic for SHORT
            if ("BEARISH".equals(indicators.getPrimaryTrend())) score += 2.0;
            if ("BEARISH".equals(indicators.getShortTermTrend())) score += 1.0;
            if (indicators.isTrendAlignment()) score += 1.0;

            if (indicators.getRsi() > 30 && indicators.getRsi() < 70) score += 1.0;
            if (indicators.isMacdBearish()) score += 1.5;
            if (!indicators.isRsiRising()) score += 0.5;

            if (indicators.getVolumeRatio() > 1.3) score += 1.0;
            if (indicators.isVolumeBreakout()) score += 0.5;

            if (indicators.getRiskRewardRatio() > 2.0) score += 1.0;
            if (indicators.getVolatilityPercent() < 8.0) score += 0.5;
        }

        // Cap between 0-10
        return Math.max(0.0, Math.min(10.0, score));
    }

    // ÚJ metódus hozzáadása:
    private Signal generateBacktestSignal(TechnicalIndicators indicators) {
        // Egyszerű signál generálás backtesthez
        double bullScore = 0.0;
        double bearScore = 0.0;

        // Trend
        if ("BULLISH".equals(indicators.getPrimaryTrend())) bullScore += 2.0;
        if ("BEARISH".equals(indicators.getPrimaryTrend())) bearScore += 2.0;

        // RSI
        if (indicators.getRsi() < 35) bullScore += 2.0;
        if (indicators.getRsi() > 65) bearScore += 2.0;

        // MACD
        if (indicators.isMacdBullish()) bullScore += 1.5;
        if (indicators.isMacdBearish()) bearScore += 1.5;

        // Volume
        if (indicators.getVolumeRatio() > 1.3) {
            bullScore += 1.0;
            bearScore += 1.0;
        }

        // ALACSONY küszöbök több signal generáláshoz
        if (bullScore >= 3.0 && bullScore > bearScore) return Signal.LONG;
        if (bearScore >= 3.0 && bearScore > bullScore) return Signal.SHORT;

        return Signal.NO_TRADE;
    }

    // === HELPER METHODS ===

    private CoinAnalysis createNoTradeAnalysis(String symbol, double lastPrice, String reason) {
        CoinAnalysis analysis = new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        analysis.setAnalysisReason(reason);
        return analysis;
    }

    private String calculatePrimaryTrend(double currentPrice, double ema200_daily, double ema20_4h, double ema50_4h) {
        if (currentPrice > ema200_daily * 0.98 && ema20_4h > ema50_4h * 0.995) {
            return "BULLISH";
        } else if (currentPrice < ema200_daily * 1.02 && ema20_4h < ema50_4h * 1.005) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    private String calculateShortTermTrend(double currentPrice, double ema10_1h, double ema20_1h) {
        if (ema10_1h > ema20_1h && currentPrice > ema10_1h * 0.99) {
            return "BULLISH";
        } else if (ema10_1h < ema20_1h && currentPrice < ema10_1h * 1.01) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    private double calculateTrendStrength(double currentPrice, double ema200_daily, String primaryTrend) {
        if (primaryTrend.equals("BULLISH")) {
            return (currentPrice - ema200_daily) / ema200_daily * 100;
        } else if (primaryTrend.equals("BEARISH")) {
            return (ema200_daily - currentPrice) / ema200_daily * 100;
        }
        return 0.0;
    }

    private double calculateVolumePercentile(List<Double> volumes, double currentVolume) {
        List<Double> recentVolumes = volumes.subList(Math.max(0, volumes.size() - 50), volumes.size());
        long belowCurrent = recentVolumes.stream().mapToLong(v -> v < currentVolume ? 1 : 0).sum();
        return (double) belowCurrent / recentVolumes.size() * 100;
    }

    private void logTradingDecision(String message) {
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
     * Resample candles to different timeframe
     */
    public List<HistoricalCandle> resample(List<HistoricalCandle> candles, int hours) {
        if (hours == 1) return candles;

        List<HistoricalCandle> resampled = new ArrayList<>();

        for (int i = 0; i < candles.size(); i += hours) {
            List<HistoricalCandle> group = candles.subList(i, Math.min(i + hours, candles.size()));

            if (group.isEmpty()) continue;

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
}