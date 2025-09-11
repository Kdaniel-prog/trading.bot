package kd.trading.bot.service.tradingProcess;

import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.TradeAction;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.PnlResult;
import kd.trading.bot.model.TradeDecision;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradeDecisionService {
    TradingConfig tradingConfig;


    public List<TradeDecision> evaluateTradeDecisions(Map<OrderDto, PnlResult> tradeAnalytics) {
        List<TradeDecision> decisions = new ArrayList<>();

        for (Map.Entry<OrderDto, PnlResult> entry : tradeAnalytics.entrySet()) {
            OrderDto order = entry.getKey();
            PnlResult pnl = entry.getValue();

            TradeDecision decision = evaluateOrder(order, pnl);
            if (decision.shouldExecute()) {
                decisions.add(decision);
            }
        }

        // Evaluate swipe condition - COMMENTED OUT FOR NOW
        /*
        BigDecimal totalPnl = tradeAnalytics.values().stream()
                .map(PnlResult::getPnlAbs)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPnl.compareTo(BigDecimal.valueOf(tradingConfig.swipeValue())) >= 0) {
            decisions.add(new TradeDecision(TradeAction.SWIPE_ALL, null, "Total profit threshold reached"));
        }
        */

        return decisions;
    }

    private TradeDecision evaluateOrder(OrderDto order, PnlResult pnl) {
        // Stop loss check
        if (pnl.getPnlPercent().compareTo(BigDecimal.valueOf(tradingConfig.stopLimit())) <= 0) {
            log.info("STOP triggered at {}", pnl.getPnlPercent());
            return new TradeDecision(TradeAction.CLOSE, order,
                    String.format("STOP triggered at %.2f%%", pnl.getPnlPercent()));
        }

        // Take profit check
        if (pnl.getPnlPercent().compareTo(BigDecimal.valueOf(tradingConfig.winLimit())) >= 0) {
            log.info("WIN triggered at {}", pnl.getPnlPercent());
            return new TradeDecision(TradeAction.CLOSE, order,
                    String.format("WIN triggered at %.2f%% | Symbol: %s", pnl.getPnlPercent(), order.getSymbol()));
        }

        // Time-based checks
        if (order.getStarted() != null) {
            Duration openDuration = Duration.between(order.getStarted(), LocalDateTime.now());
            log.warn("DURATION TIME: {} | SYMBOL: {} | PNL: {}", openDuration.toMinutes(), order.getSymbol(), pnl.getPnlPercent());
            // After 40 minutes: close if profitable (any win > 0%)
            if (openDuration.toHours() >= 7) {
                if (pnl.getPnlPercent().compareTo(BigDecimal.valueOf(0.30)) >= 0) {
                    return new TradeDecision(TradeAction.CLOSE, order,
                            String.format("TIME WIN triggered after %d minutes with %.2f%% profit | Symbol: %s",
                                    openDuration.toMinutes(), pnl.getPnlPercent(), order.getSymbol()));
                }
            }

            if (openDuration.toHours() >= 8) {
                if (pnl.getPnlPercent().compareTo(BigDecimal.valueOf(0.0)) <= 0) {
                    return new TradeDecision(TradeAction.CLOSE, order,
                            String.format("TIME LOSE triggered after %d minutes with %.2f%% profit | Symbol: %s",
                                    openDuration.toMinutes(), pnl.getPnlPercent(), order.getSymbol()));
                }
            }

        }

        return new TradeDecision(TradeAction.HOLD, order, "No action required");
    }
}
