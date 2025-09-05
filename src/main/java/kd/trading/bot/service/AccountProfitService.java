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
            log.warn("{}", dto);
            handleOrderUpdate(order.o);
        }
    }

    public void handleOrderUpdate(OrderTradeUpdateDto.Order order) {
        String status = order.X; // Status
        String execType = order.x; // Execution type
        String type = order.o;     // Order type
        String symbol = order.s;
        long orderId = order.i;
        String clientOrderId = order.c;

        double realizedProfit = Double.parseDouble(order.rp);

        // --- LIMIT ORDERS ---
        if ("LIMIT".equals(type)) {
            if ("NEW".equals(status)) {
                boolean exists = TradeService.orderDtoList.stream()
                        .anyMatch(o -> o.getClientOrderId().equals(clientOrderId));

                if (!exists) {
                    OrderDto dto = OrderMapper.fromBinanceOrder(order);
                    dto.setStarted(LocalDateTime.now());
                    TradeService.orderDtoList.add(dto);
                    log.info("➕ Open order added: {} ({})", symbol, orderId);
                } else {
                    log.debug("⚠️ Duplicate NEW order ignored: {} ({})", symbol, orderId);
                }
            } else if ("PARTIALLY_FILLED".equals(status)) {
                TradeService.orderDtoList.stream()
                        .filter(o -> o.getClientOrderId().equals(clientOrderId))
                        .findFirst()
                        .ifPresent(o -> {
                            o.setExecutedQty(BigDecimal.valueOf(Double.parseDouble(order.z)));
                            o.setCumQty(BigDecimal.valueOf(Double.parseDouble(order.z)));
                            o.setAvgPrice(BigDecimal.valueOf(Double.parseDouble(order.ap)));
                        });
                log.info("✏️ Open order updated (partial fill): {} ({})", symbol, orderId);
            } else if ("FILLED".equals(status)) {
                TradeService.orderDtoList.removeIf(o -> o.getClientOrderId().equals(clientOrderId));
                log.info("❌ Open order removed: {} ({})", symbol, orderId);

                OrderDto myOrder = OrderMapper.fromBinanceOrder(order);
                tradeService.moveOrderToActive(myOrder);
                log.info("✅ LIMIT order moved to active trades: {} ({})", symbol, orderId);
            } else if ("CANCELED".equals(status)) {
                TradeService.orderDtoList.removeIf(o -> o.getClientOrderId().equals(clientOrderId));
                log.info("❌ Open order canceled: {} ({})", symbol, orderId);
            }
        } else if ("MARKET".equals(type)) {

            if("FILLED".equals(status)) {
                if (realizedProfit != 0.0) {
                    profit += realizedProfit;
                    updateWinLose(realizedProfit);
                    tradeClosedUpdated(
                            String.format("✅ Trade closed:\n%s\nProfit/Loss: %.2f USDC | Total profit: %.2f USDC",
                                    formatTradeDetails(order),
                                    realizedProfit,
                                    profit));

                    log.info("✅ Trade closed: {} | Profit/Loss: {}", symbol, realizedProfit);

                    if(realizedProfit < 0.0) BAD_SYMBOL_LIST.add(new BadSymbolsDto(symbol, LocalDateTime.now()));

                }
            }

            if ("FILLED".equals(status) || "PARTIALLY_FILLED".equals(status)) {
                if (realizedProfit != 0.0) {
                    // Trade closed → profit/loss accounted
                    tradeService.removeFromActiveBySymbol(symbol);
                } else {
                    // New position opened → only add once per orderId
                    boolean exists = TradeService.activeOrderList.stream()
                            .anyMatch(o ->{
                                if(o.getIsLoaded()) {
                                    return false;
                                } else {
                                   return o.getClientOrderId().equals(clientOrderId);
                                }
                            });

                    if (!exists) {
                        OrderDto myOrder = OrderMapper.fromBinanceOrder(order);
                        tradeService.moveOrderToActive(myOrder);
                        log.info("🚀 New trade opened: {} ({})", symbol, orderId);
                        // új pozíció
                        tradeClosedUpdated(
                                String.format("🚀 New trade opened:\n%s",
                                        formatTradeDetails(order)));
                    } else {
                        log.debug("⚠️ Duplicate MARKET update ignored: {} ({})", symbol, orderId);
                    }
                }
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
        stats.append(String.format("Accumulated profit: %.4f USDC\n", profit));
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

        BigDecimal totalUsdc = qty.multiply(entryPrice);

        return String.format(
                "%s | Symbol: %s | Qty: %s (≈ %.2f USDC) | Entry: %s | Close: %s",
                direction,
                symbol,
                qty.stripTrailingZeros().toPlainString(),
                totalUsdc,
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