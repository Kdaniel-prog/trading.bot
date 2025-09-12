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

        // === CALCULATE ADJUSTED LIMITS ===
        BigDecimal adjustedWinLimit = BigDecimal.valueOf(tradingConfig.winLimit() / 5.0); // 1/5 of win limit
        BigDecimal adjustedStopLimit = BigDecimal.valueOf(tradingConfig.stopLimit() / 5.0); // 1/5 of stop limit
        BigDecimal zeroThreshold = BigDecimal.ZERO;

        // === IMMEDIATE EXITS WITH ADJUSTED LIMITS ===

        // 1. Adjusted Stop Loss: 1/5 of original
        if (currentPnlPercent.compareTo(adjustedStopLimit) <= 0) {
            log.info("🛑 ADJUSTED STOP LOSS triggered at {}% for {} (limit: {}%)",
                    currentPnlPercent, order.getSymbol(), adjustedStopLimit);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("ADJUSTED STOP LOSS: %.2f%%", currentPnlPercent));
        }

        // 2. Adjusted Target Profit: 1/5 of original
        if (currentPnlPercent.compareTo(adjustedWinLimit) >= 0) {
            log.info("🎯 ADJUSTED TARGET PROFIT reached at {}% for {} (limit: {}%)",
                    currentPnlPercent, order.getSymbol(), adjustedWinLimit);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("ADJUSTED TARGET PROFIT: %.2f%%", currentPnlPercent));
        }

        // === 40-MINUTE PROFIT DECLINE CHECK ===
        TradeDecision declineDecision = evaluateProfitDeclineAt40Min(order, currentPnlPercent, openDuration);
        if (declineDecision.shouldExecute()) {
            return declineDecision;
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
     * Check for profit decline after 40 minutes
     */
    private TradeDecision evaluateProfitDeclineAt40Min(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long minutes = openDuration.toMinutes();
        BigDecimal forceExitThreshold = BigDecimal.valueOf(Math.abs(tradingConfig.stopLimit()) / 2.0); // 30% of stop limit for force exit
        BigDecimal zeroThreshold = BigDecimal.ZERO;
        BigDecimal minDeclineThreshold = BigDecimal.valueOf(0.5);
        BigDecimal smallProfitThreshold = BigDecimal.valueOf(tradingConfig.winLimit() / 10.0); // 10% of win limit
        BigDecimal smallDeclineThreshold = BigDecimal.valueOf(0.3);

        // Only check after 40 minutes
        if (minutes < 40) {
            return createDecision(TradeAction.HOLD, order, "Not yet 40 minutes");
        }

        BigDecimal lastWin = order.getLastWin() != null ? order.getLastWin() : zeroThreshold;

        // If we're in profit and had higher profit before
        if (currentPnl.compareTo(zeroThreshold) > 0 && lastWin.compareTo(currentPnl) > 0) {
            BigDecimal decline = lastWin.subtract(currentPnl);

            // If profit declined by more than threshold from peak after 40+ minutes
            if (decline.compareTo(forceExitThreshold) >= 0) {
                log.info("📉 40-MIN PROFIT DECLINE detected for {} - Peak: {}%, Current: {}%, Decline: {}%",
                        order.getSymbol(), lastWin, currentPnl, decline);
                return createDecision(TradeAction.CLOSE, order,
                        String.format("40-MIN DECLINE: Peak %.2f%% → Current %.2f%%", lastWin, currentPnl));
            }

            // Even smaller decline threshold for very small profits
            if (currentPnl.compareTo(smallProfitThreshold) <= 0 &&
                    decline.compareTo(smallDeclineThreshold) >= 0) {
                log.info("📉 40-MIN SMALL PROFIT DECLINE for {} - Peak: {}%, Current: {}%",
                        order.getSymbol(), lastWin, currentPnl);
                return createDecision(TradeAction.CLOSE, order,
                        String.format("40-MIN SMALL DECLINE: Peak %.2f%% → Current %.2f%%", lastWin, currentPnl));
            }
        }

        return createDecision(TradeAction.HOLD, order, "No significant decline detected");
    }

    /**
     * Trailing stop mechanism using lastWin field with tighter controls
     */
    private TradeDecision evaluateTrailingStop(OrderDto order, BigDecimal currentPnl) {
        BigDecimal lastWin = order.getLastWin() != null ? order.getLastWin() : BigDecimal.ZERO;
        BigDecimal trailingThreshold = BigDecimal.valueOf(tradingConfig.winLimit() / 20.0); // 1/20 of win limit for trailing

        // Update lastWin if current profit is higher
        if (currentPnl.compareTo(lastWin) > 0) {
            order.setLastWin(currentPnl);
            log.debug("📈 New high for {}: {}%", order.getSymbol(), currentPnl);
            return createDecision(TradeAction.HOLD, order, "New profit high recorded");
        }

        // Tighter trailing stop conditions
        if (lastWin.compareTo(trailingThreshold) >= 0) { // Had at least threshold profit
            BigDecimal dropFromPeak = lastWin.subtract(currentPnl);

            log.info("📉 TIGHT TRAILING STOP triggered for {} - Peak: {}%, Current: {}%, Drop: {}%",
                    order.getSymbol(), lastWin, currentPnl, dropFromPeak);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIGHT TRAILING STOP: Peak %.2f%% → Current %.2f%%", lastWin, currentPnl));
        }

        return createDecision(TradeAction.HOLD, order, "Trailing stop monitoring");
    }

    /**
     * Time-based exit strategy with config-proportional controls
     */
    private TradeDecision evaluateTimeBasedExit(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long hours = openDuration.toHours();
        long minutes = openDuration.toMinutes();

        // Calculate proportional thresholds from config
        BigDecimal twoHourThreshold = BigDecimal.valueOf(tradingConfig.winLimit() / 2.0); // 1/2 of win limit for 2h exit
        BigDecimal threeHourThreshold = BigDecimal.valueOf(tradingConfig.winLimit() / 3.0); // 1/3 of win limit for 3h exit
        BigDecimal fourHourThreshold = BigDecimal.valueOf(tradingConfig.winLimit() / 5.0); // 1/5 of win limit for 4h exit
        BigDecimal forceExitThreshold = BigDecimal.valueOf(-Math.abs(tradingConfig.stopLimit()) / 3.33); // 30% of stop limit for force exit

        // After 2 hours: Take profit above proportional threshold
        if (hours >= 2 && currentPnl.compareTo(twoHourThreshold) >= 0) {
            log.info("⏰ TIME EXIT (2h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), twoHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (2h): %.2f%% profit", currentPnl));
        }

        // After 3 hours: Take profit above smaller threshold
        if (hours >= 3 && currentPnl.compareTo(threeHourThreshold) >= 0) {
            log.info("⏰ TIME EXIT (3h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), threeHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (3h): %.2f%% profit", currentPnl));
        }

        // After 4 hours: Close if above minimal threshold
        if (hours >= 4 && currentPnl.compareTo(fourHourThreshold) >= 0) {
            log.info("⏰ TIME EXIT (4h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), fourHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (4h): %.2f%% profit", currentPnl));
        }

        // After 6 hours: Force close if not too negative (proportional to stop limit)
        if (hours >= 6 && currentPnl.compareTo(forceExitThreshold) >= 0) {
            log.info("⏰ FORCE TIME EXIT (6h+): Closing at {}% for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), forceExitThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("FORCE TIME EXIT (6h): %.2f%%", currentPnl));
        }

        return createDecision(TradeAction.HOLD, order, "Time criteria not met");
    }

    /**
     * Progressive risk reduction based on time and performance with tighter controls
     */
    private TradeDecision evaluateRiskReduction(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long hours = openDuration.toHours();
        long minutes = openDuration.toMinutes();

        // Calculate tighter thresholds based on config values
        BigDecimal adjustedWinLimit = BigDecimal.valueOf(tradingConfig.winLimit() / 5.0);
        BigDecimal adjustedStopLimit = BigDecimal.valueOf(Math.abs(tradingConfig.stopLimit() / 5.0));

        BigDecimal sidewaysUpperBound = adjustedStopLimit.divide(BigDecimal.valueOf(5.0), 4, BigDecimal.ROUND_HALF_UP); // Tighter range
        BigDecimal sidewaysLowerBound = adjustedStopLimit.divide(BigDecimal.valueOf(10.0), 4, BigDecimal.ROUND_HALF_UP); // Tighter range
        BigDecimal smallGainThreshold = adjustedWinLimit.divide(BigDecimal.valueOf(10.0), 4, BigDecimal.ROUND_HALF_UP); // Lower threshold
        BigDecimal earlyLossThreshold = adjustedStopLimit.divide(BigDecimal.valueOf(2.0), 4, BigDecimal.ROUND_HALF_UP); // Tighter loss monitoring

        // After 90 minutes (1.5h) with minimal movement, tighten stops
        if (minutes >= 90 && currentPnl.compareTo(sidewaysUpperBound) >= 0
                && currentPnl.compareTo(sidewaysLowerBound.negate()) >= 0) {

            // If it's been sideways for 90+ minutes, exit on very small gains
            if (currentPnl.compareTo(smallGainThreshold) >= 0) {
                log.info("📊 TIGHT SIDEWAYS EXIT: Closing flat trade at {}% for {}", currentPnl, order.getSymbol());
                return createDecision(TradeAction.CLOSE, order,
                        String.format("TIGHT SIDEWAYS EXIT: %.2f%%", currentPnl));
            }
        }

        // After 30 minutes, if losing more than half of adjusted stop limit
        if (minutes >= 30 && currentPnl.compareTo(earlyLossThreshold.negate()) <= 0) {
            log.info("🔻 EARLY LOSS MANAGEMENT: Position down {}% after {} minutes for {}",
                    currentPnl, minutes, order.getSymbol());

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