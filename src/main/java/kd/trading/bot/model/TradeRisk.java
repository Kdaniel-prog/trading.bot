package kd.trading.bot.model;


import kd.trading.bot.enums.RiskLevel;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class TradeRisk {
    // Getters
    private final OrderDto order;
    private final PnlResult pnl;
    private final RiskLevel riskLevel;
    private final String reason;

    public TradeRisk(OrderDto order, PnlResult pnl) {
        this.order = order;
        this.pnl = pnl;
        this.riskLevel = calculateRiskLevel(pnl);
        this.reason = generateReason(pnl, order);
    }

    private RiskLevel calculateRiskLevel(PnlResult pnl) {
        BigDecimal absPercent = pnl.getPnlPercent().abs();

        if (absPercent.compareTo(BigDecimal.valueOf(10.0)) >= 0) {
            return RiskLevel.CRITICAL;
        } else if (absPercent.compareTo(BigDecimal.valueOf(5.0)) >= 0) {
            return RiskLevel.HIGH;
        } else if (absPercent.compareTo(BigDecimal.valueOf(2.0)) >= 0) {
            return RiskLevel.MEDIUM;
        } else {
            return RiskLevel.LOW;
        }
    }

    private String generateReason(PnlResult pnl, OrderDto order) {
        BigDecimal percent = pnl.getPnlPercent();

        if (percent.compareTo(BigDecimal.ZERO) < 0) {
            return String.format("Loss of %.2f%% on %s", percent.abs(), order.getSymbol());
        } else {
            return String.format("High gain of %.2f%% on %s", percent, order.getSymbol());
        }
    }

    public boolean isCritical() {
        return riskLevel == RiskLevel.CRITICAL;
    }

    public boolean isHighRisk() {
        return riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL;
    }
}
