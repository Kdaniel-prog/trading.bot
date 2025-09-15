package kd.trading.bot.service.tradingProcess;

import jakarta.annotation.PostConstruct;
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
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradeDecisionService {
    final TradingConfig tradingConfig;

    // === CALCULATION DIVISORS ===
    static final BigDecimal DIVIDE_BY_TWO = BigDecimal.valueOf(2);
    static final BigDecimal DIVIDE_BY_THREE = BigDecimal.valueOf(3);
    static final BigDecimal DIVIDE_BY_FOUR = BigDecimal.valueOf(4);

    // === CONFIG-BASED THRESHOLDS ===
    BigDecimal winOneThird;
    BigDecimal loseOneThird;

    // === SPECIFIC TRADING THRESHOLDS ==
    static final int PROFIT_DECLINE_CHECK_MINUTES = 30;
    static final int SIDEWAYS_CHECK_MINUTES = 90;
    static final int LOSE_CHECK_MINUTES = 120;
    static final int TIME_EXIT_1 = 4;
    static final int TIME_EXIT_2 = 5;
    static final int TIME_EXIT_3 = 6;
    static final int FORCE_EXIT_4 = 7;

    @PostConstruct
    void init() {
        this.winOneThird = BigDecimal.valueOf(tradingConfig.winLimit()).divide(DIVIDE_BY_THREE, 4, RoundingMode.HALF_UP);
        this.loseOneThird = BigDecimal.valueOf(Math.abs(tradingConfig.stopLimit())).divide(DIVIDE_BY_THREE, 4, RoundingMode.HALF_UP);
    }

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


        // === TRADE CONFIG CHECK === GOOD
        TradeDecision configDecision = tradeConfigLimits(order, currentPnlPercent, openDuration);
        if (configDecision.shouldExecute()) {
            return configDecision;
        }


        // === X-MINUTE PROFIT DECLINE CHECK === GOOD
        TradeDecision declineDecision = evaluateProfitDeclineAtXMin(order, currentPnlPercent, openDuration);
        if (declineDecision.shouldExecute()) {
            return declineDecision;
        }

        // === TRAILING STOP MECHANISM === Good
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

    private TradeDecision tradeConfigLimits(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        BigDecimal zeroThreshold = BigDecimal.ZERO;
        BigDecimal lastWin = order.getLastWin() != null ? order.getLastWin() : zeroThreshold;
        BigDecimal decline = lastWin.subtract(currentPnl);
        long minutes = openDuration.toMinutes();

        BigDecimal winLimitThreshold = BigDecimal.valueOf(tradingConfig.winLimit());    // 6.0%
        BigDecimal stopLimitThreshold = BigDecimal.valueOf(tradingConfig.stopLimit()); // -3.0%

        if (currentPnl.compareTo(winLimitThreshold) >= 0) {
            log.info("✅ {}-MIN TRADE CONFIG PROFIT DECLINE detected for {} - Peak: {}%, Current: {}%, Decline: {}%",
                    minutes, order.getSymbol(), lastWin, currentPnl, decline);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("✅ %s-MIN TRADE CONFIG PROFIT detected: Peak %.2f%% → Current %.2f%%", minutes, lastWin, currentPnl));
        }

        //tradeconfig legyen nagyobb
        if (currentPnl.compareTo(stopLimitThreshold) <= 0) {
            log.info("❌ {}-MIN TRADE CONFIG LOSE detected for {} - Peak: {}%, Current: {}%, Decline: {}%",
                    minutes, order.getSymbol(), lastWin, currentPnl, decline);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("❌ %s-MIN TRADE CONFIG LOSE detected: Peak %.2f%% → Current %.2f%%", minutes, lastWin, currentPnl));
        }

        // No limits reached, continue holding
        log.debug("No trade limits reached for {} - Current PnL: {}% (Win: {}%, Stop: {}%)",
                order.getSymbol(), currentPnl, winLimitThreshold, stopLimitThreshold);

        return createDecision(TradeAction.HOLD, order, "No significant decline detected");
    }

    /**
     * Check for profit decline after 40 minutes
     */
    private TradeDecision evaluateProfitDeclineAtXMin(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long minutes = openDuration.toMinutes();

        // Using loseOneThird for force exit threshold (1/3 of stop limit)
        BigDecimal forceExitThreshold = loseOneThird;
        BigDecimal zeroThreshold = BigDecimal.ZERO;
        // Using winOneThird for small profit threshold
        BigDecimal smallProfitThreshold = winOneThird.divide(DIVIDE_BY_TWO, RoundingMode.HALF_UP);

        // Only check after 40 minutes
        if (minutes < PROFIT_DECLINE_CHECK_MINUTES) {
            return createDecision(TradeAction.HOLD, order, "Not yet 30 minutes");
        }

        BigDecimal lastWin = order.getLastWin() != null ? order.getLastWin() : zeroThreshold;

        // If we're in profit and had higher profit before
        if (currentPnl.compareTo(zeroThreshold) > 0 && lastWin.compareTo(currentPnl) > 0) {
            BigDecimal decline = lastWin.subtract(currentPnl);

            // If profit declined by more than threshold from peak after 40+ minutes
            if (decline.compareTo(forceExitThreshold) >= 0) {
                log.info("📉 {}-MIN PROFIT DECLINE detected for {} - Peak: {}%, Current: {}%, Decline: {}%",
                        PROFIT_DECLINE_CHECK_MINUTES, order.getSymbol(), lastWin, currentPnl, decline);
                return createDecision(TradeAction.CLOSE, order,
                        String.format("%s-MIN DECLINE: Peak %.2f%% → Current %.2f%%", PROFIT_DECLINE_CHECK_MINUTES, lastWin, currentPnl));
            }

            // Even smaller decline threshold for very small profits (fontos)
            if (currentPnl.compareTo(smallProfitThreshold) >= 0) {
                log.info("📉 {}-MIN SMALL PROFIT DECLINE for {} - Peak: {}%, Current: {}%",
                        PROFIT_DECLINE_CHECK_MINUTES, order.getSymbol(), lastWin, currentPnl);
                return createDecision(TradeAction.CLOSE, order,
                        String.format("%s-MIN SMALL DECLINE: Peak %.2f%% → Current %.2f%%",PROFIT_DECLINE_CHECK_MINUTES, lastWin, currentPnl));
            }
        }

        return createDecision(TradeAction.HOLD, order, "No significant decline detected");
    }

    /**
     * Trailing stop mechanism using lastWin field with tighter controls and minimum profit guarantee
     */
    private TradeDecision evaluateTrailingStop(OrderDto order, BigDecimal currentPnl) {
        BigDecimal lastWin = order.getLastWin() != null ? order.getLastWin() : BigDecimal.ZERO;

        // Using winLimit / 4 for trailing threshold (1.5% when winLimit is 6%)
        BigDecimal trailingThreshold = getWinLimitValue().divide(DIVIDE_BY_FOUR, 4, RoundingMode.HALF_UP);

        // MINIMUM PROFIT we want to secure (0.6%)
        BigDecimal minimumProfitTarget = BigDecimal.valueOf(0.6);

        // Update lastWin if current profit is higher
        if (currentPnl.compareTo(lastWin) > 0) {
            order.setLastWin(currentPnl);
            log.debug("📈 New high for {}: {}%", order.getSymbol(), currentPnl);
            return createDecision(TradeAction.HOLD, order, "New profit high recorded");
        }

        // Only activate trailing stop if we had at least the threshold profit AND there's an actual drop
        if (lastWin.compareTo(trailingThreshold) >= 0) {
            BigDecimal dropFromPeak = lastWin.subtract(currentPnl);

            // There's an actual drop, now check if we can secure minimum profit
            if (currentPnl.compareTo(minimumProfitTarget) >= 0) {
                log.info("📉 TIGHT TRAILING STOP triggered for {} - Peak: {}%, Current: {}%, Drop: {}%, Secured: {}%",
                        order.getSymbol(), lastWin, currentPnl, dropFromPeak, currentPnl);
                return createDecision(TradeAction.CLOSE, order,
                        String.format("TIGHT TRAILING STOP: Peak %.2f%% → Secured %.2f%% (Min: %.2f%%)",
                                lastWin, currentPnl, minimumProfitTarget));
            } else {
                // Drop too big, would result in less than minimum profit - continue holding and hope for recovery
                log.warn("⚠️ TRAILING STOP blocked for {} - Current: {}% < Minimum: {}%, Peak was: {}%",
                        order.getSymbol(), currentPnl, minimumProfitTarget, lastWin);
                return createDecision(TradeAction.HOLD, order,
                        String.format("Holding for minimum profit: Current %.2f%% < Target %.2f%%",
                                currentPnl, minimumProfitTarget));
            }

        }

        return createDecision(TradeAction.HOLD, order, "Trailing stop monitoring");
    }

    /**
     * Time-based exit strategy with config-proportional controls
     */
    private TradeDecision evaluateTimeBasedExit(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long hours = openDuration.toHours();

        // Calculate proportional thresholds from config using winOneThird as base
        BigDecimal fourHourThreshold = winOneThird.divide(DIVIDE_BY_TWO, 4, RoundingMode.HALF_UP); // winOneThird / 2
        BigDecimal forceExitThreshold = loseOneThird; // Using loseOneThird for force exit

        // After 2 hours: Take profit above proportional threshold
        if (hours >= TIME_EXIT_1 && currentPnl.compareTo(winOneThird) <= 0) {
            log.info("⏰ TIME EXIT (2h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), winOneThird);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (2h): %.2f%% profit", currentPnl));
        }

        // After 3 hours: Take profit above smaller threshold
        /**
         if (hours >= TIME_EXIT_2 && currentPnl.compareTo(winOneThird) <= 0) {
         log.info("⏰ TIME EXIT (3h+): Taking {}% profit for {} (threshold: {}%)",
         currentPnl, order.getSymbol(), winOneThird);
         return createDecision(TradeAction.CLOSE, order,
         String.format("TIME EXIT (3h): %.2f%% profit", currentPnl));
         }
         */
        // After 4 hours: Close if above minimal threshold
        if (hours >= TIME_EXIT_3 && currentPnl.compareTo(fourHourThreshold) <= 0) {
            log.info("⏰ TIME EXIT (4h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), fourHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (4h): %.2f%% profit", currentPnl));
        }

        // After 6 hours: Force close if not too negative (using loseOneThird)
        if (hours >= FORCE_EXIT_4 && currentPnl.compareTo(forceExitThreshold) <= 0) {
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
        long minutes = openDuration.toMinutes();

        // Using config-based one-third values as base thresholds
        BigDecimal earlyLossThreshold = loseOneThird.divide(DIVIDE_BY_TWO, 4, RoundingMode.HALF_UP); // loseOneThird / 2

        // After 90 minutes (1.5h) with minimal movement, tighten stops
        if (minutes >= SIDEWAYS_CHECK_MINUTES && currentPnl.compareTo(winOneThird) >= 0) {
            log.info("📊 TIGHT SIDEWAYS EXIT: Closing flat trade at {}% for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIGHT SIDEWAYS EXIT: %.2f%%", currentPnl));
        }

        /**
         // After 30 minutes, if losing more than half of loseOneThird
         if (minutes >= LOSE_CHECK_MINUTES && currentPnl.compareTo(earlyLossThreshold) >= 0) {
         log.info("🔻 EARLY LOSS MANAGEMENT: Position down {}% after {} minutes for {}",
         currentPnl, minutes, order.getSymbol());

         return createDecision(TradeAction.HOLD, order,
         String.format("Monitoring early loss: %.2f%%", currentPnl));
         }
         */
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

}