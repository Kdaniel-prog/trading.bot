package kd.trading.bot.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TechnicalIndicators {

    @JsonProperty("currentPrice")
    private double currentPrice;

    // === TREND INDICATORS ===
    @JsonProperty("ema20_4h")
    private double ema20_4h;

    @JsonProperty("ema50_4h")
    private double ema50_4h;

    @JsonProperty("ema200_daily")
    private double ema200_daily;

    @JsonProperty("ema10_1h")
    private double ema10_1h;

    @JsonProperty("ema20_1h")
    private double ema20_1h;

    @JsonProperty("primaryTrend")
    private String primaryTrend = "NEUTRAL"; // BULLISH, BEARISH, NEUTRAL

    @JsonProperty("shortTermTrend")
    private String shortTermTrend = "NEUTRAL";

    @JsonProperty("trendAlignment")
    private boolean trendAlignment;

    @JsonProperty("trendStrength")
    private double trendStrength;

    // === MOMENTUM INDICATORS ===
    @JsonProperty("rsi")
    private Double rsi;

    @JsonProperty("macdLine")
    private double macdLine;

    @JsonProperty("macdSignal")
    private double macdSignal;

    @JsonProperty("macdHistogram")
    private Double macdHistogram;

    @JsonProperty("macdBullish")
    private boolean macdBullish;

    @JsonProperty("macdBearish")
    private boolean macdBearish;

    @JsonProperty("rsiBullishZone")
    private boolean rsiBullishZone;

    @JsonProperty("rsiBearishZone")
    private boolean rsiBearishZone;

    @JsonProperty("rsiOversold")
    private boolean rsiOversold;

    @JsonProperty("rsiOverbought")
    private boolean rsiOverbought;

    @JsonProperty("rsiRising")
    private boolean rsiRising;

    // === VOLUME INDICATORS ===
    @JsonProperty("currentVolume")
    private double currentVolume;

    @JsonProperty("averageVolume20")
    private double averageVolume20;

    @JsonProperty("averageVolume50")
    private double averageVolume50;

    @JsonProperty("volumeRatio")
    private Double volumeRatio;

    @JsonProperty("strongVolume")
    private boolean strongVolume;

    @JsonProperty("volumeBreakout")
    private boolean volumeBreakout;

    @JsonProperty("volumeTrendUp")
    private boolean volumeTrendUp;

    // === VOLATILITY & RISK INDICATORS ===
    @JsonProperty("atr")
    private double atr;

    @JsonProperty("volatilityPercent")
    private Double volatilityPercent;

    @JsonProperty("shortTermVolatility")
    private double shortTermVolatility;

    @JsonProperty("nearestSupport")
    private double nearestSupport;

    @JsonProperty("nearestResistance")
    private double nearestResistance;

    @JsonProperty("distanceFromSupport")
    private double distanceFromSupport;

    @JsonProperty("distanceFromResistance")
    private double distanceFromResistance;

    @JsonProperty("riskRewardRatio")
    private Double riskRewardRatio;

    // === MARKET STRUCTURE INDICATORS ===
    @JsonProperty("higherHighs")
    private boolean higherHighs;

    @JsonProperty("lowerLows")
    private boolean lowerLows;

    @JsonProperty("higherLows")
    private boolean higherLows;

    @JsonProperty("lowerHighs")
    private boolean lowerHighs;

    @JsonProperty("bullishStructure")
    private boolean bullishStructure;

    @JsonProperty("bearishStructure")
    private boolean bearishStructure;

    @JsonProperty("consolidation")
    private boolean consolidation;

    // === CONVENIENCE METHODS ===

    /**
     * Overall bullish score (0-100)
     */
    public double getBullishScore() {
        double score = 0.0;

        // Trend components (40 points max)
        if ("BULLISH".equals(primaryTrend)) score += 20;
        if ("BULLISH".equals(shortTermTrend)) score += 10;
        if (trendAlignment && "BULLISH".equals(primaryTrend)) score += 10;

        // Momentum components (30 points max)
        if (macdBullish) score += 10;
        if (rsiBullishZone && !rsiOverbought) score += 10;
        if (rsiOversold) score += 10; // Bounce opportunity

        // Structure components (20 points max)
        if (bullishStructure) score += 10;
        if (higherHighs) score += 5;
        if (higherLows) score += 5;

        // Volume components (10 points max)
        if (strongVolume) score += 5;
        if (volumeBreakout) score += 5;

        return Math.min(100.0, score);
    }

    /**
     * Overall bearish score (0-100)
     */
    public double getBearishScore() {
        double score = 0.0;

        // Trend components (40 points max)
        if ("BEARISH".equals(primaryTrend)) score += 20;
        if ("BEARISH".equals(shortTermTrend)) score += 10;
        if (trendAlignment && "BEARISH".equals(primaryTrend)) score += 10;

        // Momentum components (30 points max)
        if (macdBearish) score += 10;
        if (rsiBearishZone && !rsiOversold) score += 10;
        if (rsiOverbought) score += 10; // Correction opportunity

        // Structure components (20 points max)
        if (bearishStructure) score += 10;
        if (lowerLows) score += 5;
        if (lowerHighs) score += 5;

        // Volume components (10 points max)
        if (strongVolume) score += 5;
        if (volumeBreakout) score += 5;

        return Math.min(100.0, score);
    }

    /**
     * Risk score (higher = more risky)
     */
    public double getRiskScore() {
        double risk = 0.0;

        if (volatilityPercent > 10.0) risk += 30;
        else if (volatilityPercent > 5.0) risk += 15;

        if (riskRewardRatio < 1.5) risk += 20;
        else if (riskRewardRatio > 3.0) risk -= 10;

        if (distanceFromSupport < 2.0) risk += 15;
        if (distanceFromResistance < 2.0) risk += 15;

        if (consolidation) risk += 10;

        return Math.max(0.0, Math.min(100.0, risk));
    }

    /**
     * Market condition summary
     */
    public String getMarketCondition() {
        double bullish = getBullishScore();
        double bearish = getBearishScore();
        double risk = getRiskScore();

        if (risk > 70) return "HIGH_RISK";
        if (consolidation) return "CONSOLIDATING";

        if (bullish > bearish + 20) return "STRONG_BULLISH";
        if (bearish > bullish + 20) return "STRONG_BEARISH";
        if (bullish > bearish + 10) return "WEAK_BULLISH";
        if (bearish > bullish + 10) return "WEAK_BEARISH";

        return "NEUTRAL";
    }

    /**
     * Trading confidence (0-1)
     */
    public double getTradingConfidence() {
        double bullish = getBullishScore();
        double bearish = getBearishScore();
        double risk = getRiskScore();

        double signalStrength = Math.abs(bullish - bearish);
        double riskPenalty = risk / 100.0;

        // Base confidence from signal strength
        double confidence = signalStrength / 100.0;

        // Apply risk penalty
        confidence *= (1.0 - riskPenalty * 0.5);

        // Volume confirmation bonus
        if (strongVolume || volumeBreakout) {
            confidence += 0.1;
        }

        // Trend alignment bonus
        if (trendAlignment) {
            confidence += 0.1;
        }

        return Math.max(0.0, Math.min(1.0, confidence));
    }

    /**
     * Check if market structure is bullish
     */
    public boolean isBullishMarketStructure() {
        return bullishStructure || (higherHighs && higherLows);
    }

    /**
     * Check if market structure is bearish
     */
    public boolean isBearishMarketStructure() {
        return bearishStructure || (lowerLows && lowerHighs);
    }

    /**
     * Overall technical score (-100 to +100)
     * Positive = bullish, Negative = bearish
     */
    public double getTechnicalScore() {
        double bullishScore = getBullishScore();
        double bearishScore = getBearishScore();
        double riskScore = getRiskScore();

        // Base score from bullish vs bearish
        double baseScore = bullishScore - bearishScore;

        // Apply risk penalty
        double riskPenalty = riskScore * 0.5;
        double adjustedScore = baseScore - riskPenalty;

        // Ensure score is within bounds
        return Math.max(-100.0, Math.min(100.0, adjustedScore));
    }

    /**
     * Get technical analysis summary
     */
    public String getSummary() {
        StringBuilder summary = new StringBuilder();

        summary.append(String.format("Market: %s | ", getMarketCondition()));
        summary.append(String.format("RSI: %.1f | ", rsi));
        summary.append(String.format("MACD: %s | ", macdBullish ? "BULL" : (macdBearish ? "BEAR" : "NEUT")));
        summary.append(String.format("Volume: %.1fx | ", volumeRatio));
        summary.append(String.format("Risk: %.0f/100 | ", getRiskScore()));
        summary.append(String.format("Confidence: %.0f%%", getTradingConfidence() * 100));

        return summary.toString();
    }

    @Override
    public String toString() {
        return String.format("TechnicalIndicators{trend=%s/%s, rsi=%.1f, macd=%s, volume=%.2fx, risk=%.1f}",
                primaryTrend, shortTermTrend, rsi,
                macdBullish ? "BULL" : (macdBearish ? "BEAR" : "NEUT"),
                volumeRatio, getRiskScore());
    }
}