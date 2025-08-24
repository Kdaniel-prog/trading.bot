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

    public CoinAnalysis analyzeCoin(String symbol) {
        try {
            // 1️⃣ Heti trend (7 napos zárók)
            List<List<Object>> weeklyKlines = restClient.getKlines(symbol, "1d", 7);
            List<Double> weeklyCloses = weeklyKlines.stream()
                    .map(k -> Double.parseDouble(k.get(4).toString()))
                    .toList();

            // 2️⃣ Napi trend (24 órás zárók)
            List<List<Object>> dailyKlines = restClient.getKlines(symbol, "1h", 24);
            List<Double> dailyCloses = dailyKlines.stream()
                    .map(k -> Double.parseDouble(k.get(4).toString()))
                    .toList();

            if (weeklyCloses.isEmpty() || dailyCloses.isEmpty()) {
                return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE);
            }

            // --- Heti EMA delta normalizálva
            double weeklyEMA = IndicatorUtil.EMA(weeklyCloses, 7);
            double lastWeeklyClose = weeklyCloses.get(weeklyCloses.size() - 1);
            double weeklyDelta = ((lastWeeklyClose - weeklyEMA) / weeklyEMA) * 100;
            double weeklyScore = Math.max(-10, Math.min(10, weeklyDelta)) * 5; // ±50 pont

            // --- Napi EMA delta normalizálva
            double dailyEMA = IndicatorUtil.EMA(dailyCloses, 24);
            double lastDailyClose = dailyCloses.get(dailyCloses.size() - 1);
            double dailyDelta = ((lastDailyClose - dailyEMA) / dailyEMA) * 100;
            double dailyScore = Math.max(-5, Math.min(5, dailyDelta)) * 6; // ±30 pont

            // --- RSI score
            double rsi = IndicatorUtil.RSI(dailyCloses, 14);
            double rsiScore;
            if (rsi > 70) rsiScore = -30;       // túlvett → SHORT
            else if (rsi < 30) rsiScore = 30;   // túladott → LONG
            else rsiScore = (50 - rsi) * 0.6;   // ±12 pont középértéknél

            // --- Összesített score
            double score = weeklyScore + dailyScore + rsiScore;

            // --- Döntés
            Signal signal;
            if (score > 20) signal = Signal.LONG;
            else if (score < -20) signal = Signal.SHORT;
            else signal = Signal.NO_TRADE;

            // Debug log (opcionális)
            log.debug("{} | weeklyScore: {:.2f}, dailyScore: {:.2f}, rsiScore: {:.2f} => score: {:.2f} | signal: {}",
                    symbol, weeklyScore, dailyScore, rsiScore, score, signal);

            return new CoinAnalysis(symbol, score, signal);

        } catch (Exception e) {
            log.error("Failed to analyze coin {}", symbol, e);
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE);
        }
    }

    public List<CoinAnalysis> analyzeTopCoins(List<String> symbols, int topN) {
        return symbols.stream()
                .map(this::analyzeCoin)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topN)
                .toList();
    }
}

