package kd.trading.bot.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class CoinAnalysis {
    private String symbol;
    private double score;  // 0-100
    private Signal signal; // LONG / SHORT / NO_TRADE
    private Double lastPrice;
}

