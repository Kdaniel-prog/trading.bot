package kd.trading.bot.model.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Model for sending trade results back to ML for continuous learning
 * Contains actual trade outcome data for model improvement
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MLTradeResult {

    // Trade identification
    private String symbol;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private String side; // LONG, SHORT

    // Price and quantity data
    private double entryPrice;
    private double exitPrice;
    private double quantity;

    // Performance metrics
    private double realizedPnl; // Actual profit/loss in USDT
    private double pnlPercent; // Percentage return
    private int durationMinutes; // How long the trade was open
    private boolean isWinning; // true if profitable

    // Technical analysis context (if available)
    private Double rsiAtEntry;
    private Double rsiAtExit;
    private Double volumeRatio;
    private String trendDirection;
    private Double volatility;

    // ML-specific metadata
    private String modelVersion; // Which model made the prediction
    private Double originalConfidence; // Model confidence at entry
    private String originalPrediction; // Original ML prediction
    private String actualOutcome; // Actual result vs prediction

    /**
     * Calculate return on investment percentage
     */
    public double getROIPercent() {
        if (entryPrice > 0 && quantity > 0) {
            double investment = entryPrice * quantity;
            return (realizedPnl / investment) * 100.0;
        }
        return 0.0;
    }

    /**
     * Get trade duration in hours
     */
    public double getDurationHours() {
        return durationMinutes / 60.0;
    }

    /**
     * Get trade performance category
     */
    public String getPerformanceCategory() {
        if (pnlPercent > 5.0) return "EXCELLENT";
        if (pnlPercent > 2.0) return "GOOD";
        if (pnlPercent > -2.0) return "NEUTRAL";
        if (pnlPercent > -5.0) return "BAD";
        return "TERRIBLE";
    }

    /**
     * Check if trade met expectations based on original prediction
     */
    public boolean metExpectations() {
        if (originalPrediction == null) return false;

        switch (originalPrediction.toUpperCase()) {
            case "LONG":
                return pnlPercent > 0;
            case "SHORT":
                return pnlPercent > 0;
            case "NO_TRADE":
                return true; // Model correctly avoided trading
            default:
                return false;
        }
    }

    /**
     * Convert to JSON format for Python ML service
     */
    public String toJson() {
        return String.format("""
            {
                "symbol": "%s",
                "entry_time": "%s",
                "exit_time": "%s", 
                "side": "%s",
                "entry_price": %.8f,
                "exit_price": %.8f,
                "quantity": %.8f,
                "realized_pnl": %.8f,
                "pnl_percent": %.4f,
                "duration_minutes": %d,
                "is_winning": %b,
                "rsi_at_entry": %s,
                "rsi_at_exit": %s,
                "volume_ratio": %s,
                "trend_direction": "%s",
                "volatility": %s,
                "model_version": "%s",
                "original_confidence": %s,
                "original_prediction": "%s",
                "performance_category": "%s",
                "met_expectations": %b
            }""",
                symbol,
                entryTime,
                exitTime,
                side,
                entryPrice,
                exitPrice,
                quantity,
                realizedPnl,
                pnlPercent,
                durationMinutes,
                isWinning,
                formatDoubleForJson(rsiAtEntry),
                formatDoubleForJson(rsiAtExit),
                formatDoubleForJson(volumeRatio),
                trendDirection != null ? trendDirection : "UNKNOWN",
                formatDoubleForJson(volatility),
                modelVersion != null ? modelVersion : "unknown",
                formatDoubleForJson(originalConfidence),
                originalPrediction != null ? originalPrediction : "UNKNOWN",
                getPerformanceCategory(),
                metExpectations()
        );
    }

    private String formatDoubleForJson(Double value) {
        return value != null ? String.format("%.4f", value) : "null";
    }
}