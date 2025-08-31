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

import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountProfitService {

    private final TradeService tradeService;
    private double profit = 0.0;
    private final ApplicationEventPublisher publisher;

    StringBuilder all;
    StringBuilder sb;
    StringBuilder stats ;

    private int winTrades = 0;
    private int loseTrades = 0;

    public void checkProfit(Object dto) {
        sb = new StringBuilder();
        all = new StringBuilder();

        if (dto instanceof OrderTradeUpdateDto order) {
            String status = order.o.X;   // order status
            String symbol = order.o.s;

            switch (status) {
                case "NEW":
                case "PARTIALLY_FILLED":
                    all.append(String.format("Order %s status: %s%n", symbol, status));
                    break;

                case "FILLED":
                    if ("BUY".equals(order.o.S)) {
                        // BUY FILLED → átrakjuk az active trades listába
                        TradeService.getOrderDtoList().stream()
                                .filter(o -> o.getSymbol().equals(symbol))
                                .findFirst()
                                .ifPresent(orderDto -> {
                                    tradeService.moveOrderToActive(orderDto);   // átrakás
                                    log.info("Order {} filled as BUY -> moved to active.", symbol);
                                });
                        all.append(String.format("Order %s filled as BUY -> moved to active.%n", symbol));
                        // push Telegram
                        tradeClosedUpdated(
                                String.format("🚀 New trade opened: %s %s %s @ %s",
                                        symbol, order.o.S, order.o.q, order.o.p));

                    } else if ("SELL".equals(order.o.S)) {
                        // SELL FILLED → trade lezárva, profit számítás
                        double realizedProfit = 0.0;
                        if (order.o.rp != null && !order.o.rp.isEmpty()) {
                            realizedProfit = Double.parseDouble(order.o.rp);
                            profit += realizedProfit;
                        }

                        if (realizedProfit > 0) {
                            winTrades++;
                        } else if (realizedProfit < 0) {
                            loseTrades++;
                        }

                        TradeService.getActiveOrderList().removeIf(o -> o.getSymbol().equals(symbol));

                        all.append(String.format("Order %s filled as SELL -> closed trade. Profit: %.4f%n",
                                symbol, realizedProfit));
                        all.append(String.format("Trade closed: %s %s @ %s | Profit/Loss: %.4f%n",
                                order.o.S, order.o.q, order.o.p, realizedProfit));
                        all.append(String.format("Total accumulated profit: %.4f%n", profit));

                        // push Telegram
                        tradeClosedUpdated(
                                String.format("✅ Trade closed: %s %s @ %s | P/L: %.4f USDT | Total profit: %.4f USDT | Symbol: %s",
                                        order.o.S, order.o.q, order.o.p, realizedProfit, profit, symbol));
                    }
                    break;

                case "CANCELED":
                case "EXPIRED":
                    TradeService.getOrderDtoList().removeIf(o -> o.getSymbol().equals(symbol));
                    all.append(String.format("Order %s canceled/expired -> removed from order list%n", symbol));
                    break;

                default:
                    all.append(String.format("Unhandled order status %s for %s%n", status, symbol));
            }

        } else if (dto instanceof AccountUpdateDto acc) {
            all.append(String.format("Account update: %s%n", acc));

        } else if (dto instanceof TradeLiteDto order) {
            all.append(String.format("Lite trade update for %s%n", order.s));

        } else {
            all.append(String.format("Unknown DTO received: %s%n", dto));
        }

        // állapot összefoglaló
        sb.append("*Active trades: ")
                .append(
                        TradeService.getActiveOrderList().stream()
                                .map(OrderDto::getSymbol)
                                .filter(Objects::nonNull)
                                .toList()
                )
                .append("\n");

        sb.append("*Order trades: ")
                .append(
                        TradeService.getOrderDtoList().stream()
                                .map(OrderDto::getSymbol)
                                .filter(Objects::nonNull)
                                .toList()
                )
                .append("\n");

    }

    public String getTrades() {
        return sb != null ? sb.toString() : "Nincs riport.";
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

        log.info(stats.toString());
        return stats.toString();
    }

    public String getAllLog() {
        return all.toString();
    }

    public void tradeClosedUpdated(String message) {
        publisher.publishEvent(new TradeClosedUpdateEvent(this, message));
    }
}