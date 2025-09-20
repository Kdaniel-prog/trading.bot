
// MLFeatures.java
package kd.trading.bot.model.ml;

import kd.trading.bot.enums.Signal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
public class MLFeatures {
    // Basic features
    private String symbol;
    private LocalDateTime timestamp;
    private double currentPrice;
    private double volume;
    private double volumeRatio;

    // Technical indicators
    private double rsi;
    private double macd;
    private double bollinger_upper;
    private double bollinger_lower;
    private double sma_20;
    private double sma_50;
    private double ema_50;

    // Trading algorithm features
    private Integer tradingRule;
    private Double algoScore;
    private Signal signal;

    // Advanced features
    private Double trendStrength;
    private Double momentumScore;
    private Double riskScore;

    // Multi-timeframe features
    private Double price_change_1h;
    private Double price_change_4h;
    private Double price_change_1d;
    private Double volume_change_24h;

    // Market structure features
    private Boolean bullishPattern;
    private Boolean bearishPattern;
    private Double supportDistance;
    private Double resistanceDistance;
}
