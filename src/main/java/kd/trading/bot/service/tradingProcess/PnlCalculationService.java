package kd.trading.bot.service.tradingProcess;


import kd.trading.bot.enums.OrderSide;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.PnlResult;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
@Service
@Component
public class PnlCalculationService {

    public PnlResult calculatePnl(OrderDto order, BigDecimal currentPrice) {
        BigDecimal entryPrice = order.getAvgPrice() != null && order.getAvgPrice().compareTo(BigDecimal.ZERO) > 0
                ? order.getAvgPrice()
                : order.getPrice();

        BigDecimal qty = order.getExecutedQty() != null && order.getExecutedQty().compareTo(BigDecimal.ZERO) > 0
                ? order.getExecutedQty()
                : order.getOrigQty();

        boolean isLong = order.getSide() == OrderSide.BUY;

        BigDecimal pnlAbs = isLong
                ? currentPrice.subtract(entryPrice).multiply(qty)
                : entryPrice.subtract(currentPrice).multiply(qty);

        BigDecimal pnlPercent = pnlAbs
                .divide(entryPrice.multiply(qty), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return new PnlResult(currentPrice, entryPrice, qty, pnlAbs, pnlPercent, isLong);
    }

    public Map<OrderDto, PnlResult> calculateBatchPnl(List<OrderDto> orders, List<BinanceTickerData> tickers) {
        Map<OrderDto, PnlResult> results = new LinkedHashMap<>();

        for (OrderDto order : orders) {
            tickers.stream()
                    .filter(t -> t.getSymbol().equalsIgnoreCase(order.getSymbol()))
                    .findFirst()
                    .ifPresent(ticker -> {
                        BigDecimal currentPrice = BigDecimal.valueOf(ticker.getLastPrice());
                        PnlResult pnl = calculatePnl(order, currentPrice);
                        results.put(order, pnl);
                    });
        }

        return results;
    }
}
