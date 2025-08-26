package kd.trading.bot.service;

import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.Signal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class CoinAnalysisService {

    public BinanceTickerData analyze(BinanceTickerData ticker,
                                     double ema50,
                                     double ema200,
                                     double ath,
                                     double rsi) {
        double score = 0.0;

        double lastClose = ticker.getLastPrice();
        double avgVol20 = ticker.getQuoteVolume();

        // --- 1. Trend erősség szűrés (oldalazás kiszűrése) ---
        double emaDiffPct = Math.abs(ema50 - ema200) / ema200 * 100;
        double priceDistanceFrom200 = Math.abs(lastClose - ema200) / ema200 * 100;

        boolean strongTrend = emaDiffPct > 2.0 && priceDistanceFrom200 > 3.0;
        boolean enoughVolume = avgVol20 > 500_000;

        if (!strongTrend || !enoughVolume) {
            ticker.setSignal(Signal.NO_TRADE);
            ticker.setScore(0.0);
            return ticker;
        }

        // --- 2. Trend irány ---
        if (ema50 > ema200 && lastClose > ema50) {
            score += 3.0; // bull trend
        } else if (ema50 < ema200 && lastClose < ema50) {
            score -= 3.0; // bear trend
        }

        // --- 3. ATH közelség ---
        double distanceFromAthPct = (ath - lastClose) / ath * 100;
        if (distanceFromAthPct < 10) {
            score -= 2.0; // túl közel ATH-hoz
        } else {
            score += 1.0;
        }

        // --- 4. RSI ---
        if (rsi < 30) {
            score += 2.0; // túladott
        } else if (rsi > 70) {
            score -= 2.0; // túlvett
        }

        // --- 5. Volumen aktivitás ---
        if (avgVol20 > 5_000_000) {
            score += 1.0;
        }

        // --- 6. Jelzés ---
        Signal signal;
        if (score >= 3.0) {
            signal = Signal.LONG;
        } else if (score <= -3.0) {
            signal = Signal.SHORT;
        } else {
            signal = Signal.NO_TRADE;
        }

        ticker.setSignal(signal);
        ticker.setScore(score);

        return ticker;
    }
}