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
     * Check for profit decline after 30 minutes - IMPROVED VERSION
     * */
    private TradeDecision evaluateProfitDeclineAtXMin(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long minutes = openDuration.toMinutes();

        // Thresholds
        BigDecimal forceExitThreshold = loseOneThird; // pl. 0.5%
        BigDecimal zeroThreshold = BigDecimal.ZERO;
        BigDecimal smallProfitThreshold = winOneThird.divide(DIVIDE_BY_TWO, RoundingMode.HALF_UP); // pl. 0.25%

        // JAVÍTÁS: Minimális decline küszöbök
        BigDecimal minSignificantDecline = new BigDecimal("0.15"); // legalább 0.15% csökkenés kell
        BigDecimal minSmallProfitDecline = new BigDecimal("0.30"); // kis profithoz nagyobb csökkenés kell

        // Only check after 30 minutes
        if (minutes < PROFIT_DECLINE_CHECK_MINUTES) {
            return createDecision(TradeAction.HOLD, order, "Not yet 30 minutes");
        }

        BigDecimal lastWin = order.getLastWin() != null ? order.getLastWin() : zeroThreshold;

        // If we're in profit and had higher profit before
        if (currentPnl.compareTo(zeroThreshold) > 0 && lastWin.compareTo(currentPnl) > 0) {
            BigDecimal decline = lastWin.subtract(currentPnl);

            // JAVÍTÁS: Relative decline számítás (százalékban a peak-hez képest)
            BigDecimal relativeDecline = decline.divide(lastWin, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));

            // NAGY PROFIT ESETÉN: Szigorúbb feltételek
            if (lastWin.compareTo(new BigDecimal("2.0")) >= 0) { // Ha peak > 2%
                // Legalább 20% relatív csökkenés VAGY 0.5% abszolút csökkenés
                if (relativeDecline.compareTo(new BigDecimal("20")) >= 0 && decline.compareTo(forceExitThreshold) >= 0) {
                    log.info("📉 {}-MIN LARGE PROFIT DECLINE detected for {} - Peak: {}%, Current: {}%, Relative Decline: {}%",
                            PROFIT_DECLINE_CHECK_MINUTES, order.getSymbol(), lastWin, currentPnl, relativeDecline);
                    return createDecision(TradeAction.CLOSE, order,
                            String.format("%s-MIN LARGE DECLINE: Peak %.2f%% → Current %.2f%% (-%s%%)",
                                    PROFIT_DECLINE_CHECK_MINUTES, lastWin, currentPnl, relativeDecline));
                }
            }

            // KÖZEPES PROFIT ESETÉN: (0.5% - 2.0%)
            else if (lastWin.compareTo(smallProfitThreshold.multiply(new BigDecimal("2"))) >= 0) {
                // Legalább 25% relatív csökkenés ÉS minimum 0.3% abszolút csökkenés
                if (relativeDecline.compareTo(new BigDecimal("25")) >= 0 && decline.compareTo(minSmallProfitDecline) >= 0) {
                    log.info("📉 {}-MIN MEDIUM PROFIT DECLINE detected for {} - Peak: {}%, Current: {}%, Relative Decline: {}%",
                            PROFIT_DECLINE_CHECK_MINUTES, order.getSymbol(), lastWin, currentPnl, relativeDecline);
                    return createDecision(TradeAction.CLOSE, order,
                            String.format("%s-MIN MEDIUM DECLINE: Peak %.2f%% → Current %.2f%% (-%s%%)",
                                    PROFIT_DECLINE_CHECK_MINUTES, lastWin, currentPnl, relativeDecline));
                }
            }

            // KIS PROFIT ESETÉN: Még szigorúbb feltételek
            else if (currentPnl.compareTo(smallProfitThreshold) >= 0) {
                // Legalább 40% relatív csökkenés ÉS minimum 0.15% abszolút csökkenés
                if (relativeDecline.compareTo(new BigDecimal("40")) >= 0 && decline.compareTo(minSignificantDecline) >= 0) {
                    log.info("📉 {}-MIN SMALL PROFIT DECLINE for {} - Peak: {}%, Current: {}%, Relative Decline: {}%",
                            PROFIT_DECLINE_CHECK_MINUTES, order.getSymbol(), lastWin, currentPnl, relativeDecline);
                    return createDecision(TradeAction.CLOSE, order,
                            String.format("%s-MIN SMALL DECLINE: Peak %.2f%% → Current %.2f%% (-%s%%)",
                                    PROFIT_DECLINE_CHECK_MINUTES, lastWin, currentPnl, relativeDecline));
                }
            }

            // JAVÍTÁS: Csak log, ha nincs close
            if (decline.compareTo(minSignificantDecline.divide(new BigDecimal("3"), RoundingMode.HALF_UP)) >= 0) {
                log.debug("📊 {}-MIN Minor decline for {} - Peak: {}%, Current: {}%, Decline: {}% (holding)",
                        PROFIT_DECLINE_CHECK_MINUTES, order.getSymbol(), lastWin, currentPnl, decline);
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
     * Time-based exit strategy with progressive hourly thresholds
     */
    private TradeDecision evaluateTimeBasedExit(OrderDto order, BigDecimal currentPnl, Duration openDuration) {
        long hours = openDuration.toHours();

        // Calculate proportional thresholds from config using winOneThird as base
        BigDecimal fourHourThreshold = winOneThird.divide(DIVIDE_BY_TWO, 4, RoundingMode.HALF_UP); // winOneThird / 2
        BigDecimal sixHourThreshold = fourHourThreshold.divide(DIVIDE_BY_TWO, 4, RoundingMode.HALF_UP); // winOneThird / 4
        BigDecimal forceExitThreshold = loseOneThird.negate(); // Using negative loseOneThird for force exit

        // After 2 hours: Take profit above winOneThird threshold
        if (hours >= 2 && currentPnl.compareTo(winOneThird) >= 0) {
            log.info("⏰ TIME EXIT (2h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), winOneThird);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (2h): %.2f%% profit", currentPnl));
        }

        // After 3 hours: Take profit above winOneThird threshold
        if (hours >= 3 && currentPnl.compareTo(winOneThird) >= 0) {
            log.info("⏰ TIME EXIT (3h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), winOneThird);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (3h): %.2f%% profit", currentPnl));
        }

        // After 4 hours: Close if above half of winOneThird threshold
        if (hours >= 4 && currentPnl.compareTo(fourHourThreshold) >= 0) {
            log.info("⏰ TIME EXIT (4h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), fourHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (4h): %.2f%% profit", currentPnl));
        }

        // After 5 hours: Close if above quarter of winOneThird threshold
        if (hours >= 5 && currentPnl.compareTo(sixHourThreshold) >= 0) {
            log.info("⏰ TIME EXIT (5h+): Taking {}% profit for {} (threshold: {}%)",
                    currentPnl, order.getSymbol(), sixHourThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (5h): %.2f%% profit", currentPnl));
        }

        // After 6 hours: Close at breakeven or small profit (threshold = 0)
        if (hours >= 6 && currentPnl.compareTo(BigDecimal.ZERO) >= 0) {
            log.info("⏰ TIME EXIT (6h+): Taking {}% profit for {} (breakeven or better)",
                    currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("TIME EXIT (6h): %.2f%% breakeven+", currentPnl));
        }

        // After 7 hours: Force close if loss is not too severe
        if (hours >= 7 && currentPnl.compareTo(forceExitThreshold) >= 0) {
            log.info("⏰ FORCE TIME EXIT (7h+): Closing at {}% for {} (max loss threshold: {}%)",
                    currentPnl, order.getSymbol(), forceExitThreshold);
            return createDecision(TradeAction.CLOSE, order,
                    String.format("FORCE TIME EXIT (7h): %.2f%%", currentPnl));
        }

        // After 8+ hours: Absolute force close regardless of loss
        if (hours >= 8) {
            log.warn("⏰ ABSOLUTE FORCE EXIT (8h+): Closing at {}% for {} (maximum time exceeded)",
                    currentPnl, order.getSymbol());
            return createDecision(TradeAction.CLOSE, order,
                    String.format("ABSOLUTE FORCE EXIT (8h): %.2f%%", currentPnl));
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