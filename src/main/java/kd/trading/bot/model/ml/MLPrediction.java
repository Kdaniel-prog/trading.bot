package kd.trading.bot.model.ml;

import kd.trading.bot.enums.Signal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MLPrediction {

    private boolean success;
    private String errorMessage;

    // Prediction results
    private Signal predictedSignal;
    private double confidence; // 0.0 to 1.0
    private double expectedReturn; // Predicted return %
    private double riskScore; // 0.0 to 1.0

    // Probabilities
    private double longProbability;
    private double shortProbability;
    private double noTradeProbability;

    // Model info
    private String modelName;
    private String modelVersion;
    private LocalDateTime predictionTime;

    // Feature importance (optional)
    private Map<String, Double> featureImportance;

    // Confidence intervals
    private double returnLowerBound;
    private double returnUpperBound;

    // Decision thresholds
    private double minConfidenceThreshold = 0.6;
    private double minExpectedReturn = 2.0; // Minimum 2% expected return

    // Decision logic
    public boolean shouldTrade() {
        return success
                && confidence >= minConfidenceThreshold
                && Math.abs(expectedReturn) >= minExpectedReturn
                && predictedSignal != Signal.NO_TRADE;
    }

    public boolean isHighConfidence() {
        return confidence >= 0.8;
    }

    public boolean isLowRisk() {
        return riskScore <= 0.3;
    }

    public String getRecommendation() {
        if (!success) return "ERROR";
        if (!shouldTrade()) return "NO_TRADE";
        if (isHighConfidence() && isLowRisk()) return "STRONG_" + predictedSignal;
        if (confidence >= 0.7) return "MODERATE_" + predictedSignal;
        return "WEAK_" + predictedSignal;
    }
}