package kd.trading.bot.model.backtest;

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

    @Override
    public String toString() {
        return String.format(
                "BacktestResult{%s %s: %.2f%% return, %.2f%% win rate, %d trades, %.2f%% max DD, PF: %.2f}",
                symbol, timeframe, totalReturnPercent, winRate, totalTrades, maxDrawdownPercent, profitFactor
        );
    }

    // Summary for logging/reporting
    public String getSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("=== BACKTEST SUMMARY for %s (%s) ===\n", symbol, timeframe));
        summary.append(String.format("Period: %s to %s (%d days)\n",
                startDate != null ? startDate.toLocalDate() : "N/A",
                endDate != null ? endDate.toLocalDate() : "N/A",
                getTradingDays()));

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

        if (!success && errorMessage != null) {
            summary.append("\n--- ERROR ---\n");
            summary.append(String.format("Error: %s\n", errorMessage));
        }

        return summary.toString();
    }

    // This method should return double, not Object
    public double getTotalProfitPercent() {
        return totalReturnPercent;
    }
}