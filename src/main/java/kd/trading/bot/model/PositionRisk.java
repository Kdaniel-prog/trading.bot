package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class PositionRisk {
    private String symbol;
    private BigDecimal positionAmt;
    private BigDecimal entryPrice;
    private BigDecimal breakEvenPrice;
    private BigDecimal markPrice;
    private BigDecimal unRealizedProfit;
    private BigDecimal liquidationPrice;
    private BigDecimal leverage;
    private BigDecimal maxNotionalValue;
    private String marginType;
    private BigDecimal isolatedMargin;
    private Boolean isAutoAddMargin;
    private String positionSide;
    private BigDecimal notional;
    private BigDecimal isolatedWallet;
    private Long updateTime;
    private Boolean isolated;
    private Integer adlQuantile;
}