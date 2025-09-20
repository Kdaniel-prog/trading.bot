package kd.trading.bot.model;

import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.ml.MLPredictionResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

// Enhanced CoinAnalysis with ML integration
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoinAnalysis {
    private String symbol;
    private double score;
    private Signal signal;
    private double lastPrice;

    // Original constructor for backward compatibility
    public CoinAnalysis(String symbol, double score, Signal signal, double lastPrice) {
        this.symbol = symbol;
        this.score = score;
        this.signal = signal;
        this.lastPrice = lastPrice;
    }

    private MLPredictionResponse mlPrediction;
    private double mlConfidence;
    private double traditionalScore;
    private String combinationMethod;

    // NEW: Detailed scoring for backtesting
    private Double longScore;
    private Double shortScore;
    private Integer tradingRule;

    // NEW: Analysis components for ML feature extraction
    private TrendAnalysis trendAnalysis;
    private MomentumAnalysis momentumAnalysis;
    private VolumeAnalysis volumeAnalysis;
    private RiskAnalysis riskAnalysis;
    private StructureAnalysis structureAnalysis;

    /**
     * Extract features for ML training (same format as prepareMlData)
     */
    public Map<String, Object> extractMlFeatures() {
        Map<String, Object> features = new HashMap<>();

        features.put("symbol", symbol);
        features.put("currentPrice", lastPrice);

        if (trendAnalysis != null) {
            features.put("trendAnalysis", Map.of(
                    "primaryTrend", trendAnalysis.getPrimaryTrend(),
                    "shortTermTrend", trendAnalysis.getShortTermTrend(),
                    "trendAlignment", trendAnalysis.isTrendAlignment(),
                    "trendStrength", trendAnalysis.getTrendStrength(),
                    "ema20_4h", trendAnalysis.getEma20_4h(),
                    "ema50_4h", trendAnalysis.getEma50_4h()
            ));
        }

        if (momentumAnalysis != null) {
            features.put("momentumAnalysis", Map.of(
                    "rsi", momentumAnalysis.getRsi(),
                    "macdBullish", momentumAnalysis.isMacdBullish(),
                    "macdBearish", momentumAnalysis.isMacdBearish(),
                    "rsiBullishZone", momentumAnalysis.isRsiBullishZone(),
                    "rsiBearishZone", momentumAnalysis.isRsiBearishZone(),
                    "rsiRising", momentumAnalysis.isRsiRising()
            ));
        }

        if (volumeAnalysis != null) {
            features.put("volumeAnalysis", Map.of(
                    "volumeRatio", volumeAnalysis.getVolumeRatio(),
                    "strongVolume", volumeAnalysis.isStrongVolume(),
                    "volumePercentile", volumeAnalysis.getVolumePercentile(),
                    "volumeBreakout", volumeAnalysis.isVolumeBreakout(),
                    "volumeTrendUp", volumeAnalysis.isVolumeTrendUp()
            ));
        }

        if (riskAnalysis != null) {
            features.put("riskAnalysis", Map.of(
                    "riskRewardRatio", riskAnalysis.getRiskRewardRatio(),
                    "volatilityPercent", riskAnalysis.getVolatilityPercent(),
                    "distanceFromSupport", riskAnalysis.getDistanceFromSupport(),
                    "distanceFromResistance", riskAnalysis.getDistanceFromResistance(),
                    "goodVolatility", riskAnalysis.isGoodVolatility(),
                    "nearSupport", riskAnalysis.isNearSupport()
            ));
        }

        if (structureAnalysis != null) {
            features.put("structureAnalysis", Map.of(
                    "higherHighs", structureAnalysis.isHigherHighs(),
                    "lowerLows", structureAnalysis.isLowerLows(),
                    "bullishPattern", structureAnalysis.isBullishPattern(),
                    "bearishPattern", structureAnalysis.isBearishPattern(),
                    "structureStrength", structureAnalysis.getStructureStrength()
            ));
        }

        return features;
    }

    /**
     * Get traditional signal confidence (0-1 scale)
     */
    public double getTraditionalConfidence() {
        double maxScore = Math.max(Math.abs(longScore != null ? longScore : 0.0),
                Math.abs(shortScore != null ? shortScore : 0.0));
        return Math.min(1.0, maxScore / 15.0); // Normalize to 0-1
    }

    /**
     * Check if this is a strong traditional signal
     */
    public boolean isStrongTraditionalSignal() {
        return getTraditionalConfidence() > 0.6 && signal != Signal.NO_TRADE;
    }

    /**
     * Get signal strength description
     */
    public String getSignalStrengthDescription() {
        double confidence = getTraditionalConfidence();
        if (confidence > 0.8) return "STRONG";
        if (confidence > 0.6) return "MODERATE";
        if (confidence > 0.4) return "WEAK";
        return "NO_SIGNAL";
    }


}