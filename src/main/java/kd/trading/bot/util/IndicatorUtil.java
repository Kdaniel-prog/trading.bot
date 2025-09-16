package kd.trading.bot.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.IntStream;

@Component
@Slf4j
public class IndicatorUtil {

    // =========================== TREND INDICATORS ===========================

    /**
     * Calculate Exponential Moving Average
     */
    public double EMA(List<Double> prices, int period) {
        if (prices.size() < period) return 0.0;

        double multiplier = 2.0 / (period + 1);
        double ema = prices.get(0);

        for (int i = 1; i < prices.size(); i++) {
            ema = (prices.get(i) * multiplier) + (ema * (1 - multiplier));
        }

        return ema;
    }

    /**
     * Calculate Simple Moving Average
     */
    public double SMA(List<Double> prices, int period) {
        if (prices.size() < period) return 0.0;

        return prices.subList(prices.size() - period, prices.size())
                .stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    // =========================== MOMENTUM INDICATORS ===========================

    /**
     * Calculate RSI (Relative Strength Index)
     */
    public double RSI(List<Double> prices, int period) {
        if (prices.size() < period + 1) return 50.0;

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        // Calculate price changes
        for (int i = 1; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            gains.add(Math.max(change, 0));
            losses.add(Math.max(-change, 0));
        }

        // Calculate average gains and losses
        double avgGain = gains.subList(0, period).stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgLoss = losses.subList(0, period).stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        // Calculate RSI using Wilder's smoothing
        for (int i = period; i < gains.size(); i++) {
            avgGain = (avgGain * (period - 1) + gains.get(i)) / period;
            avgLoss = (avgLoss * (period - 1) + losses.get(i)) / period;
        }

        if (avgLoss == 0) return 100.0;

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * Calculate MACD (Moving Average Convergence Divergence)
     * Returns: [MACD Line, Signal Line, Histogram]
     */
    public double[] MACD(List<Double> prices, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (prices.size() < slowPeriod) {
            return new double[]{0.0, 0.0, 0.0};
        }

        double fastEMA = EMA(prices, fastPeriod);
        double slowEMA = EMA(prices, slowPeriod);
        double macdLine = fastEMA - slowEMA;

        // Calculate signal line (EMA of MACD line)
        List<Double> macdHistory = new ArrayList<>();
        for (int i = slowPeriod; i <= prices.size(); i++) {
            List<Double> subPrices = prices.subList(0, i);
            double fastEmaTemp = EMA(subPrices, fastPeriod);
            double slowEmaTemp = EMA(subPrices, slowPeriod);
            macdHistory.add(fastEmaTemp - slowEmaTemp);
        }

        double signalLine = EMA(macdHistory, signalPeriod);
        double histogram = macdLine - signalLine;

        return new double[]{macdLine, signalLine, histogram};
    }

    // =========================== VOLATILITY INDICATORS ===========================

    /**
     * Calculate Average True Range (ATR)
     */
    public double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (highs.size() < period + 1 || lows.size() < period + 1 || closes.size() < period + 1) {
            return 0.0;
        }

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

        // Calculate ATR as SMA of True Ranges
        return trueRanges.subList(trueRanges.size() - period, trueRanges.size())
                .stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    // =========================== SUPPORT/RESISTANCE ===========================

    /**
     * Calculate Support and Resistance levels
     * Returns: [Support Level, Resistance Level]
     */
    public double[] calculateSupportResistance(List<Double> highs, List<Double> lows, List<Double> closes) {
        if (highs.size() < 20) {
            double currentPrice = closes.get(closes.size() - 1);
            return new double[]{currentPrice * 0.95, currentPrice * 1.05};
        }

        List<Double> pivotHighs = findPivotHighs(highs, 5);
        List<Double> pivotLows = findPivotLows(lows, 5);

        double currentPrice = closes.get(closes.size() - 1);

        // Find nearest support (highest pivot low below current price)
        double support = pivotLows.stream()
                .filter(level -> level < currentPrice)
                .max(Double::compareTo)
                .orElse(currentPrice * 0.95);

        // Find nearest resistance (lowest pivot high above current price)
        double resistance = pivotHighs.stream()
                .filter(level -> level > currentPrice)
                .min(Double::compareTo)
                .orElse(currentPrice * 1.05);

        return new double[]{support, resistance};
    }

    private List<Double> findPivotHighs(List<Double> highs, int lookback) {
        List<Double> pivots = new ArrayList<>();

        for (int i = lookback; i < highs.size() - lookback; i++) {
            boolean isPivot = true;
            double current = highs.get(i);

            // Check if current high is higher than lookback periods on both sides
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j != i && highs.get(j) >= current) {
                    isPivot = false;
                    break;
                }
            }

            if (isPivot) {
                pivots.add(current);
            }
        }

        return pivots;
    }

