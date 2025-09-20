// RiskAnalysis.java
package kd.trading.bot.model.analysis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RiskAnalysis {
    private double nearestSupport;
    private double nearestResistance;
    private double distanceFromSupport;
    private double distanceFromResistance;
    private double riskRewardRatio;
    private double atr;
    private double volatilityPercent;
    private double shortTermVolatility;
    private boolean goodVolatility;
    private boolean goodShortTermVolatility;
    private boolean nearSupport;
    private boolean nearResistance;
    private boolean highRisk;
    private boolean optimalRiskReward;
    private double stopLossLevel;
    private double takeProfitLevel;
}