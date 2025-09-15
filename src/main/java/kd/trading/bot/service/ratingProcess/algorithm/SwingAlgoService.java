package kd.trading.bot.service.ratingProcess.algorithm;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.CoinAnalysis;
import kd.trading.bot.util.IndicatorUtil;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SwingAlgoService {

    BinanceRestClient restClient;
    IndicatorUtil indicatorUtil;

    public CoinAnalysis analyzeCoin(String symbol, double lastPrice) {
        try {
            // Get 4H and daily data for better swing analysis
            List<List<Object>> fourHourKlines = restClient.getKlines(symbol, "4h", 300);
            List<List<Object>> dailyKlines = restClient.getKlines(symbol, "1d", 200);

            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, 0.0);
            }

            // Extract price data
            List<Double> closes4h = fourHourKlines.stream()
                    .map(k -> Double.parseDouble(k.get(4).toString()))
                    .toList();

            List<Double> highs4h = fourHourKlines.stream()
                    .map(k -> Double.parseDouble(k.get(2).toString()))
                    .toList();

            List<Double> lows4h = fourHourKlines.stream()
                    .map(k -> Double.parseDouble(k.get(3).toString()))
                    .toList();

            List<Double> volumes4h = fourHourKlines.stream()
                    .map(k -> Double.parseDouble(k.get(5).toString()))
                    .toList();

            List<Double> closesDaily = dailyKlines.stream()
                    .map(k -> Double.parseDouble(k.get(4).toString()))
                    .toList();

            double currentPrice = closes4h.get(closes4h.size() - 1);

            // === PRIMARY TREND ANALYSIS ===
            double ema20_4h = indicatorUtil.EMA(closes4h, 20);
            double ema50_4h = indicatorUtil.EMA(closes4h, 50);
            double ema200_daily = indicatorUtil.EMA(closesDaily, 200);

            boolean primaryUptrend = currentPrice > ema200_daily && ema20_4h > ema50_4h;
            boolean primaryDowntrend = currentPrice < ema200_daily && ema20_4h < ema50_4h;

            // === MOMENTUM ANALYSIS ===
            double rsi4h = indicatorUtil.RSI(closes4h, 14);
            double[] macd4h = indicatorUtil.MACD(closes4h, 12, 26, 9);
            double macdLine = macd4h[0];
            double macdSignal = macd4h[1];
            double macdHist = macd4h[2];

            // MACD momentum conditions - More flexible
            boolean macdBullish = macdLine > macdSignal;
            boolean macdBearish = macdLine < macdSignal;
            boolean macdStrengthening = Math.abs(macdHist) > 0; // Any momentum change

            // RSI conditions - More permissive ranges
            boolean rsiBullishEntry = rsi4h > 35 && rsi4h < 70;
            boolean rsiBearishEntry = rsi4h > 30 && rsi4h < 65;

            // === VOLUME ANALYSIS ===
            double avgVolume20 = volumes4h.subList(volumes4h.size() - 20, volumes4h.size())
                    .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double currentVolume = volumes4h.get(volumes4h.size() - 1);
            boolean strongVolume = currentVolume > 1.2 * avgVolume20; // Lowered threshold

            // === SUPPORT/RESISTANCE LEVELS ===
            double[] srLevels = indicatorUtil.calculateSupportResistance(highs4h, lows4h, closes4h);
            double nearestSupport = srLevels[0];
            double nearestResistance = srLevels[1];

            // Distance from S/R levels (risk management)
            double distanceFromSupport = (currentPrice - nearestSupport) / currentPrice * 100;
            double distanceFromResistance = (nearestResistance - currentPrice) / currentPrice * 100;

            // === VOLATILITY FILTER ===
            double atr = indicatorUtil.calculateATR(highs4h, lows4h, closes4h, 14);
            double volatilityPct = (atr / currentPrice) * 100;
            boolean goodVolatility = volatilityPct > 1.5 && volatilityPct < 10.0; // More permissive range

            // === MARKET STRUCTURE ===
            boolean higherHighs = indicatorUtil.isHigherHighsPattern(highs4h, 8); // Shorter period
            boolean lowerLows = indicatorUtil.isLowerLowsPattern(lows4h, 8);

            // === ENHANCED SCORING SYSTEM ===
            double score = 0.0;

            // Base trend bias (reduced weight)
            if (primaryUptrend) score += 2.5;
            if (primaryDowntrend) score -= 2.5;

            // Momentum alignment (increased importance)
            if (macdBullish && rsiBullishEntry) score += 3.5;
            if (macdBearish && rsiBearishEntry) score -= 3.5;

            // MACD strengthening bonus
            if (macdStrengthening) {
                if (macdBullish) score += 1.0;
                if (macdBearish) score -= 1.0;
            }

            // Market structure
            if (higherHighs) score += 2.5;
            if (lowerLows) score -= 2.5;

            // Volume confirmation
            if (strongVolume) score += 2.0; // Increased weight

            // Volatility bonus (good volatility = more opportunity)
            if (goodVolatility) score += 1.5;
            else if (volatilityPct < 1.0) score -= 1.5; // Only penalize very low volatility

            // Risk/Reward based on S/R levels - more aggressive
            if (distanceFromSupport > 1.5) score += 1.0; // Easier to qualify
            if (distanceFromResistance > 2.0) score += 1.5; // Room to move
            if (distanceFromResistance < 1.0) score -= 1.5; // Reduced penalty

            // RSI momentum bonus instead of extreme penalty
            if (rsi4h > 60 && rsi4h < 75) score += 1.0; // Bullish momentum
            if (rsi4h > 25 && rsi4h < 40) score -= 1.0; // Bearish momentum
            if (rsi4h > 80 || rsi4h < 20) score -= 2.0; // Only extreme levels penalized

            // Trend alignment bonus
            double ema10_4h = indicatorUtil.EMA(closes4h, 10);
            if (ema10_4h > ema20_4h && ema20_4h > ema50_4h) score += 2.0; // Strong uptrend
            if (ema10_4h < ema20_4h && ema20_4h < ema50_4h) score -= 2.0; // Strong downtrend

            // Short-term momentum check
            double priceChange5Bars = (closes4h.get(closes4h.size() - 1) - closes4h.get(closes4h.size() - 6)) / closes4h.get(closes4h.size() - 6) * 100;
            if (Math.abs(priceChange5Bars) > 3.0) { // Strong recent movement
                if (priceChange5Bars > 0) score += 1.5; // Recent bullish momentum
                else score -= 1.5; // Recent bearish momentum
            }

            // === OPTIMIZED SIGNAL DECISION ===
            Signal signal = Signal.NO_TRADE;

            // LONG signals - Lowered threshold and fixed signal direction
            if (score >= 4.5 && goodVolatility && rsiBullishEntry) {
                signal = Signal.LONG; // FIXED: Was incorrectly SHORT
            }
            // SHORT signals - Lowered threshold and fixed signal direction
            else if (score <= -4.5 && goodVolatility && rsiBearishEntry) {
                signal = Signal.SHORT; // FIXED: Was incorrectly LONG
            }
            // Additional opportunities with medium confidence
            else if (score >= 3.0 && macdBullish && strongVolume && primaryUptrend) {
                signal = Signal.LONG;
            }
            else if (score <= -3.0 && macdBearish && strongVolume && primaryDowntrend) {
                signal = Signal.SHORT;
            }
            // Quick momentum plays
            else if (score >= 2.5 && Math.abs(priceChange5Bars) > 4.0 && priceChange5Bars > 0 && rsi4h < 65) {
                signal = Signal.LONG;
            }
            else if (score <= -2.5 && Math.abs(priceChange5Bars) > 4.0 && priceChange5Bars < 0 && rsi4h > 35) {
                signal = Signal.SHORT;
            }

            return new CoinAnalysis(symbol, score, signal, lastPrice);

        } catch (Exception e) {
            log.error("Failed to analyze swing coin {}", symbol, e);
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        }
    }
}