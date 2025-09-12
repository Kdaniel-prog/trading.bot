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
import java.math.RoundingMode;
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

    private static final BigDecimal DIVIDE_BY_TWO = BigDecimal.valueOf(2);
    private static final BigDecimal DIVIDE_BY_THREE = BigDecimal.valueOf(3);
    private static final BigDecimal DIVIDE_BY_FOUR = BigDecimal.valueOf(4);
    private static final BigDecimal DIVIDE_BY_FIVE = BigDecimal.valueOf(5);
    private static final BigDecimal DIDIVIDE_BY_THREE_POINT_THREE = BigDecimal.valueOf(3.33);

    // === FRACTIONAL CONSTANTS FOR CALCULATIONS ===
    private static final BigDecimal HALF = BigDecimal.valueOf(2);  // 1/2
    private static final BigDecimal ONE_THIRD = BigDecimal.valueOf(3);  // 1/3
    private static final BigDecimal TWO_THIRDS = BigDecimal.valueOf(1.5);  // 2/3
    private static final BigDecimal ONE_QUARTER = BigDecimal.valueOf(4);  // 1/4
    private static final BigDecimal ONE_FIFTH = BigDecimal.valueOf(5);  // 1/5
    private static final BigDecimal THREE_POINT_THREE_THREE = BigDecimal.valueOf(3.33);  // 3.33 for ~30%

    // === SPECIFIC TRADING THRESHOLDS (not from config) ===
    private static final BigDecimal DECLINE_SMALL_THRESHOLD = BigDecimal.valueOf(0.3);
    private static final BigDecimal DECLINE_MIN_THRESHOLD = BigDecimal.valueOf(0.5);
    private static final int PROFIT_DECLINE_CHECK_MINUTES = 40;
    private static final int EARLY_LOSS_CHECK_MINUTES = 30;
    private static final int SIDEWAYS_CHECK_MINUTES = 90;
    private static final int TIME_EXIT_2H = 2;
    private static final int TIME_EXIT_3H = 3;
    private static final int TIME_EXIT_4H = 4;
    private static final int FORCE_EXIT_6H = 6;

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

        // stopLimit / 2 = 50% of stop limit for force exit
        BigDecimal forceExitThreshold = getStopLimitValue().divide(HALF, 4, RoundingMode.HALF_UP);
        BigDecimal zeroThreshold = BigDecimal.ZERO;
        BigDecimal minDeclineThreshold = DECLINE_MIN_THRESHOLD;
        // winLimit / 3 = 33% of win limit for small profit threshold
        BigDecimal smallProfitThreshold = getWinLimitValue().divide(ONE_THIRD, 4, RoundingMode.HALF_UP);
        BigDecimal smallDeclineThreshold = DECLINE_SMALL_THRESHOLD;

        // Only check after 40 minutes
        if (minutes < PROFIT_DECLINE_CHECK_MINUTES) {
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
        // winLimit / 4 = 25% of win limit for trailing threshold
        BigDecimal trailingThreshold = getWinLimitValue().divide(ONE_QUARTER, 4, RoundingMode.HALF_UP);

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

        // Calculate proportional thresholds from config
        BigDecimal twoHourThreshold = getWinLimitValue().divide(HALF, 4, RoundingMode.HALF_UP); // winLimit / 2
        BigDecimal threeHourThreshold = getWinLimitValue().divide(ONE_THIRD, 4, RoundingMode.HALF_UP); // winLimit / 3
        BigDecimal fourHourThreshold = getWinLimitValue().divide(ONE_FIFTH, 4, RoundingMode.HALF_UP); // winLimit / 5
        BigDecimal forceExitThreshold = getStopLimitValue().abs().divide(THREE_POINT_THREE_THREE, 4, RoundingMode.HALF_UP); // ~30% of stop limit

        // After 2 hours: Take profit above proportional threshold
        if (hours >= TIME_EXIT_2H && currentPnl.compareTo(twoHourThreshold) >= 0) {
            log.info("⏰ TIME EXIT (2h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), twoHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (2h): %.2f%% profit", currentPnl));
        }

        // After 3 hours: Take profit above smaller threshold
        if (hours >= TIME_EXIT_3H && currentPnl.compareTo(threeHourThreshold) >= 0) {
            log.info("⏰ TIME EXIT (3h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), threeHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (3h): %.2f%% profit", currentPnl));
        }

        // After 4 hours: Close if above minimal threshold
        if (hours >= TIME_EXIT_4H && currentPnl.compareTo(fourHourThreshold) >= 0) {
            log.info("⏰ TIME EXIT (4h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), fourHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (4h): %.2f%% profit", currentPnl));
        }

        // After 6 hours: Force close if not too negative (proportional to stop limit)
        if (hours >= FORCE_EXIT_6H && currentPnl.compareTo(forceExitThreshold.negate()) >= 0) {
            log.info("⏰ FORCE TIME EXIT (6h+): Closing at {}% for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), forceExitThreshold.negate());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("FORCE TIME EXIT (6h): %.2f%%", currentPnl));
        }

        return createDecision(TradeAction.HOLD, order, "Time criteria not met");
    }

    /**
     * Progressive risk reduction based on time and performance with tighter controls
     */
    private TradeDecision evaluateRiskReduction(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long minutes = openDuration.toMinutes();

        // Calculate tighter thresholds based on config values
        BigDecimal adjustedWinLimit = getWinLimitValue().divide(DIVIDE_BY_FIVE, 4, RoundingMode.HALF_UP); // winLimit / 5 (4.0 / 5 = 0.8)
        BigDecimal adjustedStopLimit = getStopLimitValue().abs().divide(DIVIDE_BY_FIVE, 4, RoundingMode.HALF_UP); // stopLimit / 5 (2.0 / 5 = 0.4)

        BigDecimal sidewaysUpperBound = adjustedStopLimit.divide(DIVIDE_BY_FIVE, 4, RoundingMode.HALF_UP); // Tighter range (0.4 / 5 = 0.08)
        BigDecimal sidewaysLowerBound = adjustedStopLimit.divide(DIVIDE_BY_TWO, 4, RoundingMode.HALF_UP); // Tighter range (0.4 / 2 = 0.2)
        BigDecimal smallGainThreshold = adjustedWinLimit.divide(DIVIDE_BY_TWO, 4, RoundingMode.HALF_UP); // Lower threshold (0.8 / 2 = 0.4)
        BigDecimal earlyLossThreshold = adjustedStopLimit.divide(DIVIDE_BY_TWO, 4, RoundingMode.HALF_UP); // Tighter loss monitoring (0.4 / 2 = 0.2)

        // After 90 minutes (1.5h) with minimal movement, tighten stops
        if (minutes >= SIDEWAYS_CHECK_MINUTES && currentPnl.compareTo(sidewaysUpperBound) >= 0
                && currentPnl.compareTo(sidewaysLowerBound.negate()) >= 0) {

            // If it's been sideways for 90+ minutes, exit on very small gains
            if (currentPnl.compareTo(smallGainThreshold) >= 0) {
                log.info("📊 TIGHT SIDEWAYS EXIT: Closing flat trade at {}% for {}", currentPnl, order.getSymbol());
                return createDecision(TradeAction.CLOSE, order,
                        String.format("TIGHT SIDEWAYS EXIT: %.2f%%", currentPnl));
            }
        }

        // After 30 minutes, if losing more than half of adjusted stop limit
        if (minutes >= EARLY_LOSS_CHECK_MINUTES && currentPnl.compareTo(earlyLossThreshold.negate()) <= 0) {
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

    // === HELPER METHODS FOR CONFIG VALUES ===
    private BigDecimal getWinLimitValue() {
        return BigDecimal.valueOf(tradingConfig.winLimit());
    }

    private BigDecimal getStopLimitValue() {
        return BigDecimal.valueOf(tradingConfig.stopLimit());
    }
}