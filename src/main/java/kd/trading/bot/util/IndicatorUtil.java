package kd.trading.bot.util;

import java.util.ArrayList;
import java.util.List;

public class IndicatorUtil {

    // Simple Moving Average
    public static double SMA(List<Double> closes) {
        return closes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    // Exponential Moving Average
    public static double EMA(List<Double> closes, int period) {
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
    public static double RSI(List<Double> closes, int period) {
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
    public static double[] MACD(List<Double> closes, int fastPeriod, int slowPeriod, int signalPeriod) {
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
}
