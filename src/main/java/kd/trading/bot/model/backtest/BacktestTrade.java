package kd.trading.bot.model.backtest;

import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.CoinAnalysis;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.Duration;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestTrade {

    private String symbol;
    private Signal side; // LONG or SHORT

    // Entry details
    private double entryPrice;
    private LocalDateTime entryTime;

    // Exit details
    private double exitPrice;
    private LocalDateTime exitTime;

    // Position details
    private double positionSize;
    private double quantity; // Add this field

    // P&L calculations
    private double pnl; // Absolute profit/loss
    private double pnlPercent; // Percentage profit/loss

    // Strategy details
    private Integer tradingRule;
    private double score; // Algorithm confidence score

    // Trade metadata
    private String exitReason; // "SIGNAL", "STOP_LOSS", "TAKE_PROFIT", "TIME_EXIT"

    // Fixed methods that were returning void
    // Technical analysis context
    private CoinAnalysis technicalIndicators; // Add this field

    // Calculated fields
    public long getHoldingTimeHours() {
        if (entryTime != null && exitTime != null) {
            return Duration.between(entryTime, exitTime).toHours();
        }
        return 0;
    }

    public long getHoldingTimeMinutes() {
        if (entryTime != null && exitTime != null) {
            return Duration.between(entryTime, exitTime).toMinutes();
        }
        return 0;
    }

    public boolean isWinningTrade() {
        return pnl > 0;
    }

    public boolean isLosingTrade() {
        return pnl < 0;
    }

    public double getReturnOnInvestment() {
        if (entryPrice > 0 && positionSize > 0) {
            double investment = entryPrice * positionSize;
            return (pnl / investment) * 100;
        }
        return 0.0;
    }

    // Risk metrics
    public double getRiskRewardRatio() {
        if (pnlPercent < 0) {
            return Math.abs(pnlPercent); // Risk taken
        }
        return 0.0;
    }

    public double getRewardRiskRatio() {
        if (pnlPercent > 0) {
            return pnlPercent; // Reward achieved
        }
        return 0.0;
    }

    @Override
    public String toString() {
        return String.format("BacktestTrade{symbol='%s', side=%s, entry=%.4f@%s, exit=%.4f@%s, pnl=%.2f (%.2f%%), rule=%d, holding=%dh}",
                symbol, side, entryPrice, entryTime, exitPrice, exitTime, pnl, pnlPercent, tradingRule, getHoldingTimeHours());
    }
}