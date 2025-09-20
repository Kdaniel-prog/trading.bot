package kd.trading.bot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Comprehensive technical indicators container
 * Contains all calculated indicators without trading logic
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TechnicalIndicators {

    // === TREND INDICATORS ===
    private double ema20_4h;
    private double ema50_4h;
    private double ema200_daily;
    private double ema10_1h;
    private double ema20_1h;

    private String primaryTrend;        // BULLISH, BEARISH, NEUTRAL
    private String shortTermTrend;      // BULLISH, BEARISH, NEUTRAL
    private boolean trendAlignment;     // primary == shortTerm && != NEUTRAL
    private double trendStrength;       // percentage from EMA200

    // === MOMENTUM INDICATORS ===
    private double rsi;
    private double macdLine;
    private double macdSignal;
    private double macdHistogram;
    private boolean macdBullish;        // line > signal && histogram > 0
    private boolean macdBearish;        // line < signal && histogram < 0

    private boolean rsiBullishZone;     // 30 < RSI < 75
    private boolean rsiBearishZone;     // 25 < RSI < 70
    private boolean rsiOversold;        // RSI < 30
    private boolean rsiOverbought;      // RSI > 70
    private boolean rsiRising;          // RSI trend direction

    // === VOLUME INDICATORS ===
    private double currentVolume;
    private double averageVolume20;
    private double averageVolume50;
    private double volumeRatio;         // current / average20
    private boolean strongVolume;       // volumeRatio > 1.3
    private boolean volumeBreakout;     // volume in top 25% percentile
    private boolean volumeTrendUp;      // volume trending upward

    // === VOLATILITY & RISK INDICATORS ===
    private double atr;
    private double volatilityPercent;   // ATR / price * 100
    private double shortTermVolatility; // ATR 15m / price * 100

    private double nearestSupport;
    private double nearestResistance;
    private double distanceFromSupport; // % distance
    private double distanceFromResistance; // % distance
    private double riskRewardRatio;     // resistance_distance / support_distance

    // === MARKET STRUCTURE ===
    private boolean higherHighs;
    private boolean lowerLows;
    private boolean higherLows;
    private boolean lowerHighs;
    private boolean bullishStructure;   // HH && HL
    private boolean bearishStructure;   // LL && LH
    private boolean consolidation;      // sideways movement

    /**
     * Get all indicators as a formatted string for logging
     */
    public String getSummary() {
        return String.format(
                "RSI: %.1f | MACD: %s | Trend: %s/%s | Volume: %.1fx | Volatility: %.1f%% | RR: %.2f",
                rsi,
                macdBullish ? "BULL" : (macdBearish ? "BEAR" : "NEUTRAL"),
                primaryTrend, shortTermTrend,
                volumeRatio,
                volatilityPercent,
                riskRewardRatio
        );
    }

    /**
     * Check if conditions are favorable for any trading (basic filters)
     */
    public boolean isBasicallyTradeable() {
        return volatilityPercent > 1.0 && volatilityPercent < 15.0  // reasonable volatility
                && riskRewardRatio >= 1.2                               // minimum RR
                && !Double.isNaN(rsi) && !Double.isNaN(macdLine);      // valid indicators
    }

    /**
     * Get trend strength category
     */
    public String getTrendStrengthCategory() {
        if (trendStrength > 10.0) return "VERY_STRONG";
        if (trendStrength > 5.0) return "STRONG";
        if (trendStrength > 2.0) return "MODERATE";
        if (trendStrength > 0.5) return "WEAK";
        return "NEUTRAL";
    }

    /**
     * Get RSI zone description
     */
    public String getRsiZoneDescription() {
        if (rsiOverbought) return "OVERBOUGHT";
        if (rsiOversold) return "OVERSOLD";
        if (rsiBullishZone) return "BULLISH_ZONE";
        if (rsiBearishZone) return "BEARISH_ZONE";
        return "NEUTRAL";
    }

    /**
     * Get volume strength description
     */
    public String getVolumeStrengthDescription() {
        if (volumeRatio > 2.0) return "VERY_HIGH";
        if (volumeRatio > 1.5) return "HIGH";
        if (volumeRatio > 1.0) return "ABOVE_AVERAGE";
        if (volumeRatio > 0.7) return "AVERAGE";
        return "LOW";
    }

    /**
     * Check if market structure is bullish
     */
    public boolean isBullishMarketStructure() {
        return bullishStructure || (higherHighs && !bearishStructure);
    }

    /**
     * Check if market structure is bearish
     */
    public boolean isBearishMarketStructure() {
        return bearishStructure || (lowerLows && !bullishStructure);
    }

    /**
     * Calculate overall technical score (0-100)
     * This is for reference only, ML will make actual decisions
     */
    public double getTechnicalScore() {
        double score = 50.0; // neutral base

        // Trend component (±20 points)
        if ("BULLISH".equals(primaryTrend)) {
            score += 10.0;
            if ("BULLISH".equals(shortTermTrend)) score += 5.0;
            if (trendAlignment) score += 5.0;
        } else if ("BEARISH".equals(primaryTrend)) {
            score -= 10.0;
            if ("BEARISH".equals(shortTermTrend)) score -= 5.0;
            if (trendAlignment) score -= 5.0;
        }

        // Momentum component (±15 points)
        if (macdBullish && rsiBullishZone) score += 10.0;
        else if (macdBearish && rsiBearishZone) score -= 10.0;

        if (rsiOversold) score += 5.0;
        else if (rsiOverbought) score -= 5.0;

        // Volume component (±10 points)
        if (strongVolume && volumeBreakout) score += 10.0;
        else if (volumeRatio < 0.5) score -= 5.0;

        // Structure component (±10 points)
        if (bullishStructure) score += 10.0;
        else if (bearishStructure) score -= 10.0;

        // Risk adjustment (±5 points)
        if (riskRewardRatio >= 2.0) score += 5.0;
        else if (riskRewardRatio < 1.2) score -= 10.0;

        return Math.max(0.0, Math.min(100.0, score));
    }
}