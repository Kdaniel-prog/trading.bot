package kd.trading.bot.service;

import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.OrderSide;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.util.MessageParser;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradeCheckingService {
    final MessageParser parser;
    final TradingConfig tradingConfig;
    final TradeService tradeService;

    @Getter
    StringBuilder sb;

    public void calculateProfit(String message) {
        List<BinanceTickerData> tickers = parser.parseMarketMessage(message);
        if (tickers.isEmpty()) return;

        sb = new StringBuilder("\n====== Active Trades ======\n");
        BigDecimal leverage = BigDecimal.valueOf(tradingConfig.leverage()); // fix x20

        for (OrderDto order : TradeService.getActiveOrderList()) {
            tickers.stream()
                    .filter(t -> t.getSymbol().equalsIgnoreCase(order.getSymbol()))
                    .findFirst()
                    .ifPresent(ticker -> {
                        BigDecimal currentPrice = BigDecimal.valueOf(ticker.getLastPrice());

                        BigDecimal entryPrice = order.getAvgPrice() != null && order.getAvgPrice().compareTo(BigDecimal.ZERO) > 0
                                ? order.getAvgPrice()
                                : order.getPrice();

                        BigDecimal qty = order.getExecutedQty() != null && order.getExecutedQty().compareTo(BigDecimal.ZERO) > 0
                                ? order.getExecutedQty()
                                : order.getOrigQty();

                        // long vagy short?
                        boolean isLong = order.getSide() == OrderSide.BUY;

                        BigDecimal pnlAbs = isLong
                                ? currentPrice.subtract(entryPrice).multiply(qty).multiply(leverage)
                                : entryPrice.subtract(currentPrice).multiply(qty).multiply(leverage);

                        BigDecimal pnlPercent = pnlAbs
                                .divide(entryPrice.multiply(qty), 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));

                        sb.append(String.format(
                                "Symbol: %-8s | Entry: %-8s | Last: %-8s | Qty: %-6s | PnL: %6.2f%% (%s USDT)\n",
                                order.getSymbol(),
                                entryPrice.setScale(4, RoundingMode.HALF_UP),
                                currentPrice.setScale(4, RoundingMode.HALF_UP),
                                qty,
                                pnlPercent,
                                pnlAbs.setScale(4, RoundingMode.HALF_UP)
                        ));

                        // stop / win check
                        if (pnlPercent.compareTo(BigDecimal.valueOf(tradingConfig.stopLimit())) <= 0) {
                            log.info("STOP triggered on {} at {}% -> closing trade", order.getSymbol(), pnlPercent);
                            tradeService.closeOrder(order);
                        } else if (pnlPercent.compareTo(BigDecimal.valueOf(tradingConfig.winLimit())) >= 0) {
                            log.info("WIN triggered on {} at {}% -> closing trade", order.getSymbol(), pnlPercent);
                            tradeService.closeOrder(order);
                        }
                    });
        }

        log.info(sb.toString());
    }
}


