package kd.trading.bot.model;

import kd.trading.bot.enums.Signal;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class TradeDto {
    private SymbolInfo symbol;
    private Signal signal;
    private BigDecimal entryPrice;
    private double stopLimit;
    private double winLimit;
    private Instant openedAt;
    private BigDecimal quantity;
}