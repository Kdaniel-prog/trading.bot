package kd.trading.bot.model;

import kd.trading.bot.enums.Signal;
import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class CoinAnalysis {
    private String symbol;
    private double score;  // 0-100
    private Signal signal; // LONG / SHORT / NO_TRADE
    private Double lastPrice;
    private Double stopLoss;
    private Double takeProfit;

    public CoinAnalysis(String symbol, double score, Signal signal, double lastPrice) {
        this.symbol = symbol;
        this.score = score;
        this.signal = signal;
        this.lastPrice = lastPrice;
    }
}

