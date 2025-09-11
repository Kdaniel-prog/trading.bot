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

        return decisions;
    }

    private TradeDecision evaluateOrder(OrderDto order, PnlResult pnl) {
        BigDecimal currentPnlPercent = pnl.getPnlPercent();
        Duration openDuration = getOrderDuration(order);

        log.debug("Evaluating {} - PnL: {}%, Duration: {} min, LastWin: {}%",
                order.getSymbol(), currentPnlPercent, openDuration.toMinutes(), order.getLastWin());

        // === IMMEDIATE EXITS ===

        // 1. Hard Stop Loss: -5%
        if (currentPnlPercent.compareTo(BigDecimal.valueOf(tradingConfig.stopLimit())) <= 0) {
            log.info("🛑 STOP LOSS triggered at {}% for {}", currentPnlPercent, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("STOP LOSS: %.2f%%", currentPnlPercent));
        }

        // 2. Target Profit: 10%
        if (currentPnlPercent.compareTo(BigDecimal.valueOf(tradingConfig.winLimit())) >= 0) {
            log.info("🎯 TARGET PROFIT reached at {}% for {}", currentPnlPercent, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TARGET PROFIT: %.2f%%", currentPnlPercent));
        }

        // === TRAILING STOP MECHANISM ===
        TradeDecision trailingDecision = evaluateTrailingStop(order, currentPnlPercent);
        if (trailingDecision.shouldExecute()) {
            return trailingDecision;
        }

        // === TIME-BASED EXITS ===
        TradeDecision timeDecision = evaluateTimeBasedExit(order, currentPnlPercent, openDuration);
        if (timeDecision.shouldExecute()) {
            return timeDecision;
        }

        // === RISK REDUCTION OVER TIME ===
        TradeDecision riskDecision = evaluateRiskReduction(order, currentPnlPercent, openDuration);
        if (riskDecision.shouldExecute()) {
            return riskDecision;
        }

        return createDecision(TradeAction.HOLD, order, "Holding position");
    }

    /**
     * Trailing stop mechanism using lastWin field
     */
    private TradeDecision evaluateTrailingStop(OrderDto order, BigDecimal currentPnl) {
        BigDecimal lastWin = order.getLastWin() != null ? order.getLastWin() : BigDecimal.ZERO;

        // Update lastWin if current profit is higher
        if (currentPnl.compareTo(lastWin) > 0) {
            order.setLastWin(currentPnl);
            log.debug("📈 New high for {}: {}%", order.getSymbol(), currentPnl);
            return createDecision(TradeAction.HOLD, order, "New profit high recorded");
        }

        // Trailing stop conditions
        if (lastWin.compareTo(BigDecimal.valueOf(3.0)) >= 0) { // Had at least 3% profit
            BigDecimal dropFromPeak = lastWin.subtract(currentPnl);

            // If dropped 1.5% from peak when peak was 3%+
            if (dropFromPeak.compareTo(BigDecimal.valueOf(1.5)) >= 0) {
                log.info("📉 TRAILING STOP triggered for {} - Peak: {}%, Current: {}%, Drop: {}%",
                        order.getSymbol(), lastWin, currentPnl, dropFromPeak);
                return createDecision(TradeAction.CLOSE, order,
                        String.format("TRAILING STOP: Peak %.2f%% → Current %.2f%%", lastWin, currentPnl));
            }
        }

        // Aggressive trailing for higher profits
        if (lastWin.compareTo(BigDecimal.valueOf(7.0)) >= 0) { // Had at least 7% profit
            BigDecimal dropFromPeak = lastWin.subtract(currentPnl);

            // If dropped 2.5% from peak when peak was 7%+
            if (dropFromPeak.compareTo(BigDecimal.valueOf(2.5)) >= 0) {
                log.info("📉 AGGRESSIVE TRAILING STOP triggered for {} - Peak: {}%, Current: {}%",
                        order.getSymbol(), lastWin, currentPnl);
                return createDecision(TradeAction.CLOSE, order,
                        String.format("AGGRESSIVE TRAILING: Peak %.2f%% → Current %.2f%%", lastWin, currentPnl));
            }
        }

        return createDecision(TradeAction.HOLD, order, "Trailing stop monitoring");
    }

    /**
     * Time-based exit strategy
     */
    private TradeDecision evaluateTimeBasedExit(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long hours = openDuration.toHours();
        long minutes = openDuration.toMinutes();

        // After 4 hours: Take any profit above 2%
        if (hours >= 4 && currentPnl.compareTo(BigDecimal.valueOf(2.0)) >= 0) {
            log.info("⏰ TIME EXIT (4h+): Taking {}% profit for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (4h): %.2f%% profit", currentPnl));
        }

        // After 6 hours: Take any profit above 1%
        if (hours >= 6 && currentPnl.compareTo(BigDecimal.valueOf(1.0)) >= 0) {
            log.info("⏰ TIME EXIT (6h+): Taking {}% profit for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (6h): %.2f%% profit", currentPnl));
        }

        // After 8 hours: Close if breakeven or slightly profitable (>0.3%)
        if (hours >= 8 && currentPnl.compareTo(BigDecimal.valueOf(0.3)) >= 0) {
            log.info("⏰ TIME EXIT (8h+): Taking {}% profit for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (8h): %.2f%% profit", currentPnl));
        }

        // After 12 hours: Force close if not too negative (better than -3%)
        if (hours >= 12 && currentPnl.compareTo(BigDecimal.valueOf(-3.0)) >= 0) {
            log.info("⏰ FORCE TIME EXIT (12h+): Closing at {}% for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("FORCE TIME EXIT (12h): %.2f%%", currentPnl));
        }

        return createDecision(TradeAction.HOLD, order, "Time criteria not met");
    }

    /**
     * Progressive risk reduction based on time and performance
     */
    private TradeDecision evaluateRiskReduction(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long hours = openDuration.toHours();

        // After 2 hours with minimal movement, tighten stops
        if (hours >= 2 && currentPnl.compareTo(BigDecimal.valueOf(-2.0)) >= 0
                && currentPnl.compareTo(BigDecimal.valueOf(0.5)) <= 0) {

            // If it's been sideways for 2+ hours, exit on small gains
            if (currentPnl.compareTo(BigDecimal.valueOf(0.3)) >= 0) {
                log.info("📊 SIDEWAYS EXIT: Closing flat trade at {}% for {}", currentPnl, order.getSymbol());
                return createDecision(TradeAction.CLOSE, order,
                        String.format("SIDEWAYS EXIT: %.2f%%", currentPnl));
            }
        }

        // After 1 hour, if losing more than -2%, consider early exit
        if (hours >= 1 && currentPnl.compareTo(BigDecimal.valueOf(-2.5)) <= 0) {
            log.info("🔻 EARLY LOSS MANAGEMENT: Position down {}% after {} hours for {}",
                    currentPnl, hours, order.getSymbol());

            // This is just monitoring, could add early exit logic here
            return createDecision(TradeAction.HOLD, order,
                    String.format("Monitoring early loss: %.2f%%", currentPnl));
        }

        return createDecision(TradeAction.HOLD, order, "Risk management monitoring");
    }

    private Duration getOrderDuration(OrderDto order) {
        if (order.getStarted() == null) {
            return Duration.ZERO;
        }
        return Duration.between(order.getStarted(), LocalDateTime.now());
    }

    private TradeDecision createDecision(TradeAction action, OrderDto order, String reason) {
        return new TradeDecision(action, order, reason);
    }
}