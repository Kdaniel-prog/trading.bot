package kd.trading.bot.model.ml;

import kd.trading.bot.enums.Signal;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class MLPredictionResponse {
    // Model metadata
    private String modelVersion;
    private String modelName;
    private Double modelConfidence;

    // Input features used for prediction
    private MLFeatures features;

    // Error handling
    private String errorMessage;

    // Enhanced prediction data
    private Double profitProbability;
    private Double stopLossLevel;
    private Double takeProfitLevel;
    private String predictionReason;

    // Confidence breakdown
    private Double technicalConfidence;
    private Double fundamentalConfidence;
    private Double volumeConfidence;
    private Double trendConfidence;

    // Risk assessment
    private Double maxDrawdownRisk;
    private Double volatilityScore;

    // Prediction details for debugging
    private String rawPredictionData;
    private Double[] modelOutputs;

    // Default builder values
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    @Builder.Default
    private boolean success = false;

    @Builder.Default
    private double confidence = 0.0;

    @Builder.Default
    private Signal predictedSignal = Signal.NO_TRADE;

    @Builder.Default
    private double expectedReturn = 0.0;

    @Builder.Default
    private double riskScore = 1.0;

    @Builder.Default
    private String riskCategory = "HIGH";

    // Helper methods
    public boolean isHighConfidence() {
        return confidence >= 0.75;
    }

    public boolean isMediumConfidence() {
        return confidence >= 0.5 && confidence < 0.75;
    }

    public boolean isLowConfidence() {
        return confidence < 0.5;
    }

    public boolean isAcceptableRisk() {
        return riskScore <= 0.7;
    }

    public boolean shouldTrade() {
        return success &&
                predictedSignal != Signal.NO_TRADE &&
                confidence >= 0.6 &&
                isAcceptableRisk();
    }

    public String getConfidenceLevel() {
        if (isHighConfidence()) return "HIGH";
        if (isMediumConfidence()) return "MEDIUM";
        return "LOW";
    }

    @Override
    public String toString() {
        return String.format("MLPrediction[signal=%s, confidence=%.3f, return=%.2f%%, risk=%.3f, success=%s]",
                predictedSignal, confidence, expectedReturn, riskScore, success);
    }
}