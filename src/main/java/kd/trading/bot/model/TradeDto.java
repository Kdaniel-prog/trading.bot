package kd.trading.bot.model;

import kd.trading.bot.enums.Signal;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class TradeDto {
    private Signal signal;
    private SymbolInfo symbol;
    private BigDecimal entryPrice;
    private BigDecimal quantity;
}