package kd.trading.bot.service.tradingProcess;

import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.PnlResult;
import kd.trading.bot.model.TradeRisk;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradeAnalyticsService {
    final PnlCalculationService pnlCalculationService;

    // In-memory cache - could be Redis in production
    final Map<OrderDto, PnlResult> lastResults = new ConcurrentHashMap<>();

    public Map<OrderDto, PnlResult> getCurrentTradeAnalytics() {
        return Collections.unmodifiableMap(lastResults);
    }

    public void updateTradeAnalytics(List<OrderDto> activeOrders, List<BinanceTickerData> tickers) {
        Map<OrderDto, PnlResult> newResults = pnlCalculationService.calculateBatchPnl(activeOrders, tickers);
        updateLastResult(newResults, activeOrders);
    }

    private void updateLastResult(Map<OrderDto, PnlResult> results, List<OrderDto> activeOrders) {
        // Clear old results and add new ones
        lastResults.clear();
        lastResults.putAll(results);

        // Alternative approach: Update existing and remove stale entries
        // lastResults.putAll(results);
        // lastResults.keySet().removeIf(order -> !activeOrders.contains(order));
    }

    public BigDecimal getTotalPnl() {
        return lastResults.values().stream()
                .map(PnlResult::getPnlAbs)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<TradeRisk> getHighRiskTrades(BigDecimal riskThreshold) {
        return lastResults.entrySet().stream()
                .filter(entry -> entry.getValue().getPnlPercent().abs().compareTo(riskThreshold) > 0)
                .map(entry -> new TradeRisk(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }
}