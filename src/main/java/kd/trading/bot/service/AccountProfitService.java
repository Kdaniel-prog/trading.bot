package kd.trading.bot.service;

import kd.trading.bot.model.AccountUpdateDto;
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

    public void checkProfit(Object dto) {
        if (dto instanceof OrderTradeUpdateDto order) {
            String status = order.o.X; // order status
            String symbol = order.o.s;

            if ("FILLED".equals(status)) {
                // átrakás aktívba
                TradeService.getOrderDtoList().stream()
                        .filter(o -> o.getSymbol().equals(symbol))
                        .findFirst()
                        .ifPresent(tradeService::moveOrderToActive);

            } else if ("CANCELED".equals(status) || "EXPIRED".equals(status)) {
                // ha Binance cancel miatt jön, akkor listából kiszedjük
                TradeService.getOrderDtoList().removeIf(o -> o.getSymbol().equals(symbol));
                log.info("Order {} canceled/expired", symbol);

            } else if ("CLOSED".equals(status)) {
                // csak ilyenkor adjuk hozzá a profitot!
                String realized = order.o.rp;
                double realizedProfit = Double.parseDouble(realized);
                profit += realizedProfit;

                log.info("Trade CLOSED: {} {} @ {} | Profit/Loss: {}",
                        order.o.S, order.o.q, order.o.p, realized);
                log.info("Total profit: {}", profit);

                // törlés az aktív listából
                TradeService.getActiveOrderList().removeIf(o -> o.getSymbol().equals(symbol));
            }

        } else if (dto instanceof AccountUpdateDto acc) {
            log.debug("Account update: {}", acc);
        } else if (dto instanceof TradeLiteDto order) {
            log.debug("Lite trade: {}", order);
        }
    }
}