    private List<Double> findPivotLows(List<Double> lows, int lookback) {
        List<Double> pivots = new ArrayList<>();

        for (int i = lookback; i < lows.size() - lookback; i++) {
            boolean isPivot = true;
            double current = lows.get(i);

            // Check if current low is lower than lookback periods on both sides
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j != i && lows.get(j) <= current) {
                    isPivot = false;
                    break;
                }
            }

            if (isPivot) {
                pivots.add(current);
            }
        }

        return pivots;
    }

    // =========================== PATTERN RECOGNITION ===========================

    /**
     * Check if price is making higher highs pattern
     */
    public boolean isHigherHighsPattern(List<Double> highs, int periods) {
        if (highs.size() < periods) return false;

        List<Double> recentHighs = highs.subList(highs.size() - periods, highs.size());
        List<Double> pivotHighs = findPivotHighs(recentHighs, 2);

        if (pivotHighs.size() < 2) return false;

        // Check if the last pivot high is higher than the previous one
        return pivotHighs.get(pivotHighs.size() - 1) > pivotHighs.get(pivotHighs.size() - 2);
    }

    /**
     * Check if price is making lower lows pattern
     */
    public boolean isLowerLowsPattern(List<Double> lows, int periods) {
        if (lows.size() < periods) return false;

        List<Double> recentLows = lows.subList(lows.size() - periods, lows.size());
        List<Double> pivotLows = findPivotLows(recentLows, 2);

        if (pivotLows.size() < 2) return false;

        // Check if the last pivot low is lower than the previous one
        return pivotLows.get(pivotLows.size() - 1) < pivotLows.get(pivotLows.size() - 2);
    }

    /**
     * Check if price is making higher lows pattern
     */
    public boolean isHigherLowsPattern(List<Double> lows, int periods) {
        if (lows.size() < periods) return false;

        List<Double> recentLows = lows.subList(lows.size() - periods, lows.size());
        List<Double> pivotLows = findPivotLows(recentLows, 2);

        if (pivotLows.size() < 2) return false;

        // Check if the last pivot low is higher than the previous one
        return pivotLows.get(pivotLows.size() - 1) > pivotLows.get(pivotLows.size() - 2);
    }

    /**
     * Check if price is making lower highs pattern
     */
    public boolean isLowerHighsPattern(List<Double> highs, int periods) {
        if (highs.size() < periods) return false;

        List<Double> recentHighs = highs.subList(highs.size() - periods, highs.size());
        List<Double> pivotHighs = findPivotHighs(recentHighs, 2);

        if (pivotHighs.size() < 2) return false;

        // Check if the last pivot high is lower than the previous one
        return pivotHighs.get(pivotHighs.size() - 1) < pivotHighs.get(pivotHighs.size() - 2);
    }

    // =========================== BREAKOUT PATTERNS ===========================

    /**
     * Detect breakout patterns
     */
    public boolean isBreakoutPattern(List<Double> highs, List<Double> lows, List<Double> closes) {
        if (closes.size() < 20) return false;

        double currentPrice = closes.get(closes.size() - 1);

        // Calculate recent trading range
        List<Double> recentHighs = highs.subList(highs.size() - 10, highs.size());
        List<Double> recentLows = lows.subList(lows.size() - 10, lows.size());

        double rangeHigh = recentHighs.stream().max(Double::compareTo).orElse(0.0);
        double rangeLow = recentLows.stream().min(Double::compareTo).orElse(0.0);

        // Check if current price is breaking above recent range with volume
        boolean breakoutAbove = currentPrice > rangeHigh * 1.001; // 0.1% above range
        boolean breakoutBelow = currentPrice < rangeLow * 0.999; // 0.1% below range

        // Additional confirmation: price should be in consolidation before breakout
        double rangeSize = (rangeHigh - rangeLow) / rangeLow * 100;
        boolean wasConsolidating = rangeSize < 5.0; // Less than 5% range

        return (breakoutAbove || breakoutBelow) && wasConsolidating;
    }

    /**
     * Detect reversal patterns
     */
    public boolean isReversalPattern(List<Double> highs, List<Double> lows, List<Double> closes) {
        if (closes.size() < 20) return false;

        double currentPrice = closes.get(closes.size() - 1);
        double prevPrice = closes.get(closes.size() - 2);

        // Calculate recent trend
        List<Double> recentPrices = closes.subList(closes.size() - 10, closes.size());
        double trendSlope = calculateTrendSlope(recentPrices);

        // Look for potential reversal signals
        boolean wasInUptrend = trendSlope > 0.001;
        boolean wasInDowntrend = trendSlope < -0.001;

        // Check for reversal signs
        double currentRSI = RSI(closes, 14);

        // Bullish reversal: downtrend + oversold RSI + bullish price action
        boolean bullishReversal = wasInDowntrend && currentRSI < 30 && currentPrice > prevPrice;

        // Bearish reversal: uptrend + overbought RSI + bearish price action
        boolean bearishReversal = wasInUptrend && currentRSI > 70 && currentPrice < prevPrice;

        return bullishReversal || bearishReversal;
    }

    /**
     * Detect structure breaks (break of market structure)
     */
    public boolean isStructureBreak(List<Double> highs, List<Double> lows, List<Double> closes) {
        if (closes.size() < 30) return false;

        // Find recent significant swing highs and lows
        List<Double> recentData = closes.subList(closes.size() - 20, closes.size());
        List<Double> recentHighs = highs.subList(highs.size() - 20, highs.size());
        List<Double> recentLows = lows.subList(lows.size() - 20, lows.size());

        List<Double> swingHighs = findPivotHighs(recentHighs, 3);
        List<Double> swingLows = findPivotLows(recentLows, 3);

        if (swingHighs.isEmpty() || swingLows.isEmpty()) return false;

        double currentPrice = closes.get(closes.size() - 1);

        // Get the most recent significant levels
        double lastSwingHigh = swingHighs.get(swingHighs.size() - 1);
        double lastSwingLow = swingLows.get(swingLows.size() - 1);

        // Determine current trend
        boolean inUptrend = isHigherHighsPattern(highs, 15) && isHigherLowsPattern(lows, 15);
        boolean inDowntrend = isLowerHighsPattern(highs, 15) && isLowerLowsPattern(lows, 15);

        // Structure break conditions
        boolean bullishStructureBreak = inDowntrend && currentPrice > lastSwingHigh * 1.001;
        boolean bearishStructureBreak = inUptrend && currentPrice < lastSwingLow * 0.999;

        return bullishStructureBreak || bearishStructureBreak;
    }

    // =========================== HELPER METHODS ===========================

    /**
     * Calculate trend slope using linear regression
     */
    private double calculateTrendSlope(List<Double> prices) {
        if (prices.size() < 2) return 0.0;

        int n = prices.size();
        double sumX = IntStream.range(0, n).sum();
        double sumY = prices.stream().mapToDouble(Double::doubleValue).sum();
        double sumXY = IntStream.range(0, n)
                .mapToDouble(i -> i * prices.get(i))
                .sum();
        double sumXX = IntStream.range(0, n)
                .mapToDouble(i -> i * i)
                .sum();

        double slope = (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX);
        return slope;
    }

    /**
     * Calculate price momentum
     */
    public double calculateMomentum(List<Double> prices, int periods) {
        if (prices.size() < periods + 1) return 0.0;

        double currentPrice = prices.get(prices.size() - 1);
        double pastPrice = prices.get(prices.size() - 1 - periods);

        return (currentPrice - pastPrice) / pastPrice * 100;
    }

    /**
     * Calculate volatility (standard deviation of returns)
     */
    public double calculateVolatility(List<Double> prices, int periods) {
        if (prices.size() < periods + 1) return 0.0;

        List<Double> returns = new ArrayList<>();
        List<Double> recentPrices = prices.subList(prices.size() - periods - 1, prices.size());

        for (int i = 1; i < recentPrices.size(); i++) {
            double returnPct = (recentPrices.get(i) - recentPrices.get(i - 1)) / recentPrices.get(i - 1);
            returns.add(returnPct);
        }

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance) * Math.sqrt(252) * 100; // Annualized volatility in %
    }
}