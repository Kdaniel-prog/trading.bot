package kd.trading.bot.model;

import kd.trading.bot.enums.Signal;
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
public class Position {

    private String symbol;
    private Signal side; // LONG or SHORT

    // Entry details
    private double entryPrice;
    private LocalDateTime entryTime;
    private double positionSize;

    // Strategy details
    private Integer tradingRule;
    private double score; // Algorithm confidence score

    // Risk management
    private Double stopLossPrice;
    private Double takeProfitPrice;
    private Double stopLossPercent;
    private Double takeProfitPercent;

    // Position status
    @Builder.Default
    private boolean isOpen = true;
    @Builder.Default
    private String status = "OPEN"; // OPEN, CLOSED, PARTIAL

    // Metadata
    private String notes;
    private LocalDateTime lastUpdateTime;

    // Calculated methods
    public double getCurrentPnl(double currentPrice) {
        if (side == Signal.LONG) {
            return positionSize * (currentPrice - entryPrice);
        } else if (side == Signal.SHORT) {
            return positionSize * (entryPrice - currentPrice);
        }
        return 0.0;
    }

    public double getCurrentPnlPercent(double currentPrice) {
        if (side == Signal.LONG) {
            return (currentPrice - entryPrice) / entryPrice * 100;
        } else if (side == Signal.SHORT) {
            return (entryPrice - currentPrice) / entryPrice * 100;
        }
        return 0.0;
    }

    public long getHoldingTimeHours() {
        if (entryTime != null) {
            return Duration.between(entryTime, LocalDateTime.now()).toHours();
        }
        return 0;
    }

    public long getHoldingTimeMinutes() {
        if (entryTime != null) {
            return Duration.between(entryTime, LocalDateTime.now()).toMinutes();
        }
        return 0;
    }

    public double getPositionValue(double currentPrice) {
        return positionSize * currentPrice;
    }

    public double getInitialValue() {
        return positionSize * entryPrice;
    }

    // Risk management helpers
    public boolean shouldStopLoss(double currentPrice) {
        if (stopLossPrice != null) {
            if (side == Signal.LONG && currentPrice <= stopLossPrice) {
                return true;
            } else if (side == Signal.SHORT && currentPrice >= stopLossPrice) {
                return true;
            }
        }

        if (stopLossPercent != null) {
            double pnlPercent = getCurrentPnlPercent(currentPrice);
            return pnlPercent <= -stopLossPercent;
        }

        return false;
    }

    public boolean shouldTakeProfit(double currentPrice) {
        if (takeProfitPrice != null) {
            if (side == Signal.LONG && currentPrice >= takeProfitPrice) {
                return true;
            } else if (side == Signal.SHORT && currentPrice <= takeProfitPrice) {
                return true;
            }
        }

        if (takeProfitPercent != null) {
            double pnlPercent = getCurrentPnlPercent(currentPrice);
            return pnlPercent >= takeProfitPercent;
        }

        return false;
    }

    @Override
    public String toString() {
        return String.format("Position{symbol='%s', side=%s, entry=%.4f, size=%.6f, rule=%d, pnl=%.2f%% (%dh)}",
                symbol, side, entryPrice, positionSize, tradingRule,
                getCurrentPnlPercent(entryPrice), getHoldingTimeHours());
    }
}