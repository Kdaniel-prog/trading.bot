package kd.trading.bot.service;

import kd.trading.bot.mapper.OrderMapper;
import kd.trading.bot.model.BadSymbolsDto;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.OrderTradeUpdateDto;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

import static kd.trading.bot.service.TradeService.BAD_SYMBOL_LIST;

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
            String status = order.o.X;   // Order státusz (FILLED, NEW, stb.)
            String execType = order.o.x; // Execution type (TRADE, NEW, EXPIRED, stb.)
            String type = order.o.o;     // Order típus (MARKET, LIMIT)
            String symbol = order.o.s;

            double realizedProfit = parseProfit(order.o.rp);

            if ("FILLED".equals(status)) {
                if ("TRADE".equals(execType)) {
                    // Ez tényleg trade lezárás -> profit számítás
                    if (realizedProfit != 0.0) {

                        if (realizedProfit < 0) {
                            BAD_SYMBOL_LIST.add(BadSymbolsDto.builder()
                                    .symbol(symbol)
                                    .stamp(LocalDateTime.now())
                                    .build());
                        }

                        profit += realizedProfit;
                        updateWinLose(realizedProfit);

                        tradeClosedUpdated(
                                String.format("✅ Trade closed:\n%s\nProfit/Loss: %.2f USDT | Total profit: %.2f USDT",
                                        formatTradeDetails(order.o),
                                        realizedProfit,
                                        profit));
                        tradeService.removeFromActiveBySymbol(symbol);
                    } else {
                        // New position
                        tradeClosedUpdated(
                                String.format("🚀 New trade opened:\n%s",
                                        formatTradeDetails(order.o)));

                        OrderTradeUpdateDto.Order binanceOrder = order.o;
                        OrderDto myOrder = OrderMapper.fromBinanceOrder(binanceOrder);
                        tradeService.moveOrderToActive(myOrder);
                    }
                }
            } else {
                log.debug("Unhandled status {} / execType {} for {}", status, execType, symbol);
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

    //Az ellenkezőjét mutatja eladásnál.
    private String getDirectionLabel(String side) {
        if ("SELL".equalsIgnoreCase(side)) {
            return "🟢 LONG ";
        } else if ("BUY".equalsIgnoreCase(side)) {
            return "🔴 SHORT ";
        }
        return "❓ UNKNOWN";
    }

    private String formatTradeDetails(OrderTradeUpdateDto.Order order) {
        String direction = getDirectionLabel(order.S);
        String symbol = order.s;
        BigDecimal qty = toBigDecimal(order.q);

        // Nyitásnál entry ár = átlagár (ap), ha nincs, akkor a megadott ár (p)
        BigDecimal entryPrice = toBigDecimal(order.ap);
        if (entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            entryPrice = toBigDecimal(order.p);
        }

        // Zárásnál (TRADE) → last fill price (L) vagy ap
        BigDecimal closePrice = toBigDecimal(order.L);
        if (closePrice.compareTo(BigDecimal.ZERO) == 0) {
            closePrice = entryPrice;
        }

        BigDecimal totalUsdt = qty.multiply(entryPrice);

        return String.format(
                "%s | Symbol: %s | Qty: %s (≈ %.2f USDT) | Entry: %s | Close: %s",
                direction,
                symbol,
                qty.stripTrailingZeros().toPlainString(),
                totalUsdt,
                entryPrice.stripTrailingZeros().toPlainString(),
                closePrice.stripTrailingZeros().toPlainString()
        );
    }

    private BigDecimal toBigDecimal(String value) {
        return (value != null && !value.isEmpty())
                ? new BigDecimal(value)
                : BigDecimal.ZERO;
    }
}