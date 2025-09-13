package kd.trading.bot.service.tradingProcess;

import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.PnlResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PnlCalculationService {

    public Map<OrderDto, PnlResult> calculateBatchPnl(List<OrderDto> orders, List<BinanceTickerData> tickers) {
        Map<String, BinanceTickerData> tickerMap = tickers.stream()
                .collect(Collectors.toMap(BinanceTickerData::getSymbol, t -> t));

        return orders.stream()
                .collect(Collectors.toMap(
                        order -> order,
                        order -> {
                            BinanceTickerData ticker = tickerMap.get(order.getSymbol());
                            if (ticker != null) {
                                return calculatePnl(order, ticker);
                            } else {
                                // No ticker data - show with 0% PnL using entry price
                                return createZeroPnlResult(order);
                            }
                        }
                ));
    }

    private PnlResult calculatePnl(OrderDto order, BinanceTickerData ticker) {
        return calculatePnlWithPrice(order, BigDecimal.valueOf(ticker.getLastPrice()));
    }

    private PnlResult createZeroPnlResult(OrderDto order) {
        BigDecimal entryPrice = getEntryPrice(order);
        BigDecimal qty = getQuantity(order);
        boolean isLong = isLongPosition(order);

        return PnlResult.builder()
                .entryPrice(entryPrice)
                .currentPrice(entryPrice) // Same as entry = 0% PnL
                .qty(qty)
                .pnlAbs(BigDecimal.ZERO)
                .pnlPercent(BigDecimal.ZERO)
                .isLong(isLong)
                .build();
    }

    private PnlResult calculatePnlWithPrice(OrderDto order, BigDecimal currentPrice) {
        BigDecimal entryPrice = getEntryPrice(order);
        BigDecimal qty = getQuantity(order);
        boolean isLong = isLongPosition(order);

        BigDecimal pnlAbs = calculatePnlAmount(entryPrice, currentPrice, qty, isLong);
        BigDecimal pnlPercent = calculatePnlPercent(entryPrice, currentPrice, isLong);

        return PnlResult.builder()
                .entryPrice(entryPrice)
                .currentPrice(currentPrice)
                .qty(qty)
                .pnlAbs(pnlAbs)
                .pnlPercent(pnlPercent)
                .isLong(isLong)
                .build();
    }

    private BigDecimal getEntryPrice(OrderDto order) {
        // Use avgPrice if available (for filled orders), otherwise use price
        return order.getAvgPrice() != null && order.getAvgPrice().compareTo(BigDecimal.ZERO) > 0
                ? order.getAvgPrice()
                : order.getPrice();
    }

    private BigDecimal getQuantity(OrderDto order) {
        // Use executedQty if available (for filled orders), otherwise use origQty
        return order.getExecutedQty() != null && order.getExecutedQty().compareTo(BigDecimal.ZERO) > 0
                ? order.getExecutedQty()
                : order.getOrigQty();
    }

    private boolean isLongPosition(OrderDto order) {
        // For futures: LONG positionSide = long, SHORT = short
        // For spot: BUY side = long, SELL = short
        // NOTE: Binance futures returns opposite side, so we use NOT operator
        if (order.getSide() != null) {
            return "BUY".equals(order.getSide().toString());
        }
        // Fallback to order side
        return "BUY".equals(order.getSide().toString());
    }

    private BigDecimal calculatePnlAmount(BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal qty, boolean isLong) {
        BigDecimal priceDiff = isLong
                ? currentPrice.subtract(entryPrice)   // For short: profit when current < entry
                : entryPrice.subtract(currentPrice);    // For long: profit when current > entry

        return priceDiff.multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePnlPercent(BigDecimal entryPrice, BigDecimal currentPrice, boolean isLong) {
        if (entryPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        BigDecimal priceDiff = isLong
                ? currentPrice.subtract(entryPrice)   // For short: profit when current < entry
                : entryPrice.subtract(currentPrice);  // For long: profit when current > entry

        return priceDiff.divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }
}