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
    StringBuilder stats;

    private int winTrades = 0;
    private int loseTrades = 0;

    public void controlOrderListsAndProfit(Object dto) {
        all = new StringBuilder();

        if (dto instanceof OrderTradeUpdateDto order) {
            // először mindig update TradeService
            tradeService.handleOrderUpdate(order);

            String status = order.o.X;   // Order státusz
            String symbol = order.o.s;   // pl. BTCUSDT
            String side = order.o.S;     // BUY vagy SELL
            String positionSide = order.o.ps != null ? order.o.ps : "BOTH"; // Futures position side
            double realizedProfit = parseProfit(order.o.rp); // ha zárás, itt jön a profit

            boolean isHedgeMode = !"BOTH".equals(positionSide);
            boolean isOpening;
            String tradeType; // "LONG" or "SHORT"

            if (isHedgeMode) {
                // Hedge mode logic
                if ("LONG".equals(positionSide)) {
                    if ("BUY".equals(side)) {
                        isOpening = true;
                        tradeType = "LONG";
                    } else { // SELL
                        isOpening = false;
                        tradeType = "LONG";
                    }
                } else { // SHORT
                    if ("SELL".equals(side)) {
                        isOpening = true;
                        tradeType = "SHORT";
                    } else { // BUY
                        isOpening = false;
                        tradeType = "SHORT";
                    }
                }
            } else {
                // One-way mode logic
                boolean isReducing = realizedProfit != 0.0;
                if (isReducing) {
                    if ("BUY".equals(side)) {
                        isOpening = false;
                        tradeType = "SHORT"; // closing short
                    } else {
                        isOpening = false;
                        tradeType = "LONG"; // closing long
                    }
                } else {
                    if ("BUY".equals(side)) {
                        isOpening = true;
                        tradeType = "LONG";
                    } else {
                        isOpening = true;
                        tradeType = "SHORT";
                    }
                }
            }

            switch (status) {
                case "FILLED":
                    all.append(String.format("Order %s filled as %s (%s) -> ", symbol, side, positionSide));
                    if (isOpening) {
                        all.append("moved to active.\n");
                        tradeClosedUpdated(
                                String.format("🚀 New %s trade opened:\n%s",
                                        tradeType,
                                        formatTradeDetails(order.o)));
                    } else {
                        profit += realizedProfit;
                        updateWinLose(realizedProfit);

                        all.append(String.format("closed. Profit: %.4f\n", realizedProfit));

                        tradeClosedUpdated(
                                String.format("✅ %s Trade closed:\n%s\nProfit/Loss: %.2f USDT | Total profit: %.2f USDT",
                                        tradeType,
                                        formatTradeDetails(order.o),
                                        realizedProfit,
                                        profit));
                    }
                    break;

                default:
                    log.debug("Unhandled status {} for {}", status, symbol);
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
        int totalTrades = winTrades + loseTrades;
        stats.append("====== Profit Statistics ======\n");
        stats.append(String.format("Total trades: %d\n", totalTrades));
        stats.append(String.format("Winning trades: %d\n", winTrades));
        stats.append(String.format("Losing trades: %d\n", loseTrades));

        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0.0;
        stats.append(String.format("Win rate: %.2f%%\n", winRate));
        stats.append(String.format("Accumulated profit: %.4f USDT\n", profit));
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