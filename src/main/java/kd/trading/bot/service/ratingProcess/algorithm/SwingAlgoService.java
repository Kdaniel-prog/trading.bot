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

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
            // === HUNGARIAN TIME CHECK ===
            ZonedDateTime hungarianTime = ZonedDateTime.now(ZoneId.of("Europe/Budapest"));
            LocalTime currentTime = hungarianTime.toLocalTime();

            // Check if current time is between 20:00 (8 PM) and 06:00 (6 AM) next day
            boolean isNightTime = currentTime.isAfter(LocalTime.of(20, 0)) ||
                    currentTime.isBefore(LocalTime.of(6, 0));


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

            // MACD momentum conditions
            boolean macdBullish = macdLine > macdSignal && macdHist > 0;
            boolean macdBearish = macdLine < macdSignal && macdHist < 0;

            // RSI conditions for entry
            boolean rsiBullishEntry = rsi4h > 45 && rsi4h < 65; // Not oversold/overbought
            boolean rsiBearishEntry = rsi4h > 35 && rsi4h < 55;

            // === VOLUME ANALYSIS ===
            double avgVolume20 = volumes4h.subList(volumes4h.size() - 20, volumes4h.size())
                    .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double currentVolume = volumes4h.get(volumes4h.size() - 1);
            boolean strongVolume = currentVolume > 1.3 * avgVolume20;

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
            boolean goodVolatility = volatilityPct > 2.0 && volatilityPct < 8.0; // 2-8% volatility

            // === MARKET STRUCTURE ===
            boolean higherHighs = indicatorUtil.isHigherHighsPattern(highs4h, 10);
            boolean lowerLows = indicatorUtil.isLowerLowsPattern(lows4h, 10);

            // === SCORING SYSTEM ===
            double score = 0.0;

            // Primary trend (most important)
            if (primaryUptrend) score += 4.0;
            if (primaryDowntrend) score -= 4.0;

            // Momentum alignment
            if (macdBullish && rsiBullishEntry) score += 3.0;
            if (macdBearish && rsiBearishEntry) score -= 3.0;

            // Market structure
            if (higherHighs) score += 2.0;
            if (lowerLows) score -= 2.0;

            // Volume confirmation
            if (strongVolume) score += 1.5;

            // Volatility filter
            if (goodVolatility) score += 1.0;
            else score -= 2.0; // Penalize low/extreme volatility

            // Risk/Reward based on S/R levels
            if (distanceFromSupport > 2.0 && distanceFromSupport < 8.0) score += 1.5; // Good distance from support
            if (distanceFromResistance > 3.0) score += 1.0; // Room to move up
            if (distanceFromResistance < 1.5) score -= 2.0; // Too close to resistance

            // Additional filters to reduce false signals
            if (rsi4h > 75 || rsi4h < 25) score -= 3.0; // Avoid extreme RSI

            // Trend consistency check
            double ema10_4h = indicatorUtil.EMA(closes4h, 10);
            if (primaryUptrend && ema10_4h > ema20_4h && ema20_4h > ema50_4h) score += 1.5;
            if (primaryDowntrend && ema10_4h < ema20_4h && ema20_4h < ema50_4h) score -= 1.5;

            // === SIGNAL DECISION WITH FLEXIBLE CRITERIA ===
            Signal signal = Signal.NO_TRADE;

            // LONG signals - High confidence setups
            if (score >= 7.0 && primaryUptrend && macdBullish && strongVolume && goodVolatility) {
                signal = isNightTime ? Signal.LONG : Signal.SHORT;
            }
            // SHORT signals - You can adjust this threshold to get more/fewer SHORT signals
            else if (score <= -6.0 && primaryDowntrend && macdBearish && goodVolatility) {
                signal = isNightTime ? Signal.SHORT : Signal.LONG;
            }
            // NO_TRADE for everything else (most cases will be NO_TRADE for safety)

            log.debug("Analysis for {}: Score={}, RSI={}, MACD={}, Volume={}, ATR%={}",
                    symbol, score, rsi4h, macdLine, currentVolume/avgVolume20, volatilityPct);

            return new CoinAnalysis(symbol, score, signal, lastPrice);

        } catch (Exception e) {
            log.error("Failed to analyze swing coin {}", symbol, e);
            return new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        }
    }

}
