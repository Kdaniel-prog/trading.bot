package kd.trading.bot.service.ratingProcess.algorithm;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.CoinAnalysis;
import kd.trading.bot.util.IndicatorUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SwingAlgoService {

    private final BinanceRestClient restClient;

    public CoinAnalysis analyzeCoin(String symbol, double lastPrice) {
        try {
            // 1. Lekérjük a napi gyertyákat (kb. 200 napra vissza)
            List<List<Object>> dailyKlines = restClient.getKlines(symbol, "1d", 200);
            List<Double> closes = dailyKlines.stream()
                    .map(k -> Double.parseDouble(k.get(4).toString()))
                    .toList();

            List<Double> volumes = dailyKlines.stream()
                    .map(k -> Double.parseDouble(k.get(5).toString()))
                    .toList();

            if (closes.size() < 200) {
                return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, 0.0);
            }

            double lastClose = closes.get(closes.size() - 1);

            // 2. Trend filter: EMA50 vs EMA200
            double ema50 = IndicatorUtil.EMA(closes, 50);
            double ema200 = IndicatorUtil.EMA(closes, 200);
            boolean bullTrend = ema50 > ema200 && lastClose > ema50;
            boolean bearTrend = ema50 < ema200 && lastClose < ema50;

            // 3. Volume filter: mai volumen vs 20 napos átlag
            double avgVol20 = volumes.subList(volumes.size() - 20, volumes.size())
                    .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double lastVol = volumes.get(volumes.size() - 1);
            boolean strongVolume = lastVol > 1.5 * avgVol20;

            // 4. MACD filter
            double[] macd = IndicatorUtil.MACD(closes, 12, 26, 9);
            double macdValue = macd[0];
            double macdSignal = macd[1];
            boolean macdBull = macdValue > macdSignal && macdValue > 0;
            boolean macdBear = macdValue < macdSignal && macdValue < 0;

            // 5. RSI
            double rsi = IndicatorUtil.RSI(closes, 14);

            // 6. ATH (All Time High)
            double ath = closes.stream().mapToDouble(Double::doubleValue).max().orElse(lastClose);
            double distanceFromAthPct = (ath - lastClose) / ath * 100;

            // Score számítás
            double score = 0.0;

            if (bullTrend) score += 3.0;
            if (bearTrend) score -= 3.0;

            if (strongVolume) score += 1.5;

            if (macdBull) score += 2.0;
            if (macdBear) score -= 2.0;

            if (distanceFromAthPct < 10) score -= 2.0; // túl közel ATH-hoz
            else score += 1.0;

            if (rsi < 30) score += 2.0;
            else if (rsi > 70) score -= 2.0;

            // 🔹 Jelzés döntés
            Signal signal;
            if (score >= 5.0) {
                signal = Signal.LONG;
            } else if (score <= -5.0) {
                signal = Signal.SHORT;
            } else {
                signal = Signal.NO_TRADE;
            }

            return new CoinAnalysis(symbol, score, signal, lastPrice);

        } catch (Exception e) {
            log.error("Failed to analyze swing coin {}", symbol, e);
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        }
    }

}
