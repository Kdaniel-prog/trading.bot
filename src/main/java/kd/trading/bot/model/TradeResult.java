package kd.trading.bot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TradeResult {
    private String symbol;
    private String side;
    private double qty;
    private double entryPrice;
    private double exitPrice;
    private double profit;
    private double profitPercent;
}
