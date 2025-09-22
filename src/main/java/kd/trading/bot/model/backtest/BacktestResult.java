package kd.trading.bot.model.backtest;

import kd.trading.bot.model.SwingAlgoTrainingPoint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestResult {

    // Basic info
    private String symbol;
    private String timeframe;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private List<SwingAlgoTrainingPoint> swingAlgoAnalysisPoints;

    // NEW FIELDS - Directional trading statistics
    private int totalSignals;
    private int holdSignals;
    private int longSignals;
    private int shortSignals;
    private double holdRatio;
    private double longRatio;
    private double shortRatio;
    private int positionsOpened;
    private double positionFillRate;
    private int actionSignals;

    // NEW FIELDS - Data quality tracking
    private String dataQuality; // "GOOD", "ACCEPTABLE", "LIMITED", "EXTENDED"
    private LocalDateTime actualDataStart; // If extended date range was used
    private Map<String, Object> metrics; // Additional metrics storage

    // Balance metrics
    private double initialBalance;
    private double finalBalance;
    private double totalReturnPercent;
    private double maxDrawdownPercent;

    // Trade statistics
    private int totalTrades;
    private int winningTrades;
    private int losingTrades;
    private double winRate; // Percentage

    // Performance metrics
    private double averageWinPercent;
    private double averageLossPercent;
    private double profitFactor; // Gross profit / Gross loss
    private double sharpeRatio;

    // Risk metrics
    private double maxConsecutiveLosses;
    private double maxConsecutiveWins;
    private double largestWinPercent;
    private double largestLossPercent;
    private double averageHoldingTimeHours;

    // Additional metrics
    private double totalFeesPaid;
    private double netProfit;
    private double grossProfit;
    private double grossLoss;

    // Trade list
    private List<BacktestTrade> trades;

    // Success/Error handling
    private boolean success;
    private String errorMessage;

    // Portfolio equity curve (optional)
    private List<Double> portfolioValues;
    private List<LocalDateTime> timestamps;

    // Performance by time period
    private Map<String, Double> monthlyReturns; // "2024-01" -> return%
    private Map<Integer, BacktestRuleAnalysis> ruleAnalysis;

    // UPDATED - Helper methods to handle null metrics safely
    public Object getMetric(String key) {
        return metrics != null ? metrics.get(key) : null;
    }

    public Integer getAvailableCandles() {
        Object value = getMetric("available_candles");
        return value instanceof Integer ? (Integer) value : null;
    }

    public Integer getRequiredMinimum() {
        Object value = getMetric("required_minimum");
        return value instanceof Integer ? (Integer) value : null;
    }

    public Double getDataSufficiencyRatio() {
        Object value = getMetric("data_sufficiency_ratio");
        return value instanceof Double ? (Double) value : null;
    }

    // Calculated properties
    public double getExpectedValue() {
        if (totalTrades == 0) return 0.0;
        return (winRate / 100.0) * averageWinPercent + ((100 - winRate) / 100.0) * averageLossPercent;
    }

    public double getPayoffRatio() {
        if (averageLossPercent == 0) return Double.MAX_VALUE;
        return Math.abs(averageWinPercent / averageLossPercent);
    }

    public double getKellyPercentage() {
        if (averageLossPercent == 0) return 0.0;
        double winProbability = winRate / 100.0;
        double payoffRatio = getPayoffRatio();
        return winProbability - ((1 - winProbability) / payoffRatio);
    }

    public long getTradingDays() {
        if (startDate != null && endDate != null) {
            return java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        }
        return 0;
    }

    public double getAnnualizedReturn() {
        long days = getTradingDays();
        if (days == 0) return 0.0;
        return Math.pow(1 + totalReturnPercent / 100.0, 365.0 / days) - 1.0;
    }

    public double getTradesPerDay() {
        long days = getTradingDays();
        if (days == 0) return 0.0;
        return (double) totalTrades / days;
    }

    // Risk-adjusted returns
    public double getCalmarRatio() {
        if (maxDrawdownPercent == 0) return Double.MAX_VALUE;
        return getAnnualizedReturn() / (maxDrawdownPercent / 100.0);
    }

    public boolean isProfitable() {
        return totalReturnPercent > 0;
    }

    public boolean isViable() {
        return isProfitable() && winRate > 35.0 && profitFactor > 1.2 && maxDrawdownPercent < 30.0;
    }

    // NEW METHODS - Directional trading specific
    public int getActionableSignals() {
        return totalSignals - holdSignals;
    }

    public double getSignalEfficiency() {
        int actionable = getActionableSignals();
        return actionable > 0 ? (double) positionsOpened / actionable * 100 : 0.0;
    }

    public boolean hasGoodDataQuality() {
        return "GOOD".equals(dataQuality) || "ACCEPTABLE".equals(dataQuality);
    }

    public String getDataQualityDescription() {
        if (dataQuality == null) return "Unknown";

        return switch (dataQuality) {
            case "GOOD" -> "Sufficient data for reliable analysis";
            case "ACCEPTABLE" -> "Limited data but analysis is reasonable";
            case "LIMITED" -> "Insufficient data - results may be unreliable";
            case "EXTENDED" -> "Extended date range used to gather sufficient data";
            default -> "Unknown data quality";
        };
    }

    @Override
    public String toString() {
        return String.format(
                "BacktestResult{%s %s: %.2f%% return, %.2f%% win rate, %d trades, %.2f%% max DD, PF: %.2f, Quality: %s}",
                symbol, timeframe, totalReturnPercent, winRate, totalTrades, maxDrawdownPercent, profitFactor,
                dataQuality != null ? dataQuality : "N/A"
        );
    }

    // UPDATED Summary with directional trading info
    public String getSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("=== BACKTEST SUMMARY for %s (%s) ===\n", symbol, timeframe));
        summary.append(String.format("Period: %s to %s (%d days)\n",
                startDate != null ? startDate.toLocalDate() : "N/A",
                endDate != null ? endDate.toLocalDate() : "N/A",
                getTradingDays()));

        // NEW - Data quality section
        summary.append("\n--- DATA QUALITY ---\n");
        summary.append(String.format("Data Quality: %s\n", dataQuality != null ? dataQuality : "Unknown"));
        summary.append(String.format("Description: %s\n", getDataQualityDescription()));
        if (actualDataStart != null) {
            summary.append(String.format("Actual Data Start: %s (extended range)\n", actualDataStart.toLocalDate()));
        }
        Integer availableCandles = getAvailableCandles();
        Integer requiredMinimum = getRequiredMinimum();
        if (availableCandles != null && requiredMinimum != null) {
            summary.append(String.format("Data Coverage: %d/%d candles (%.1f%%)\n",
                    availableCandles, requiredMinimum, (double) availableCandles / requiredMinimum * 100));
        }

        // NEW - Directional trading signals
        if (totalSignals > 0) {
            summary.append("\n--- DIRECTIONAL SIGNALS ---\n");
            summary.append(String.format("Total Signals: %d\n", totalSignals));
            summary.append(String.format("Hold Signals: %d (%.1f%%)\n", holdSignals, holdRatio));
            summary.append(String.format("Long Signals: %d (%.1f%%)\n", longSignals, longRatio));
            summary.append(String.format("Short Signals: %d (%.1f%%)\n", shortSignals, shortRatio));
            summary.append(String.format("Positions Opened: %d/%d actionable (%.1f%% fill rate)\n",
                    positionsOpened, getActionableSignals(), positionFillRate));
        }

        summary.append("\n--- PERFORMANCE ---\n");
        summary.append(String.format("Initial Balance: $%.2f\n", initialBalance));
        summary.append(String.format("Final Balance: $%.2f\n", finalBalance));
        summary.append(String.format("Total Return: %.2f%%\n", totalReturnPercent));
        summary.append(String.format("Annualized Return: %.2f%%\n", getAnnualizedReturn() * 100));
        summary.append(String.format("Max Drawdown: %.2f%%\n", maxDrawdownPercent));

        summary.append("\n--- TRADE STATISTICS ---\n");
        summary.append(String.format("Total Trades: %d\n", totalTrades));
        summary.append(String.format("Winning Trades: %d (%.1f%%)\n", winningTrades, winRate));
        summary.append(String.format("Losing Trades: %d (%.1f%%)\n", losingTrades, 100 - winRate));
        summary.append(String.format("Average Win: %.2f%%\n", averageWinPercent));
        summary.append(String.format("Average Loss: %.2f%%\n", averageLossPercent));

        summary.append("\n--- RISK METRICS ---\n");
        summary.append(String.format("Profit Factor: %.2f\n", profitFactor));
        summary.append(String.format("Sharpe Ratio: %.2f\n", sharpeRatio));
        summary.append(String.format("Payoff Ratio: %.2f\n", getPayoffRatio()));
        summary.append(String.format("Kelly %%: %.2f%%\n", getKellyPercentage() * 100));
        summary.append(String.format("Calmar Ratio: %.2f\n", getCalmarRatio()));

        summary.append("\n--- VIABILITY ---\n");
        summary.append(String.format("Profitable: %s\n", isProfitable() ? "YES" : "NO"));
        summary.append(String.format("Viable Strategy: %s\n", isViable() ? "YES" : "NO"));
        summary.append(String.format("Expected Value: %.2f%%\n", getExpectedValue()));
        summary.append(String.format("Trades per Day: %.2f\n", getTradesPerDay()));

        // UPDATED - Enhanced error reporting
        if (!success && errorMessage != null) {
            summary.append("\n--- ERROR ---\n");
            summary.append(String.format("Error: %s\n", errorMessage));
            if ("LIMITED".equals(dataQuality)) {
                summary.append("Recommendation: Try extending the date range or using a different timeframe\n");
            }
        }

        // NEW - Data quality warnings
        if (!hasGoodDataQuality() && success) {
            summary.append("\n--- WARNINGS ---\n");
            summary.append("⚠️ Data quality is limited - results may not be reliable for live trading\n");
            summary.append("Consider: Extending date range, using different timeframe, or getting more data\n");
        }

        return summary.toString();
    }

    // This method should return double, not Object
    public double getTotalProfitPercent() {
        return totalReturnPercent;
    }

    // BUILDER PATTERN ENHANCEMENT - fluent methods for common operations
    public BacktestResult withDataQuality(String quality) {
        this.dataQuality = quality;
        return this;
    }

    public BacktestResult withExtendedDataStart(LocalDateTime extendedStart) {
        this.actualDataStart = extendedStart;
        return this;
    }

    public BacktestResult withMetrics(Map<String, Object> metrics) {
        this.metrics = metrics;
        return this;
    }

    public BacktestResult addMetric(String key, Object value) {
        if (this.metrics == null) {
            this.metrics = new java.util.HashMap<>();
        }
        this.metrics.put(key, value);
        return this;
    }
}