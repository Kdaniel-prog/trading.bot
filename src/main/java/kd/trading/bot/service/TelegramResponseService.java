package kd.trading.bot.service;

import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.PnlResult;
import kd.trading.bot.model.TradeRisk;
import kd.trading.bot.service.tradingProcess.TradeAnalyticsService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TelegramResponseService {
    TradeAnalyticsService analyticsService;
    TradingConfig tradingConfig;

    public String getTradeInfos() {
        Map<OrderDto, PnlResult> tradeInfos = analyticsService.getCurrentTradeAnalytics();
        return formatTradeInfos(tradeInfos);
    }

    public String getTradeSummary() {
        Map<OrderDto, PnlResult> tradeInfos = analyticsService.getCurrentTradeAnalytics();
        BigDecimal totalPnl = analyticsService.getTotalPnl();

        return String.format("""
            📊 **Trading Summary**
            Active Trades: %d
            Total PnL: %.4f USDT
            """, tradeInfos.size(), totalPnl);
    }

    public String getTopPerformers(int count) {
        return analyticsService.getCurrentTradeAnalytics().entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().getPnlPercent().compareTo(e1.getValue().getPnlPercent()))
                .limit(count)
                .map(entry -> String.format("🏆 %s: %.2f%%",
                        entry.getKey().getSymbol(), entry.getValue().getPnlPercent()))
                .collect(Collectors.joining("\n"));
    }

    public String getRiskyTrades() {
        List<TradeRisk> risks = analyticsService.getHighRiskTrades(BigDecimal.valueOf(5.0));
        return risks.stream()
                .map(risk -> String.format("⚠️ %s: %.2f%%",
                        risk.getOrder().getSymbol(), risk.getPnl().getPnlPercent()))
                .collect(Collectors.joining("\n"));
    }

    private String formatTradeInfos(Map<OrderDto, PnlResult> tradeInfos) {
        if (tradeInfos.isEmpty()) {
            return """
                📊 **ACTIVE TRADES**
                ━━━━━━━━━━━━━━━━━━━━━━
                📭 No active trades
                """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 **ACTIVE TRADES**\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");

        // Calculate totals
        BigDecimal totalPnl = tradeInfos.values().stream()
                .map(PnlResult::getPnlAbs)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int profitableTrades = (int) tradeInfos.values().stream()
                .filter(pnl -> pnl.getPnlPercent().compareTo(BigDecimal.ZERO) > 0)
                .count();

        tradeInfos.forEach((order, pnl) -> {
            // Fixed: Corrected the logic for direction display
            String directionEmoji = pnl.isLong() ? "🟢" : "🔴";
            String directionText = pnl.isLong() ? "LONG" : "SHORT";

            // PnL emoji based on performance
            String pnlEmoji = getPnlEmoji(pnl.getPnlPercent());

            // Time info if available
            String timeInfo = "";
            if (order.getStarted() != null) {
                long minutes = Duration.between(order.getStarted(), LocalDateTime.now()).toMinutes();
                timeInfo = String.format(" ⏱️ %dm", minutes);
            }

            sb.append(String.format("""
                %s **%s** %s %s%s
                💰 **%.2f%%** (%s USDT) %s
                📈 Entry: `%s` → Last: `%s`
                📊 Qty: `%s`
                ━━━━━━━━━━━━━━━━━━━━━━
                
                """,
                    directionEmoji,
                    order.getSymbol(),
                    directionText,
                    pnlEmoji,
                    timeInfo,
                    pnl.getPnlPercent(),
                    pnl.getPnlAbs().setScale(4, RoundingMode.HALF_UP),
                    getPnlStatusText(pnl.getPnlPercent()),
                    pnl.getEntryPrice().setScale(6, RoundingMode.HALF_UP),
                    pnl.getCurrentPrice().setScale(6, RoundingMode.HALF_UP),
                    pnl.getQty().setScale(4, RoundingMode.HALF_UP)
            ));
        });

        // Summary footer
        sb.append(String.format("""
            📊 **SUMMARY**
            • Total Trades: %d
            • Profitable: %d 🟢 | Losing: %d 🔴
            • Total PnL: **%.4f USDT** %s
            """,
                tradeInfos.size(),
                profitableTrades,
                tradeInfos.size() - profitableTrades,
                totalPnl,
                totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉"
        ));

        return sb.toString();
    }

    private String getPnlEmoji(BigDecimal pnlPercent) {
        // Use trading config limits for dynamic thresholds
        double winLimit = tradingConfig.winLimit() != null ? tradingConfig.winLimit() : 5.0;
        double stopLimit = tradingConfig.stopLimit() != null ? Math.abs(tradingConfig.stopLimit()) : 5.0;

        // Calculate thresholds based on config limits
        double highWin = winLimit;                    // Full win limit
        double goodWin = winLimit * 0.4;              // 40% of win limit
        double smallWin = winLimit * 0.1;             // 10% of win limit
        double smallLoss = stopLimit * 0.1;           // 10% of stop limit
        double moderateLoss = stopLimit * 0.4;        // 40% of stop limit
        double highLoss = stopLimit;                  // Full stop limit

        if (pnlPercent.compareTo(BigDecimal.valueOf(highWin)) >= 0) return "🚀";
        if (pnlPercent.compareTo(BigDecimal.valueOf(goodWin)) >= 0) return "📈";
        if (pnlPercent.compareTo(BigDecimal.valueOf(smallWin)) >= 0) return "✅";
        if (pnlPercent.compareTo(BigDecimal.valueOf(-smallLoss)) >= 0) return "⚪";
        if (pnlPercent.compareTo(BigDecimal.valueOf(-moderateLoss)) >= 0) return "⚠️";
        if (pnlPercent.compareTo(BigDecimal.valueOf(-highLoss)) >= 0) return "🔻";
        return "🆘";
    }

    private String getPnlStatusText(BigDecimal pnlPercent) {
        // Use trading config limits for dynamic thresholds
        double winLimit = tradingConfig.winLimit() != null ? tradingConfig.winLimit() : 5.0;
        double stopLimit = tradingConfig.stopLimit() != null ? Math.abs(tradingConfig.stopLimit()) : 5.0;

        // Calculate thresholds based on config limits
        double highWin = winLimit;                    // Full win limit
        double goodWin = winLimit * 0.4;              // 40% of win limit
        double moderateLoss = stopLimit * 0.4;        // 40% of stop limit
        double highLoss = stopLimit;                  // Full stop limit

        if (pnlPercent.compareTo(BigDecimal.valueOf(highWin)) >= 0) return "🔥 HOT";
        if (pnlPercent.compareTo(BigDecimal.valueOf(goodWin)) >= 0) return "📈 GOOD";
        if (pnlPercent.compareTo(BigDecimal.valueOf(-moderateLoss)) >= 0) return "⚖️ STABLE";
        if (pnlPercent.compareTo(BigDecimal.valueOf(-highLoss)) >= 0) return "⚠️ RISK";
        return "🆘 DANGER";
    }
}