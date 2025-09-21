package kd.trading.bot.model;

import kd.trading.bot.enums.Direction;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.service.ratingProcess.algorithm.SwingAlgoService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced CoinAnalysis with pure technical analysis + ML integration
 * Separates technical calculations from trading decisions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoinAnalysis {
    private Direction direction;                    // ÚJ
    private Map<String, Double> probabilities;      // ÚJ
    private String symbol;
    private double score;              // Final combined score (0-10)
    private Signal signal;             // Final trading decision (from ML)
    private double lastPrice;

    // Original constructor for backward compatibility
    public CoinAnalysis(String symbol, double score, Signal signal, double lastPrice) {
        this.symbol = symbol;
        this.score = score;
        this.signal = signal;
        this.lastPrice = lastPrice;
    }

    // === ML INTEGRATION ===
    private MLPredictionResponse mlPrediction;
    private double mlConfidence;
    private String analysisReason;     // Why NO_TRADE or reason for analysis

    // === TECHNICAL ANALYSIS DATA ===
    private TechnicalIndicators technicalIndicators;

    // === LEGACY SUPPORT (for backtesting) ===
    private Double longScore;
    private Double shortScore;
    private Integer tradingRule;

    // === DEPRECATED - for backward compatibility ===
    @Deprecated
    private TrendAnalysis trendAnalysis;
    @Deprecated
    private MomentumAnalysis momentumAnalysis;
    @Deprecated
    private VolumeAnalysis volumeAnalysis;
    @Deprecated
    private RiskAnalysis riskAnalysis;
    @Deprecated
    private StructureAnalysis structureAnalysis;

    /**
     * Extract ML features from technical indicators
     */
    public Map<String, Object> extractMlFeatures() {
        Map<String, Object> features = new HashMap<>();

        features.put("symbol", symbol);
        features.put("currentPrice", lastPrice);
        features.put("timestamp", System.currentTimeMillis());

        if (technicalIndicators != null) {
            // Trend features
            features.put("ema20_4h", technicalIndicators.getEma20_4h());
            features.put("ema50_4h", technicalIndicators.getEma50_4h());
            features.put("ema200_daily", technicalIndicators.getEma200_daily());
            features.put("primaryTrend", technicalIndicators.getPrimaryTrend());
            features.put("shortTermTrend", technicalIndicators.getShortTermTrend());
            features.put("trendAlignment", technicalIndicators.isTrendAlignment());
            features.put("trendStrength", technicalIndicators.getTrendStrength());

            // Momentum features
            features.put("rsi", technicalIndicators.getRsi());
            features.put("macdLine", technicalIndicators.getMacdLine());
            features.put("macdSignal", technicalIndicators.getMacdSignal());
            features.put("macdBullish", technicalIndicators.isMacdBullish());
            features.put("macdBearish", technicalIndicators.isMacdBearish());
            features.put("rsiBullishZone", technicalIndicators.isRsiBullishZone());
            features.put("rsiBearishZone", technicalIndicators.isRsiBearishZone());
            features.put("rsiOversold", technicalIndicators.isRsiOversold());
            features.put("rsiOverbought", technicalIndicators.isRsiOverbought());
            features.put("rsiRising", technicalIndicators.isRsiRising());

            // Volume features
            features.put("volumeRatio", technicalIndicators.getVolumeRatio());
            features.put("strongVolume", technicalIndicators.isStrongVolume());
            features.put("volumeBreakout", technicalIndicators.isVolumeBreakout());
            features.put("volumeTrendUp", technicalIndicators.isVolumeTrendUp());

            // Risk features
            features.put("volatilityPercent", technicalIndicators.getVolatilityPercent());
            features.put("riskRewardRatio", technicalIndicators.getRiskRewardRatio());
            features.put("distanceFromSupport", technicalIndicators.getDistanceFromSupport());
            features.put("distanceFromResistance", technicalIndicators.getDistanceFromResistance());

            // Structure features
            features.put("bullishStructure", technicalIndicators.isBullishMarketStructure());
            features.put("bearishStructure", technicalIndicators.isBearishMarketStructure());
            features.put("consolidation", technicalIndicators.isConsolidation());
        }

        return features;
    }

    /**
     * Get technical confidence (0-1 scale) from technical indicators
     */
    public double getTechnicalConfidence() {
        if (technicalIndicators == null) return 0.0;
        return technicalIndicators.getTechnicalScore() / 100.0;
    }

    /**
     * Check if ML made the final decision
     */
    public boolean isMlDecision() {
        return mlPrediction != null && signal == mlPrediction.getPredictedSignal();
    }

    /**
     * Get decision source description
     */
    public String getDecisionSource() {
        if (signal == Signal.NO_TRADE) {
            return analysisReason != null ? analysisReason : "NO_TRADE";
        }

        if (isMlDecision()) {
            return String.format("ML (%.1f%% confidence)", mlConfidence * 100);
        }

        return "TECHNICAL_FALLBACK";
    }

    /**
     * Get comprehensive analysis summary
     */
    public String getAnalysisSummary() {
        if (technicalIndicators == null) {
            return String.format("%s: %s (%.2f) - %s", symbol, signal, score, getDecisionSource());
        }

        return String.format("%s: %s (%.2f) | %s | Decision: %s",
                symbol, signal, score,
                technicalIndicators.getSummary(),
                getDecisionSource());
    }

    /**
     * Check if this is a high-confidence signal
     */
    public boolean isHighConfidenceSignal() {
        if (signal == Signal.NO_TRADE) return false;

        if (isMlDecision()) {
            return mlConfidence > 0.75;
        }

        return getTechnicalConfidence() > 0.7;
    }

    /**
     * Get risk level assessment
     */
    public String getRiskLevel() {
        if (technicalIndicators == null) return "UNKNOWN";

        double volatility = technicalIndicators.getVolatilityPercent();
        double riskReward = technicalIndicators.getRiskRewardRatio();

        if (volatility > 10.0 || riskReward < 1.2) return "HIGH";
        if (volatility > 6.0 || riskReward < 1.5) return "MEDIUM";
        return "LOW";
    }

    /**
     * Legacy method for backward compatibility
     */
    @Deprecated
    public boolean isStrongTraditionalSignal() {
        return isHighConfidenceSignal();
    }

    /**
     * Legacy method for backward compatibility
     */
    @Deprecated
    public double getTraditionalConfidence() {
        return getTechnicalConfidence();
    }

    /**
     * Legacy method for backward compatibility
     */
    @Deprecated
    public String getSignalStrengthDescription() {
        if (isHighConfidenceSignal()) return "STRONG";
        if (score > 5.0) return "MODERATE";
        if (score > 2.0) return "WEAK";
        return "NO_SIGNAL";
    }
}