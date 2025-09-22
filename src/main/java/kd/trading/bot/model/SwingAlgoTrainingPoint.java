package kd.trading.bot.model;

import kd.trading.bot.enums.Signal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Training data point capturing SwingAlgo analysis results
 * This represents one moment in time where SwingAlgo made an analysis decision
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwingAlgoTrainingPoint {

    // Basic info
    private LocalDateTime timestamp;
    private String symbol;
    private double price;

    // SwingAlgo predictions/analysis
    private Signal swingAlgoSignal;        // What SwingAlgo predicted: LONG/SHORT/NO_TRADE
    private double swingAlgoScore;         // SwingAlgo confidence score (0-10)
    private double swingAlgoConfidence;    // ML confidence (0-1)

    // SwingAlgo technical features (all the indicators it calculated)
    private Map<String, Object> swingAlgoFeatures;

    // Actual outcomes (what really happened)
    private String actualOutcome;          // STRONG_WIN, WIN, NEUTRAL, LOSS, STRONG_LOSS, HOLDING, NO_TRADE
    private Double actualPnlPercent;       // Actual profit/loss percentage
    private Long tradeDurationHours;       // How long the position was held

    // Metadata
    private String exitReason;             // STOP_LOSS, TAKE_PROFIT, SIGNAL_REVERSAL, MAX_TIME
    private boolean wasTradeOpened;        // Did SwingAlgo signal result in opening a trade?

    /**
     * Check if this was a successful prediction
     */
    public boolean wasSuccessfulPrediction() {
        if (swingAlgoSignal == null || swingAlgoSignal == Signal.NO_TRADE) {
            return "NO_TRADE".equals(actualOutcome) || "HOLDING".equals(actualOutcome);
        }

        if (actualPnlPercent == null) return false;

        // For LONG/SHORT signals, check if prediction direction was correct
        return (swingAlgoSignal == Signal.LONG && actualPnlPercent > 0) ||
                (swingAlgoSignal == Signal.SHORT && actualPnlPercent > 0);
    }

    /**
     * Get prediction accuracy category
     */
    public String getPredictionAccuracy() {
        if (!wasTradeOpened) {
            return (swingAlgoSignal == null || swingAlgoSignal == Signal.NO_TRADE) ? "CORRECT_NO_TRADE" : "MISSED_OPPORTUNITY";
        }

        if (actualPnlPercent == null) return "UNKNOWN";

        if (wasSuccessfulPrediction()) {
            if (actualPnlPercent > 3.0) return "EXCELLENT";
            if (actualPnlPercent > 1.0) return "GOOD";
            return "CORRECT";
        } else {
            if (actualPnlPercent < -3.0) return "TERRIBLE";
            if (actualPnlPercent < -1.0) return "BAD";
            return "WRONG";
        }
    }

    /**
     * FIXED: Convert to training data format for Python ML with proper signal handling
     */
    public Map<String, Object> toMLTrainingData() {
        Map<String, Object> trainingData = new java.util.HashMap<>();

        // Basic info
        trainingData.put("timestamp", timestamp != null ? timestamp.toString() : null);
        trainingData.put("symbol", symbol);
        trainingData.put("price", price);

        // FIXED: SwingAlgo predictions with proper signal conversion
        String signalString = swingAlgoSignal != null ? swingAlgoSignal.toString() : "NO_TRADE";
        trainingData.put("predicted_signal", signalString);
        trainingData.put("swingalgo_signal", signalString); // Keep both for compatibility
        trainingData.put("predicted_score", swingAlgoScore);
        trainingData.put("predicted_confidence", swingAlgoConfidence);

        // Add all SwingAlgo technical features with null-safe handling
        if (swingAlgoFeatures != null && !swingAlgoFeatures.isEmpty()) {
            // Add features with proper key naming
            Map<String, Object> features = new java.util.HashMap<>();
            for (Map.Entry<String, Object> entry : swingAlgoFeatures.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Ensure all feature values are properly formatted
                if (value instanceof Boolean) {
                    features.put(key, ((Boolean) value) ? 1.0 : 0.0);
                } else if (value instanceof Number) {
                    features.put(key, ((Number) value).doubleValue());
                } else if (value != null) {
                    features.put(key, value.toString());
                }
            }
            trainingData.put("swingalgo_features", features);

            // Also add features directly to top level for easier access in ML
            trainingData.putAll(features);
        } else {
            trainingData.put("swingalgo_features", new java.util.HashMap<>());
        }

        // Actual outcomes
        trainingData.put("actual_outcome", actualOutcome);
        trainingData.put("actual_pnl_percent", actualPnlPercent);
        trainingData.put("trade_duration_hours", tradeDurationHours);
        trainingData.put("was_successful", wasSuccessfulPrediction());
        trainingData.put("prediction_accuracy", getPredictionAccuracy());
        trainingData.put("exit_reason", exitReason);
        trainingData.put("was_trade_opened", wasTradeOpened);

        return trainingData;
    }

    /**
     * Get signal as string for logging and display
     */
    public String getSignalAsString() {
        return swingAlgoSignal != null ? swingAlgoSignal.toString() : "NO_TRADE";
    }

    /**
     * Check if this training point has valid SwingAlgo data
     */
    public boolean hasValidSwingAlgoData() {
        return swingAlgoSignal != null &&
                swingAlgoFeatures != null &&
                !swingAlgoFeatures.isEmpty() &&
                actualOutcome != null;
    }

    /**
     * Get feature count for data quality analysis
     */
    public int getFeatureCount() {
        return swingAlgoFeatures != null ? swingAlgoFeatures.size() : 0;
    }

    @Override
    public String toString() {
        return String.format("SwingAlgoTrainingPoint{symbol='%s', time=%s, predicted=%s(%.1f), actual=%s(%.2f%%), accuracy=%s, features=%d}",
                symbol, timestamp, getSignalAsString(), swingAlgoScore, actualOutcome,
                actualPnlPercent != null ? actualPnlPercent : 0.0, getPredictionAccuracy(), getFeatureCount());
    }
}