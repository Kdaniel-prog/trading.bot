package kd.trading.bot.model;

import lombok.Data;

import java.time.Instant;

@Data
public class TradeDto {
    private String symbol;
    private Signal signal;
    private double entryPrice;
    private double stopLimit;
    private double winLimit;
    private Instant openedAt;
}