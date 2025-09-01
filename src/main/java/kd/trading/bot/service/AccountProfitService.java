package kd.trading.bot.service;

import kd.trading.bot.model.AccountUpdateDto;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.OrderTradeUpdateDto;
import kd.trading.bot.model.TradeLiteDto;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountProfitService {

    private final TradeService tradeService;
    private double profit = 0.0;
    private final ApplicationEventPublisher publisher;

    StringBuilder all;
    StringBuilder stats ;

    private int winTrades = 0;
    private int loseTrades = 0;

    public void controlOrderListsAndProfit(Object dto) {
        all = new StringBuilder();

        if (dto instanceof OrderTradeUpdateDto order) {
            // csak a TradeService hívás + profit/telegram logika
            tradeService.handleOrderUpdate(order);

            String status = order.o.X;
            String symbol = order.o.s;

            switch (status) {
                case "FILLED":
                    String side = order.o.S;  // BUY / SELL
                    String positionSide = order.o.ps != null ? order.o.ps : "BOTH"; // ha van külön field (Binance futures-nél van ps)

                    if ("BUY".equals(side)) {
                        if ("LONG".equals(positionSide) || "BOTH".equals(positionSide)) {
                            // Long nyílik
                            all.append(String.format("Order %s filled as BUY (LONG) -> moved to active.%n", symbol));
                            tradeClosedUpdated(
                                    String.format("🚀 New LONG trade opened:\n%s",
                                            formatTradeDetails(order.o)));
                        } else if ("SHORT".equals(positionSide)) {
                            // Short záródik
                            double realizedProfit = parseProfit(order.o.rp);
                            profit += realizedProfit;
                            updateWinLose(realizedProfit);

                            all.append(String.format("Order %s filled as BUY (SHORT close) -> closed trade. Profit: %.4f%n",
                                    symbol, realizedProfit));

                            tradeClosedUpdated(
                                    String.format("✅ SHORT Trade closed:\n%s\nProfit/Loss: %.2f USDT | Total profit: %.2f USDT",
                                            formatTradeDetails(order.o),
                                            realizedProfit,
                                            profit));
                        }
                    } else if ("SELL".equals(side)) {
                        if ("SHORT".equals(positionSide)) {
                            // Short nyílik
                            all.append(String.format("Order %s filled as SELL (SHORT) -> moved to active.%n", symbol));
                            tradeClosedUpdated(
                                    String.format("🚀 New SHORT trade opened:\n%s",
                                            formatTradeDetails(order.o)));
                        } else if ("LONG".equals(positionSide) || "BOTH".equals(positionSide)) {
                            // Long záródik
                            double realizedProfit = parseProfit(order.o.rp);
                            profit += realizedProfit;
                            updateWinLose(realizedProfit);

                            all.append(String.format("Order %s filled as SELL (LONG close) -> closed trade. Profit: %.4f%n",
                                    symbol, realizedProfit));

                            tradeClosedUpdated(
                                    String.format("✅ LONG Trade closed:\n%s\nProfit/Loss: %.2f USDT | Total profit: %.2f USDT",
                                            formatTradeDetails(order.o),
                                            realizedProfit,
                                            profit));
                        }
                    }
                    break;
            }
        }

    }

    private double parseProfit(String rp) {
        return (rp != null && !rp.isEmpty()) ? Double.parseDouble(rp) : 0.0;
    }

    private void updateWinLose(double realizedProfit) {
        if (realizedProfit > 0) winTrades++;
        else if (realizedProfit < 0) loseTrades++;
    }

    public String getTrades() {
        StringBuilder sb = new StringBuilder();

        // összefoglaló riport
        sb.append("*Active trades: ")
                .append(TradeService.getActiveOrderList().stream()
                        .map(OrderDto::getSymbol)
                        .filter(Objects::nonNull)
                        .toList())
                .append("\n");

        sb.append("*Order trades: ")
                .append(TradeService.getOrderDtoList().stream()
                        .map(OrderDto::getSymbol)
                        .filter(Objects::nonNull)
                        .toList())
                .append("\n");

        return sb.toString();
    }

    public String getProfitStatsReport() {
        stats = new StringBuilder();
        int totalTrades = winTrades+loseTrades;
        stats.append("====== Profit Statistics ======\n");
        stats.append(String.format("Total trades: %d%n", totalTrades));
        stats.append(String.format("Winning trades: %d%n", winTrades));
        stats.append(String.format("Losing trades: %d%n", loseTrades));

        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0.0;
        stats.append(String.format("Win rate: %.2f%%%n", winRate));
        stats.append(String.format("Accumulated profit: %.4f USDT%n", profit));
        return stats.toString();
    }

    public String getAllLog() {
        return all.toString();
    }

    public void tradeClosedUpdated(String message) {
        publisher.publishEvent(new TradeClosedUpdateEvent(this, message));
    }

    private String getDirectionLabel(String side) {
        if ("BUY".equalsIgnoreCase(side)) {
            return "🟢 LONG ";
        } else if ("SELL".equalsIgnoreCase(side)) {
            return "🔴 SHORT ";
        }
        return "❓ UNKNOWN";
    }

    private String formatTradeDetails(OrderTradeUpdateDto.Order order) {
        String direction = getDirectionLabel(order.S);
        String symbol = order.s;
        BigDecimal qty = new BigDecimal(order.q);
        BigDecimal price = new BigDecimal(order.p);
        BigDecimal totalUsdt = qty.multiply(price);

        return String.format(
                "%s | Symbol: %s | Qty: %s (≈ %.2f USDT) | Entry: %s",
                direction,
                symbol,
                qty.stripTrailingZeros().toPlainString(),
                totalUsdt,
                price.stripTrailingZeros().toPlainString()
        );
    }
}