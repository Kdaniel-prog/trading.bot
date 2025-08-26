package kd.trading.bot.service;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.model.CoinAnalysis;
import kd.trading.bot.model.Signal;
import kd.trading.bot.util.IndicatorUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlgorithmService {

    private final BinanceRestClient restClient;
    //swing trade: lassabb trade 1 naptól 1 hétig tart egy trade.
    public CoinAnalysis analyzeSwingCoin(String symbol) {
        try {
            // 1️⃣ Lekérjük a napi gyertyákat (kb. 200 napra vissza)
            List<List<Object>> dailyKlines = restClient.getKlines(symbol, "1d", 200);
            List<Double> closes = dailyKlines.stream()
                    .map(k -> Double.parseDouble(k.get(4).toString()))
                    .toList();

            List<Double> volumes = dailyKlines.stream()
                    .map(k -> Double.parseDouble(k.get(5).toString()))
                    .toList();

            if (closes.size() < 200) {
                return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE);
            }

            // 2️⃣ Trend filter: EMA50 vs EMA200
            double ema50 = IndicatorUtil.EMA(closes, 50);
            double ema200 = IndicatorUtil.EMA(closes, 200);
            double lastClose = closes.get(closes.size() - 1);

            boolean bullTrend = ema50 > ema200 && lastClose > ema50;
            boolean bearTrend = ema50 < ema200 && lastClose < ema50;

            // 3️⃣ Volume filter: mai volumen vs 20 napos átlag
            double avgVol20 = volumes.subList(volumes.size() - 20, volumes.size())
                    .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double lastVol = volumes.get(volumes.size() - 1);
            boolean strongVolume = lastVol > 1.5 * avgVol20;

            // 4️⃣ MACD filter (trend megerősítés)
            double[] macd = IndicatorUtil.MACD(closes, 12, 26, 9);
            double macdValue = macd[0];
            double macdSignal = macd[1];
            boolean macdBull = macdValue > macdSignal && macdValue > 0;
            boolean macdBear = macdValue < macdSignal && macdValue < 0;

            // 5️⃣ Döntés
            Signal signal;
            if (bullTrend && strongVolume && macdBull) {
                signal = Signal.LONG;
            } else if (bearTrend && strongVolume && macdBear) {
                signal = Signal.SHORT;
            } else {
                signal = Signal.NO_TRADE;
            }

            log.debug("{} | ema50: {:.2f}, ema200: {:.2f}, volCheck: {}, macd: {:.2f}/{:.2f} => signal: {}",
                    symbol, ema50, ema200, strongVolume, macdValue, macdSignal, signal);

            return new CoinAnalysis(symbol, 0.0, signal);

        } catch (Exception e) {
            log.error("Failed to analyze swing coin {}", symbol, e);
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE);
        }
    }

    public List<CoinAnalysis> analyzeTopCoins(List<String> symbols, int topN) {
        return symbols.stream()
                .map(this::analyzeSwingCoin)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topN)
                .toList();
    }
}

