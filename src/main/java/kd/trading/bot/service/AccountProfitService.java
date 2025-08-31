package kd.trading.bot.service;

import kd.trading.bot.model.AccountUpdateDto;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.OrderTradeUpdateDto;
import kd.trading.bot.model.TradeLiteDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountProfitService {

    private final TradeService tradeService;
    private double profit = 0.0;

    /**
     * Itt mozgatjuk openről. activra vagy closoljuk a tradet. plusz profit számolás.
     * @param dto
     */
    public void checkProfit(Object dto) {
        if (dto instanceof OrderTradeUpdateDto order) {
            String status = order.o.X;   // order status
            String symbol = order.o.s;

            switch (status) {
                case "NEW":
                case "PARTIALLY_FILLED":
                    // open -> még nem aktív, de figyeljük
                    //log.info("Order {} status: {}", symbol, status);
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

                    } else if ("SELL".equals(order.o.S)) {
                        // SELL FILLED → trade lezárva, profit számítás
                        double realizedProfit = 0.0;
                        if (order.o.rp != null && !order.o.rp.isEmpty()) {
                            realizedProfit = Double.parseDouble(order.o.rp);
                            profit += realizedProfit;
                        }

                        TradeService.getActiveOrderList().removeIf(o -> o.getSymbol().equals(symbol));
                        log.info("Order {} filled as SELL -> closed trade. Profit: {}", symbol, realizedProfit);
                        log.info("Total accumulated profit: {}", profit);
                    }
                    break;

                case "CANCELED":
                case "EXPIRED":
                    // pendingből kivesszük
                    TradeService.getOrderDtoList().removeIf(o -> o.getSymbol().equals(symbol));
                    log.info("Order {} canceled/expired -> removed from order list", symbol);
                    break;

                default:
                    log.debug("Unhandled order status {} for {}", status, symbol);
            }

            // ha van realizált profit, számoljuk (akkor is ha FILLED vagy lezárt trade)
            if (order.o.rp != null && !order.o.rp.isEmpty()) {
                double realizedProfit = Double.parseDouble(order.o.rp);
                if (realizedProfit != 0.0) {
                    profit += realizedProfit;

                    log.info("Trade closed: {} {} @ {} | Profit/Loss: {}",
                            order.o.S, order.o.q, order.o.p, realizedProfit);
                    log.info("Total accumulated profit: {}", profit);

                    // ha a trade tényleg lezárult, aktívból is töröljük
                    TradeService.getActiveOrderList().removeIf(o -> o.getSymbol().equals(symbol));
                }
            }

        } else if (dto instanceof AccountUpdateDto acc) {
            log.debug("Account update: {}", acc);

        } else if (dto instanceof TradeLiteDto order) {
            //log.debug("Lite trade: {}", order);
            //TradeService.getActiveOrderList().removeIf(o -> o.getSymbol().equals(order.s));

        } else {
            log.warn("Unknown DTO received: {}", dto);
        }

        log.info("*Active trades: {}",
                TradeService.getActiveOrderList().stream()
                        .map(OrderDto::getSymbol)
                        .toList()
        );

        log.info("*Order trades: {}",
                TradeService.getOrderDtoList().stream()
                        .map(OrderDto::getSymbol)
                        .toList()
        );

        log.info("*Check Profit: {}", profit);
    }
}
