package kd.trading.bot.service;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.Signal;
import kd.trading.bot.model.TradeStatus;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ControlTradesService {
    final BinanceRestClient restClient;
    final TradingConfig tradingConfig;

    public void checkTrades(List<BinanceTickerData> coins) {
        TradeService.activeTrades.forEach(trade -> {
            coins.stream()
                    .filter(t -> t.getSymbol().equals(trade.getSymbol().getSymbol()))
                    .findFirst()
                    .ifPresent(ticker -> {
                        double profit = 0.0;
                        if(trade.getSignal() == Signal.LONG){
                            profit = (ticker.getLastPrice() - trade.getEntryPrice().doubleValue()) * trade.getQuantity().doubleValue();
                        } else if(trade.getSignal() == Signal.SHORT){
                            profit = (trade.getEntryPrice().doubleValue() - ticker.getLastPrice()) * trade.getQuantity().doubleValue();
                        }

                        double pnlPercent = (ticker.getLastPrice() - trade.getEntryPrice().doubleValue())
                                / trade.getEntryPrice().doubleValue();

                        if (trade.getSignal() == Signal.SHORT) {
                            pnlPercent = (trade.getEntryPrice().doubleValue() - ticker.getLastPrice())
                                    / trade.getEntryPrice().doubleValue();
                        }

                        if (pnlPercent >= trade.getWinLimit() || pnlPercent <= trade.getStopLimit()) {
                            // remove trade
                            TradeStatus tradeStatus = restClient.finishOrder(
                                    trade.getSymbol().getSymbol(),
                                    trade.getSignal(),
                                    trade.getQuantity()
                            );

                            if (tradeStatus.equals(TradeStatus.SUCCESS)) {
                                TradeService.activeTrades.remove(trade);
                            }
                        }

                        log.info("Trade {}: unrealized profit = {} Percent: {}", trade.getSymbol().getSymbol(), profit, pnlPercent);
                    });
        });
    }

}
