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

    // === OPTIMIZED TRADING THRESHOLDS ===
    static final int EARLY_PROFIT_CHECK_MINUTES = 15; // Earlier profit taking opportunity
    static final int PROFIT_DECLINE_CHECK_MINUTES = 25; // Reduced from 30
    static final int SIDEWAYS_CHECK_MINUTES = 60; // Reduced from 90
    static final int LOSE_CHECK_MINUTES = 45; // Reduced from 120
    static final int TIME_EXIT_1 = 3; // Reduced from 4 hours
    static final int TIME_EXIT_2 = 4; // Reduced from 5 hours
    static final int TIME_EXIT_3 = 5; // Reduced from 6 hours
    static final int FORCE_EXIT_4 = 6; // Reduced from 7 hours

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

        // === TRADE CONFIG CHECK ===
        TradeDecision configDecision = tradeConfigLimits(order, currentPnlPercent, openDuration);
        if (configDecision.shouldExecute()) {
            return configDecision;
        }

        // === IMPROVED TRAILING STOP MECHANISM ===
        TradeDecision trailingDecision = evaluateImprovedTrailingStop(order, currentPnlPercent, openDuration);
        if (trailingDecision.shouldExecute()) {
            return trailingDecision;
        }

        // === FASTER PROFIT DECLINE CHECK ===
        TradeDecision declineDecision = evaluateProfitDeclineAtXMin(order, currentPnlPercent, openDuration);
        if (declineDecision.shouldExecute()) {
            return declineDecision;
        }

        // === MORE AGGRESSIVE TIME-BASED EXITS ===
        TradeDecision timeDecision = evaluateTimeBasedExit(order, currentPnlPercent, openDuration);
        if (timeDecision.shouldExecute()) {
            return timeDecision;
        }

        // === TIGHTER RISK REDUCTION ===
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

        BigDecimal winLimitThreshold = BigDecimal.valueOf(tradingConfig.winLimit());
        BigDecimal stopLimitThreshold = BigDecimal.valueOf(tradingConfig.stopLimit());

        if (currentPnl.compareTo(winLimitThreshold) >= 0) {
            log.info("✅ TRADE CONFIG WIN LIMIT reached for {} - Current: {}%",
                    order.getSymbol(), currentPnl);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("WIN LIMIT: %.2f%%", currentPnl));
        }

        if (currentPnl.compareTo(stopLimitThreshold) <= 0) {
            log.info("❌ TRADE CONFIG STOP LIMIT reached for {} - Current: {}%",
                    order.getSymbol(), currentPnl);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("STOP LIMIT: %.2f%%", currentPnl));
        }

        return createDecision(TradeAction.HOLD, order, "Within config limits");
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
     * IMPROVED: More responsive trailing stop
     */
    private TradeDecision evaluateImprovedTrailingStop(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        BigDecimal lastWin = order.getLastWin() != null ? order.getLastWin() : BigDecimal.ZERO;
        long minutes = openDuration.toMinutes();

        // Dynamic trailing threshold based on time and peak
        BigDecimal baseTrailingThreshold = BigDecimal.valueOf(0.5); // 0.5% base
        BigDecimal dynamicThreshold = baseTrailingThreshold;

        // Tighter trailing after longer duration
        if (minutes > 60) {
            dynamicThreshold = BigDecimal.valueOf(0.3); // 0.3% after 1 hour
        }
        if (minutes > 120) {
            dynamicThreshold = BigDecimal.valueOf(0.2); // 0.2% after 2 hours
        }

        // Update lastWin
        if (currentPnl.compareTo(lastWin) > 0) {
            order.setLastWin(currentPnl);
            log.debug("📈 New high for {}: {}%", order.getSymbol(), currentPnl);
            return createDecision(TradeAction.HOLD, order, "New profit high");
        }

        // Activate trailing if we had significant profit
        if (lastWin.compareTo(BigDecimal.valueOf(0.8)) >= 0) { // If peak was 0.8%+
            BigDecimal dropFromPeak = lastWin.subtract(currentPnl);

            if (dropFromPeak.compareTo(dynamicThreshold) >= 0) {
                // Ensure we still have reasonable profit
                BigDecimal minimumAcceptable = BigDecimal.valueOf(0.25); // 0.25% minimum

                if (currentPnl.compareTo(minimumAcceptable) >= 0) {
                    log.info("📉 IMPROVED TRAILING STOP for {} - Peak: {}%, Current: {}%, Drop: {}%",
                            order.getSymbol(), lastWin, currentPnl, dropFromPeak);
                    return createDecision(TradeAction.CLOSE, order,
                            String.format("TRAILING: Peak %.2f%% → Current %.2f%%", lastWin, currentPnl));
                }
            }
        }

        return createDecision(TradeAction.HOLD, order, "Trailing monitoring");
    }

    /**
     * More aggressive time-based exits
     */
    private TradeDecision evaluateTimeBasedExit(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long hours = openDuration.toHours();
        long minutes = openDuration.toMinutes();

        // More aggressive time-based thresholds
        BigDecimal smallProfitThreshold = BigDecimal.valueOf(0.3); // 0.3%
        BigDecimal tinyProfitThreshold = BigDecimal.valueOf(0.15); // 0.15%

        // After 3 hours: Take any reasonable profit
        if (hours >= TIME_EXIT_1 && currentPnl.compareTo(smallProfitThreshold) >= 0) {
            log.info("⏰ TIME EXIT (3h+): Taking {}% profit for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (3h): %.2f%%", currentPnl));
        }

        // After 4 hours: Take tiny profits too
        if (hours >= TIME_EXIT_2 && currentPnl.compareTo(tinyProfitThreshold) >= 0) {
            log.info("⏰ TIME EXIT (4h+): Taking tiny {}% profit for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (4h): %.2f%%", currentPnl));
        }

        // After 5 hours: Close if not too negative
        if (hours >= TIME_EXIT_3 && currentPnl.compareTo(BigDecimal.valueOf(-1.5)) >= 0) {
            log.info("⏰ TIME EXIT (5h+): Closing at {}% for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (5h): %.2f%%", currentPnl));
        }

        // After 6 hours: Force close unless very negative
        if (hours >= FORCE_EXIT_4 && currentPnl.compareTo(BigDecimal.valueOf(-2.5)) >= 0) {
            log.info("⏰ FORCE TIME EXIT (6h+): Closing at {}% for {}", currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("FORCE EXIT (6h): %.2f%%", currentPnl));
        }

        return createDecision(TradeAction.HOLD, order, "Time criteria not met");
    }

    /**
     * Tighter risk reduction
     */
    private TradeDecision evaluateRiskReduction(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long minutes = openDuration.toMinutes();

        // Close sideways trades faster
        if (minutes >= SIDEWAYS_CHECK_MINUTES) {
            // If PnL between -0.2% and +0.2% after 1 hour, close it
            if (currentPnl.compareTo(BigDecimal.valueOf(-0.2)) >= 0 &&
                    currentPnl.compareTo(BigDecimal.valueOf(0.2)) <= 0) {
                log.info("📊 TIGHT SIDEWAYS EXIT: Closing flat trade at {}% for {}",
                        currentPnl, order.getSymbol());
                return createDecision(TradeAction.CLOSE, order,
                        String.format("SIDEWAYS: %.2f%%", currentPnl));
            }
        }

        return createDecision(TradeAction.HOLD, order, "Risk monitoring");
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

    private BigDecimal getWinLimitValue() {
        return BigDecimal.valueOf(tradingConfig.winLimit());
    }
}