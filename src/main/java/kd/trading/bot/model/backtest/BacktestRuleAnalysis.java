package kd.trading.bot.model.backtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestRuleAnalysis {

    // Rule identifier
    private Integer tradingRule;
    private String ruleName;
    private String ruleDescription;

    // Basic statistics
    private int totalTrades;
    private int winningTrades;
    private int losingTrades;
    private double winRate; // Percentage

    // Performance metrics
    private double totalPnl;
    private double averagePnlPercent;
    private double bestTradePercent;
    private double worstTradePercent;

    // Risk metrics
    private double maxDrawdownPercent;
    private double profitFactor;
    private double averageHoldingTimeHours;

    // Trade frequency
    private double tradesPerWeek;
    private double avgDaysBetweenTrades;

    // Detailed trade list
    private List<BacktestTrade> trades;

    // Score analysis
    private double averageScore;
    private double minScore;
    private double maxScore;
    private double scoreStandardDeviation;

    // Performance by score ranges
    private RulePerformanceByScore highScoreTrades; // score > 0.7
    private RulePerformanceByScore mediumScoreTrades; // 0.4 < score <= 0.7
    private RulePerformanceByScore lowScoreTrades; // score <= 0.4

    // Time-based analysis
    private double morningPerformance;   // 06:00-12:00
    private double afternoonPerformance; // 12:00-18:00
    private double eveningPerformance;   // 18:00-00:00
    private double nightPerformance;     // 00:00-06:00

    // Calculated properties
    public double getPayoffRatio() {
        if (totalTrades == 0) return 0.0;

        double avgWin = trades.stream()
                .filter(BacktestTrade::isWinningTrade)
                .mapToDouble(BacktestTrade::getPnlPercent)
                .average()
                .orElse(0.0);

        double avgLoss = Math.abs(trades.stream()
                .filter(BacktestTrade::isLosingTrade)
                .mapToDouble(BacktestTrade::getPnlPercent)
                .average()
                .orElse(0.0));

        return avgLoss > 0 ? avgWin / avgLoss : Double.MAX_VALUE;
    }

    public double getExpectedValue() {
        if (totalTrades == 0) return 0.0;
        return (winRate / 100.0) * getBestTradePercent() + ((100 - winRate) / 100.0) * getWorstTradePercent();
    }

    public boolean isEffectiveRule() {
        return winRate > 50.0 && totalPnl > 0 && totalTrades >= 10 && profitFactor > 1.2;
    }

    public String getRuleEffectiveness() {
        if (totalTrades < 10) return "INSUFFICIENT_DATA";
        if (winRate > 60 && profitFactor > 1.5) return "EXCELLENT";
        if (winRate > 50 && profitFactor > 1.2) return "GOOD";
        if (winRate > 40 && profitFactor > 1.0) return "MARGINAL";
        return "POOR";
    }

    // Inner class for performance by score ranges
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RulePerformanceByScore {
        private int tradeCount;
        private double winRate;
        private double avgPnlPercent;
        private double totalPnl;
        private double profitFactor;
    }

    @Override
    public String toString() {
        return String.format(
                "Rule %d: %d trades, %.1f%% win rate, %.2f%% avg PnL, PF: %.2f (%s)",
                tradingRule, totalTrades, winRate, averagePnlPercent, profitFactor, getRuleEffectiveness()
        );
    }

    public String getDetailedSummary() {
        return String.format("""
            === Trading Rule %d Analysis ===
            %s
            
            Performance:
            - Total Trades: %d
            - Win Rate: %.1f%% (%d wins, %d losses)
            - Average P&L: %.2f%%
            - Best Trade: %.2f%%, Worst Trade: %.2f%%
            - Total P&L: %.2f
            - Profit Factor: %.2f
            - Payoff Ratio: %.2f
            - Max Drawdown: %.2f%%
            
            Score Analysis:
            - Average Score: %.3f (±%.3f)
            - Score Range: %.3f to %.3f
            - High Score Performance: %.1f%% win rate
            - Low Score Performance: %.1f%% win rate
            
            Trading Frequency:
            - Trades per Week: %.1f
            - Average Days Between Trades: %.1f
            - Average Holding Time: %.1f hours
            
            Time Performance:
            - Morning: %.2f%%
            - Afternoon: %.2f%%
            - Evening: %.2f%%
            - Night: %.2f%%
            
            Effectiveness: %s
            """,
                tradingRule,
                ruleDescription != null ? ruleDescription : "No description",
                totalTrades, winRate, winningTrades, losingTrades,
                averagePnlPercent, bestTradePercent, worstTradePercent,
                totalPnl, profitFactor, getPayoffRatio(), maxDrawdownPercent,
                averageScore, scoreStandardDeviation, minScore, maxScore,
                highScoreTrades != null ? highScoreTrades.getWinRate() : 0.0,
                lowScoreTrades != null ? lowScoreTrades.getWinRate() : 0.0,
                tradesPerWeek, avgDaysBetweenTrades, averageHoldingTimeHours,
                morningPerformance, afternoonPerformance, eveningPerformance, nightPerformance,
                getRuleEffectiveness()
        );
    }
}