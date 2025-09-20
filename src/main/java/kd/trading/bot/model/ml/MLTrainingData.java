// MLTrainingData.java
package kd.trading.bot.model.ml;

import kd.trading.bot.enums.Signal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
public class MLTrainingData {
    // Trade identification
    private String symbol;
    private LocalDateTime timestamp;
    private double entryPrice;
    private double exitPrice;
    private Signal side;

    // Trading algorithm data
    private Integer tradingRule;
    private Double algoScore;
    private double pnlPercent;
    private double holdingTimeHours;
    private boolean isWinning;

    // Features at entry time
    private Double rsi;
    private Double macd;
    private Double volume;
    private Double volatility;
    private Double trendStrength;
    private Double riskRewardRatio;

    // Market conditions
    private String marketCondition;
    private Double priceChange24h;
    private Double volumeRatio;
    private Boolean nearSupport;
    private Boolean nearResistance;
}
