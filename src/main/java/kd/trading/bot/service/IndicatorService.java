package kd.trading.bot.service;

import org.springframework.stereotype.Service;

import java.util.List;

import kd.trading.bot.api.BinanceRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndicatorService {

    private final BinanceRestClient restClient;

    public double calculateEMA(List<Double> closes, int period) {
        if (closes.size() < period) return 0.0;
        double multiplier = 2.0 / (period + 1);
        double ema = closes.get(0);
        for (int i = 1; i < closes.size(); i++) {
            ema = (closes.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }

    public double calculateRSI(List<Double> closes, int period) {
        if (closes.size() < period + 1) return 50.0;

        double gains = 0.0, losses = 0.0;
        for (int i = closes.size() - period; i < closes.size() - 1; i++) {
            double diff = closes.get(i + 1) - closes.get(i);
            if (diff > 0) gains += diff;
            else losses -= diff;
        }
        double avgGain = gains / period;
        double avgLoss = losses / period;

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    public double calculateATH(List<Double> closes) {
        return closes.stream().mapToDouble(v -> v).max().orElse(0.0);
    }

    /**
     * Convenience: lekéri és kiszámolja az indikátorokat adott szimbólumra.
     */
    public Indicators loadIndicators(String symbol) {
        List<List<Object>> klines = restClient.getKlines(symbol, "1d", 250);
        if (klines.isEmpty()) return new Indicators(0,0,0,0);

        List<Double> closes = klines.stream()
                .map(k -> Double.parseDouble(k.get(4).toString())) // [4] = close price
                .toList();

        return new Indicators(
                calculateEMA(closes, 50),
                calculateEMA(closes, 200),
                calculateATH(closes),
                calculateRSI(closes, 14)
        );
    }

    public record Indicators(double ema50, double ema200, double ath, double rsi) {}
}

