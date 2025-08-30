package kd.trading.bot.util;

import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.model.TradeDto;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradeServiceHelper {
    TradingConfig tradingConfig;

    public TradeDto generateTradeDto(Signal signal, BigDecimal lastPrice, SymbolInfo info) {
        int priceScale = info.getPriceScaleOrDefault();
        int qtyScale = info.getQuantityScaleOrDefault();

        BigDecimal normalizedPrice = lastPrice.setScale(priceScale, RoundingMode.DOWN);
        BigDecimal normalizedQty = calculateQtyForSymbol(info, lastPrice)
                .setScale(qtyScale, RoundingMode.DOWN);

        double stopLimit;
        double winLimit;
        if (signal == Signal.LONG) {
            stopLimit = lastPrice.doubleValue() * (1 - Double.parseDouble(tradingConfig.stopLimit()) / 100.0);
            winLimit  = lastPrice.doubleValue() * (1 + Double.parseDouble(tradingConfig.winLimit()) / 100.0);
        } else {
            stopLimit = lastPrice.doubleValue() * (1 + Double.parseDouble(tradingConfig.stopLimit()) / 100.0);
            winLimit  = lastPrice.doubleValue() * (1 - Double.parseDouble(tradingConfig.winLimit()) / 100.0);
        }

        return TradeDto.builder()
                .signal(signal)
                .entryPrice(normalizedPrice)
                .stopLimit(stopLimit)
                .winLimit(winLimit)
                .openedAt(Instant.now())
                .quantity(normalizedQty)
                .build();
    }

    // --- Qty számítás szűrőkkel ---
    public BigDecimal calculateQtyForSymbol(SymbolInfo info, BigDecimal lastPrice) {
        if (info == null) {
            throw new IllegalArgumentException("SymbolInfo is null");
        }
        SymbolInfo.Filter lotSize = info.getFilter("LOT_SIZE");
        BigDecimal minQty   = new BigDecimal(lotSize.getMinQty());
        BigDecimal maxQty   = new BigDecimal(lotSize.getMaxQty());
        BigDecimal stepSize = new BigDecimal(lotSize.getStepSize());

        SymbolInfo.Filter notionalFilter = info.getFilter("MIN_NOTIONAL");
        BigDecimal minNotional = notionalFilter != null && notionalFilter.getNotional() != null
                ? new BigDecimal(notionalFilter.getNotional())
                : BigDecimal.ZERO;

        BigDecimal usdtBalance = BigDecimal.valueOf(tradingConfig.moneyUsdt());
        BigDecimal rawQty = usdtBalance.divide(lastPrice, 8, RoundingMode.DOWN);

        BigDecimal orderValue = rawQty.multiply(lastPrice);
        if (orderValue.compareTo(minNotional) < 0) {
            rawQty = minNotional.divide(lastPrice, 8, RoundingMode.UP);
        }

        int precision = stepSize.stripTrailingZeros().scale();
        BigDecimal adjusted = rawQty.setScale(precision, RoundingMode.DOWN);

        if (adjusted.compareTo(minQty) < 0) adjusted = minQty;
        if (adjusted.compareTo(maxQty) > 0) adjusted = maxQty;

        return adjusted;
    }
}
