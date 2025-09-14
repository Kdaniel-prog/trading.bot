package kd.trading.bot.service;

import kd.trading.bot.mapper.OrderMapper;
import kd.trading.bot.model.BadSymbolsDto;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.OrderTradeUpdateDto;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

import static kd.trading.bot.service.TradeService.BAD_SYMBOL_LIST;

/**
 * AccountProfitService
 *
 * Ez az osztály felel a tradek kezeléséért és a profit statisztikák számításáért.
 *
 * - Binance-től érkező order frissítések feldolgozása
 * - LIMIT és MARKET megbízások életciklusának kezelése
 * - Aktív és függő tradek nyilvántartása
 * - Profit, veszteség és win rate számítása
 * - Trade záráskor események küldése (pl. Telegram)
 * - Összefoglaló riport készítése az aktuális helyzetről
 * - Veszteséges szimbólumok nyilvántartása (bad symbols)
 *
 * handleOrderUpdate()
 * ├── handleLimitOrder()
 * │   ├── handleLimitOrderNew()
 * │   ├── handleLimitOrderPartiallyFilled()
 * │   ├── handleLimitOrderFilled()
 * │   └── handleLimitOrderCanceled()
 * └── handleMarketOrder()
 *     ├── handleMarketOrderFilled()
 *     └── handleMarketOrderExecution()
 */
