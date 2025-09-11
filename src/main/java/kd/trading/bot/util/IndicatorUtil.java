package kd.trading.bot.util;

import java.util.ArrayList;
import java.util.List;

public class IndicatorUtil {

    // Simple Moving Average
    public double SMA(List<Double> closes) {
        return closes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    // Exponential Moving Average
    public double EMA(List<Double> closes, int period) {
        if (closes.isEmpty() || period <= 0) return 0.0;

        double k = 2.0 / (period + 1); // smoothing factor
        double ema = closes.get(0);   // első elem indulásnak

        for (int i = 1; i < closes.size(); i++) {
            double price = closes.get(i);
            ema = price * k + ema * (1 - k);
        }

        return ema;
    }

    // Wilder RSI (klasszikus verzió, TradingView-val egyező)
    public double RSI(List<Double> closes, int period) {
        if (closes.size() < period + 1) return 50.0; // default középérték

        double gain = 0.0;
        double loss = 0.0;

        // első átlag: sima átlag az első "period" gyertyán
        for (int i = 1; i <= period; i++) {
            double change = closes.get(i) - closes.get(i - 1);
            if (change > 0) gain += change;
            else loss -= change;
        }

        gain /= period;
        loss /= period;

        double avgGain = gain;
        double avgLoss = loss;

        // további gyertyák: Wilder smoothing
        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i) - closes.get(i - 1);
            double currentGain = (change > 0) ? change : 0;
            double currentLoss = (change < 0) ? -change : 0;

            avgGain = (avgGain * (period - 1) + currentGain) / period;
            avgLoss = (avgLoss * (period - 1) + currentLoss) / period;
        }

        if (avgLoss == 0) return 100.0;

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    // MACD: [0] = MACD line, [1] = Signal line, [2] = Histogram
    public double[] MACD(List<Double> closes, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (closes.size() < slowPeriod + signalPeriod) {
            return new double[] {0.0, 0.0, 0.0};
        }

        // MACD line = EMA(fast) - EMA(slow)
        double emaFast = EMA(closes, fastPeriod);
        double emaSlow = EMA(closes, slowPeriod);
        double macdLine = emaFast - emaSlow;

        // Signal line: EMA on MACD values
        List<Double> macdValues = new ArrayList<>();
        for (int i = slowPeriod; i < closes.size(); i++) {
            double subEmaFast = EMA(closes.subList(0, i + 1), fastPeriod);
            double subEmaSlow = EMA(closes.subList(0, i + 1), slowPeriod);
            macdValues.add(subEmaFast - subEmaSlow);
        }

        double signalLine = EMA(macdValues, signalPeriod);
        double histogram = macdLine - signalLine;

        return new double[] {macdLine, signalLine, histogram};
    }

    public double[] calculateSupportResistance(List<Double> highs, List<Double> lows, List<Double> closes) {
        int lookback = Math.min(50, highs.size());
        double currentPrice = closes.get(closes.size() - 1);

        // Find recent significant levels
        List<Double> recentHighs = highs.subList(highs.size() - lookback, highs.size());
        List<Double> recentLows = lows.subList(lows.size() - lookback, lows.size());

        // Find nearest support (highest low below current price)
        double nearestSupport = recentLows.stream()
                .filter(low -> low < currentPrice)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(currentPrice * 0.95);

        // Find nearest resistance (lowest high above current price)
        double nearestResistance = recentHighs.stream()
                .filter(high -> high > currentPrice)
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(currentPrice * 1.05);

        return new double[]{nearestSupport, nearestResistance};
    }

    public double calculateATR(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (highs.size() < period + 1) return 0.0;

        double sum = 0.0;
        for (int i = highs.size() - period; i < highs.size(); i++) {
            double high = highs.get(i);
            double low = lows.get(i);
            double prevClose = i > 0 ? closes.get(i - 1) : closes.get(i);

            double tr1 = high - low;
            double tr2 = Math.abs(high - prevClose);
            double tr3 = Math.abs(low - prevClose);

            double trueRange = Math.max(tr1, Math.max(tr2, tr3));
            sum += trueRange;
        }

        return sum / period;
    }

    public boolean isHigherHighsPattern(List<Double> highs, int lookback) {
        if (highs.size() < lookback) return false;

        List<Double> recentHighs = highs.subList(highs.size() - lookback, highs.size());
        int higherHighsCount = 0;

        for (int i = 1; i < recentHighs.size(); i++) {
            if (recentHighs.get(i) > recentHighs.get(i - 1)) {
                higherHighsCount++;
            }
        }

        return higherHighsCount > lookback * 0.6; // 60% of recent candles showing higher highs
    }

    public boolean isLowerLowsPattern(List<Double> lows, int lookback) {
        if (lows.size() < lookback) return false;

        List<Double> recentLows = lows.subList(lows.size() - lookback, lows.size());
        int lowerLowsCount = 0;

        for (int i = 1; i < recentLows.size(); i++) {
            if (recentLows.get(i) < recentLows.get(i - 1)) {
                lowerLowsCount++;
            }
        }

        return lowerLowsCount > lookback * 0.6; // 60% of recent candles showing lower lows
    }
}
