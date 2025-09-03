package kd.trading.bot.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class PnlResult {
    BigDecimal currentPrice;
    BigDecimal entryPrice;
    BigDecimal qty;
    BigDecimal pnlAbs;
    BigDecimal pnlPercent;
    boolean isLong;
}