@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class HandleOrderUpdateService {

    static final String ORDER_TYPE_LIMIT = "LIMIT";
    static final String ORDER_TYPE_MARKET = "MARKET";

    static final String STATUS_NEW = "NEW";
    static final String STATUS_PARTIALLY_FILLED = "PARTIALLY_FILLED";
    static final String STATUS_FILLED = "FILLED";
    static final String STATUS_CANCELED = "CANCELED";

    static final String SIDE_BUY = "BUY";
    static final String SIDE_SELL = "SELL";

    final TradeService tradeService;
    final ApplicationEventPublisher publisher;

    double profit = 0.0;
    int winTrades = 0;
    int loseTrades = 0;

    public void controlOrderListsAndProfit(Object dto) {
        if (dto instanceof OrderTradeUpdateDto order) {
            log.warn("{}", dto);
            handleOrderUpdate(order.o);
        }
    }

    public void handleOrderUpdate(OrderTradeUpdateDto.Order order) {
        String orderType = order.o;

        if (ORDER_TYPE_LIMIT.equals(orderType)) {
            handleLimitOrder(order);
        } else if (ORDER_TYPE_MARKET.equals(orderType)) {
            handleMarketOrder(order);
        }
    }

    private void handleLimitOrder(OrderTradeUpdateDto.Order order) {
        String status = order.X;

        switch (status) {
            case STATUS_NEW -> handleLimitOrderNew(order);
            case STATUS_PARTIALLY_FILLED -> handleLimitOrderPartiallyFilled(order);
            case STATUS_FILLED -> handleLimitOrderFilled(order);
            case STATUS_CANCELED -> handleLimitOrderCanceled(order);
        }
    }

    private void handleLimitOrderNew(OrderTradeUpdateDto.Order order) {
        String clientOrderId = order.c;
        String symbol = order.s;
        long orderId = order.i;

        boolean exists = TradeService.orderDtoList.stream()
                .anyMatch(o -> o.getClientOrderId().equals(clientOrderId));

        if (!exists) {
            OrderDto dto = OrderMapper.fromBinanceOrder(order);
            dto.setStarted(LocalDateTime.now());
            TradeService.orderDtoList.add(dto);
            log.info("➕ Open order added: {} ({}) at price: {}", symbol, orderId, order.p);
        } else {
            log.debug("⚠️ Duplicate NEW order ignored: {} ({})", symbol, orderId);
        }
    }

    private void handleLimitOrderPartiallyFilled(OrderTradeUpdateDto.Order order) {
        String clientOrderId = order.c;
        String symbol = order.s;
        long orderId = order.i;

        TradeService.orderDtoList.stream()
                .filter(o -> o.getClientOrderId().equals(clientOrderId))
                .findFirst()
                .ifPresent(o -> {
                    // Update with actual execution data
                    o.setExecutedQty(BigDecimal.valueOf(Double.parseDouble(order.z))); // cumulative filled quantity
                    o.setCumQty(BigDecimal.valueOf(Double.parseDouble(order.z)));

                    // CRITICAL: Update with actual fill price (average price)
                    BigDecimal actualFillPrice = getActualFillPrice(order);
                    o.setAvgPrice(actualFillPrice);
                    o.setPrice(actualFillPrice); // Update original price too

                    log.info("✏️ Open order updated (partial fill): {} ({}) - Filled at: {}", symbol, orderId, actualFillPrice);
                });
    }

    private void handleLimitOrderFilled(OrderTradeUpdateDto.Order order) {
        String clientOrderId = order.c;
        String symbol = order.s;
        long orderId = order.i;

        // Remove from pending orders
        TradeService.orderDtoList.removeIf(o -> o.getClientOrderId().equals(clientOrderId));
        log.info("❌ Open order removed: {} ({})", symbol, orderId);

        // Create active trade with CORRECT entry price
        OrderDto activeOrder = createActiveOrderFromFilled(order);
        tradeService.moveOrderToActive(activeOrder);

        BigDecimal actualEntryPrice = getActualFillPrice(order);
        log.info("✅ LIMIT order moved to active trades: {} ({}) - Entry price: {}", symbol, orderId, actualEntryPrice);

        // Notify about new position opening
        String message = String.format("🚀 LIMIT order filled - New position opened:\n%s\nEntry Price: %s",
                formatTradeDetails(order, false),
                actualEntryPrice.stripTrailingZeros().toPlainString());
        tradeClosedUpdated(message);
    }

    private void handleLimitOrderCanceled(OrderTradeUpdateDto.Order order) {
        String clientOrderId = order.c;
        String symbol = order.s;
        long orderId = order.i;

        TradeService.orderDtoList.removeIf(o -> o.getClientOrderId().equals(clientOrderId));
        log.info("❌ Open order canceled: {} ({})", symbol, orderId);
    }

    private void handleMarketOrder(OrderTradeUpdateDto.Order order) {
        String status = order.X;

        if (STATUS_FILLED.equals(status)) {
            handleMarketOrderFilled(order);
        }

        if (STATUS_FILLED.equals(status) || STATUS_PARTIALLY_FILLED.equals(status)) {
            handleMarketOrderExecution(order);
        }
    }

    private void handleMarketOrderFilled(OrderTradeUpdateDto.Order order) {
        double realizedProfit = Double.parseDouble(order.rp);
        String symbol = order.s;

        if (realizedProfit != 0.0) {
            updateProfitAndStats(realizedProfit, symbol, order);
        }
    }

    private void handleMarketOrderExecution(OrderTradeUpdateDto.Order order) {
        double realizedProfit = Double.parseDouble(order.rp);
        String clientOrderId = order.c;
        String symbol = order.s;
        long orderId = order.i;

        if (realizedProfit != 0.0) {
            // Trade closed → profit/loss accounted
            tradeService.removeFromActiveBySymbol(symbol);
        } else {
            // New position opened → only add once per orderId
            if (!isDuplicateMarketOrder(clientOrderId)) {
                OrderDto myOrder = createMarketOrder(order);
                tradeService.moveOrderToActive(myOrder);

                BigDecimal marketEntryPrice = getActualFillPrice(order);
                log.info("🚀 New MARKET trade opened: {} ({}) - Entry: {}", symbol, orderId, marketEntryPrice);

                String message = String.format("🚀 New MARKET trade opened:\n%s", formatTradeDetails(order, false));
                tradeClosedUpdated(message);
            } else {
                log.debug("⚠️ Duplicate MARKET update ignored: {} ({})", symbol, orderId);
            }
        }
    }

    private OrderDto createActiveOrderFromFilled(OrderTradeUpdateDto.Order order) {
        OrderDto activeOrder = OrderMapper.fromBinanceOrder(order);

        // CRITICAL: Set the correct entry price from the filled order
        BigDecimal actualEntryPrice = getActualFillPrice(order);
        activeOrder.setPrice(actualEntryPrice); // Entry price
        activeOrder.setAvgPrice(actualEntryPrice); // Average price
        activeOrder.setExecutedQty(BigDecimal.valueOf(Double.parseDouble(order.z))); // Filled quantity
        activeOrder.setCumQty(BigDecimal.valueOf(Double.parseDouble(order.z)));
        activeOrder.setStarted(LocalDateTime.now()); // Mark when position started

        return activeOrder;
    }

    private OrderDto createMarketOrder(OrderTradeUpdateDto.Order order) {
        OrderDto myOrder = OrderMapper.fromBinanceOrder(order);

        // For MARKET orders, also ensure correct entry price
        BigDecimal marketEntryPrice = getActualFillPrice(order);
        myOrder.setPrice(marketEntryPrice);
        myOrder.setAvgPrice(marketEntryPrice);

        return myOrder;
    }

    private boolean isDuplicateMarketOrder(String clientOrderId) {
        return TradeService.activeOrderList.stream()
                .anyMatch(o -> {
                    if (o.getIsLoaded()) {
                        return false;
                    } else {
                        return o.getClientOrderId().equals(clientOrderId);
                    }
                });
    }

    private void updateProfitAndStats(double realizedProfit, String symbol, OrderTradeUpdateDto.Order order) {
        profit += realizedProfit;
        updateWinLose(realizedProfit);

        String message = String.format("✅ Trade closed:\n%s\nProfit/Loss: %.2f USDC | Total profit: %.2f USDC",
                formatTradeDetails(order, true), realizedProfit, profit);
        tradeClosedUpdated(message);

        log.info("✅ Trade closed: {} | Profit/Loss: {}", symbol, realizedProfit);

        if (realizedProfit < 0.0) {
            BAD_SYMBOL_LIST.add(new BadSymbolsDto(symbol, LocalDateTime.now()));
        }
    }

    /**
     * Gets the actual fill price from the order update
     * Priority: Last filled price (L) > Average price (ap) > Order price (p)
     */
    private BigDecimal getActualFillPrice(OrderTradeUpdateDto.Order order) {
        // Last filled price (most accurate for the current fill)
        if (isValidPrice(order.L)) {
            return new BigDecimal(order.L);
        }

        // Average price (weighted average of all fills)
        if (isValidPrice(order.ap)) {
            return new BigDecimal(order.ap);
        }

        // Fallback to original order price
        if (isValidPrice(order.p)) {
            return new BigDecimal(order.p);
        }

        // This should not happen, but return 0 as fallback
        log.warn("⚠️ Could not determine fill price for order: {}", order);
        return BigDecimal.ZERO;
    }

    private boolean isValidPrice(String price) {
        return price != null && !price.isEmpty() && !price.equals("0");
    }

    private void updateWinLose(double realizedProfit) {
        if (realizedProfit > 0) {
            winTrades++;
        } else if (realizedProfit < 0) {
            loseTrades++;
        }
    }

    public String getTrades() {

        // összefoglaló riport

        String sb = "*Active trades: " +
                TradeService.getActiveOrderList().stream()
                        .map(OrderDto::getSymbol)
                        .filter(Objects::nonNull)
                        .toList() +
                "\n" +
                "*Order trades: " +
                TradeService.getOrderDtoList().stream()
                        .map(OrderDto::getSymbol)
                        .filter(Objects::nonNull)
                        .toList() +
                "\n";

        return sb;
    }

    public String getProfitStatsReport() {
        int totalTrades = winTrades + loseTrades;

        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0.0;
        String stats = "====== Profit Statistics ======\n" +
                String.format("Total trades: %d\n", totalTrades) +
                String.format("Winning trades: %d\n", winTrades) +
                String.format("Losing trades: %d\n", loseTrades) +
                String.format("Win rate: %.2f%%\n", winRate) +
                String.format("Accumulated profit: %.4f USDC\n", profit);

        return stats;
    }

    public void tradeClosedUpdated(String message) {
        publisher.publishEvent(new TradeClosedUpdateEvent(this, message));
    }

    // Az ellenkezőjét mutatja eladásnál.
    private String getDirectionLabel(String side, Boolean isClosed) {
        if (SIDE_BUY.equalsIgnoreCase(side)) {
            return isClosed ? "🔴 SHORT " : "🟢 LONG ";
        } else if (SIDE_SELL.equalsIgnoreCase(side)) {
            return isClosed ? "🟢 LONG " : "🔴 SHORT ";
        }
        return "❓ UNKNOWN";
    }

    private String formatTradeDetails(OrderTradeUpdateDto.Order order, Boolean isClosed) {
        String direction = getDirectionLabel(order.S, isClosed);
        String symbol = order.s;
        BigDecimal qty = toBigDecimal(order.q);

        // Use the actual fill price method for consistency
        BigDecimal entryPrice = getActualFillPrice(order);
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
        return (value != null && !value.isEmpty()) ? new BigDecimal(value) : BigDecimal.ZERO;
    }
}