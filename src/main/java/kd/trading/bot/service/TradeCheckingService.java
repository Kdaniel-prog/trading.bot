package kd.trading.bot.service;

import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.OrderSide;
import kd.trading.bot.model.BadSymbolsDto;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.PnlResult;
import kd.trading.bot.util.MessageParser;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static kd.trading.bot.service.TradeService.BAD_SYMBOL_LIST;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradeCheckingService {
    final MessageParser parser;
    final TradingConfig tradingConfig;
    final TradeService tradeService;

    // cache az utolsó kalkulációhoz
    Map<OrderDto, PnlResult> lastResults = Map.of();

    public void calculateTradeInfos(String message) {
        List<BinanceTickerData> tickers = parser.parseMarketMessage(message);

        List<OrderDto> activeOrders = TradeService.getActiveOrderList();
        if (activeOrders.isEmpty()) return;

        List<String> mySymbols = activeOrders.stream()
                .map(OrderDto::getSymbol)
                .toList();

        tickers = tickers.stream()
                .filter(t -> mySymbols.contains(t.getSymbol()))
                .toList();

        if (tickers.isEmpty()) return;

        Map<OrderDto, PnlResult> results = new LinkedHashMap<>();

        for (OrderDto order : activeOrders) {
            tickers.stream()
                    .filter(t -> t.getSymbol().equalsIgnoreCase(order.getSymbol()))
                    .findFirst()
                    .ifPresent(ticker -> {
                        BigDecimal currentPrice = BigDecimal.valueOf(ticker.getLastPrice());
                        PnlResult pnl = calculatePnl(order, currentPrice);

                        results.put(order, pnl);
                        log.warn("debug1: pnlPercent: {}| config:{} | symbol: {}",pnl.getPnlPercent(), tradingConfig.stopLimit(), order.getSymbol());
                        log.warn("debug2: pnlPercent: {}",pnl.getPnlPercent().compareTo(BigDecimal.valueOf(tradingConfig.stopLimit())));
                        // STOP/WIN check (pnlPercent alapján)
                        if (pnl.getPnlPercent().compareTo(BigDecimal.valueOf(tradingConfig.stopLimit())) <= 0) {
                            log.info("STOP triggered on {} at {}% -> closing trade", order.getSymbol(), pnl.getPnlPercent());
                            tradeService.closeOrder(order);
                        } else if (pnl.getPnlPercent().compareTo(BigDecimal.valueOf(tradingConfig.winLimit())) >= 0) {
                            log.info("WIN triggered on {} at {}% -> closing trade", order.getSymbol(), pnl.getPnlPercent());
                            tradeService.closeOrder(order);
                        }
                        if (order.getStarted() != null) {
                            Duration openDuration = Duration.between(order.getStarted(), LocalDateTime.now());

                            if (openDuration.toMinutes() >= 10) {
                                BigDecimal absPercent = pnl.getPnlPercent().abs();

                                if (absPercent.doubleValue() < 0.11){

                                    log.info("TIMEOUT triggered on {} ({}min, pnl={}%) -> closing trade",
                                            order.getSymbol(), openDuration.toMinutes(), pnl.getPnlPercent());
                                    tradeService.closeOrder(order);
                                }
                            }
                        }
                    });
        }

        updateLastResult(results);
        //swipeCoins(results);
    }

    private void updateLastResult(Map<OrderDto, PnlResult> results) {
        if(!lastResults.isEmpty()) {
            // frissítjük vagy hozzáadjuk az új eredményeket
            lastResults.putAll(results);

            // eltávolítjuk a lezárt order-eket
            lastResults.keySet().removeIf(order -> !TradeService.getActiveOrderList().contains(order));
        } else {
            lastResults = results;
        }
    }
    /**
     * Kiszámolja a PnL értékeket egy aktív order alapján.
     */
    public PnlResult calculatePnl(OrderDto order, BigDecimal currentPrice) {
        BigDecimal entryPrice = order.getAvgPrice() != null && order.getAvgPrice().compareTo(BigDecimal.ZERO) > 0
                ? order.getAvgPrice()
                : order.getPrice();

        BigDecimal qty = order.getExecutedQty() != null && order.getExecutedQty().compareTo(BigDecimal.ZERO) > 0
                ? order.getExecutedQty()
                : order.getOrigQty();

        boolean isLong = order.getSide() == OrderSide.BUY;

        // abszolút PnL USDT-ben (NINCS leveraggel szorozva!)
        BigDecimal pnlAbs = isLong
                ? currentPrice.subtract(entryPrice).multiply(qty)
                : entryPrice.subtract(currentPrice).multiply(qty);

        // %-os PnL a teljes pozíció értékéhez viszonyítva
        BigDecimal pnlPercent = pnlAbs
                .divide(entryPrice.multiply(qty), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return new PnlResult(currentPrice, entryPrice, qty, pnlAbs, pnlPercent, isLong);
    }

    private void swipeCoins(Map<OrderDto, PnlResult> tradeInfos) {
        BigDecimal allProfit = BigDecimal.ZERO;

        for (Map.Entry<OrderDto, PnlResult> entry : tradeInfos.entrySet()) {
            PnlResult pnl = entry.getValue();
            if (pnl != null && pnl.getPnlAbs() != null) {
                allProfit = allProfit.add(
                        pnl.getPnlAbs().setScale(4, RoundingMode.HALF_UP)
                );
            }
        }

        // ha az összes profit >= 0.5
        if (allProfit.compareTo(BigDecimal.valueOf(tradingConfig.swipeValue())) >= 0) {
            // itt zárjuk az összes aktív ordert
            tradeInfos.keySet().forEach(order -> {
                tradeService.closeOrder(order);
            });
        }

        log.info("Swipe all coins value: {}", allProfit);
    }

    /**
     * Formázott string visszaadás a PnL adatokból.
     */
    public String formatTradeInfos(Map<OrderDto, PnlResult> tradeInfos) {
        if (tradeInfos.isEmpty()) {
            return "\n====== Active Trades ======\nNo active trades.\n";
        }

        StringBuilder sb = new StringBuilder("\n====== Active Trades ======\n");

        tradeInfos.forEach((order, pnl) -> {
            String directionEmoji = pnl.isLong() ? "🟢 LONG" : "🔴 SHORT";
            sb.append(String.format(
                    "%-8s | %s\n  Entry: %-10s | Last: %-10s\n  Qty: %-10s | PnL: %6.2f%% (%s USDT)\n\n",
                    order.getSymbol(),
                    directionEmoji,
                    pnl.getEntryPrice().setScale(4, RoundingMode.HALF_UP),
                    pnl.getCurrentPrice().setScale(4, RoundingMode.HALF_UP),
                    pnl.getQty(),
                    pnl.getPnlPercent(),
                    pnl.getPnlAbs().setScale(4, RoundingMode.HALF_UP)
            ));
        });

        return sb.toString();
    }

    public void closeTrade(OrderDto orderDto) {
        tradeService.closeOrder(orderDto);
    }

    /**
     * Telegram bot innen hívhatja közvetlenül.
     */
    public String getTradeInfos() {
        return formatTradeInfos(lastResults);
    }
}



