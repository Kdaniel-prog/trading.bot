package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceOrder {
    private String symbol;
    private Long orderId;
    private String clientOrderId;
    private String status;
    private String type;
    private String side;
    private BigDecimal origQty;
    private BigDecimal executedQty;
    private BigDecimal avgPrice;
    private Long updateTime;
}
