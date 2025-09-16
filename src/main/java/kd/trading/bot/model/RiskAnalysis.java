package kd.trading.bot.model;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RiskAnalysis {

    double nearestSupport;
    double nearestResistance;
    double distanceFromSupport;
    double distanceFromResistance;
    double riskRewardRatio;
    double atr;
    double volatilityPercent;
    boolean goodVolatility;
    boolean nearSupport;
    boolean nearResistance;
    boolean highRisk;
    boolean optimalRiskReward;
    double stopLossLevel;
    double takeProfitLevel;

}

