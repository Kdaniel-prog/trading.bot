package kd.trading.bot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@Builder
@RequiredArgsConstructor
public class PnlResult {
    BigDecimal currentPrice;
    BigDecimal entryPrice;
    BigDecimal qty;
    BigDecimal pnlAbs;
    BigDecimal pnlPercent;
    boolean isLong;
}
