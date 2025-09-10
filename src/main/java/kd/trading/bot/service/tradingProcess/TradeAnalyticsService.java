package kd.trading.bot.service.tradingProcess;

import kd.trading.bot.enums.OrderSide;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.PnlResult;
import kd.trading.bot.model.TradeRisk;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


@Service
public class TradeAnalyticsService {
    private final PnlCalculationService pnlCalculationService;

    // In-memory cache - could be Redis in production
    private Map<OrderDto, PnlResult> lastResults = new ConcurrentHashMap<>();

    public TradeAnalyticsService(PnlCalculationService pnlCalculationService) {
        this.pnlCalculationService = pnlCalculationService;
    }

    public Map<OrderDto, PnlResult> getCurrentTradeAnalytics() {
        return Collections.unmodifiableMap(lastResults);
    }

    public void updateTradeAnalytics(List<OrderDto> activeOrders, List<BinanceTickerData> tickers) {
        Map<OrderDto, PnlResult> newResults = pnlCalculationService.calculateBatchPnl(activeOrders, tickers);
        updateLastResult(newResults, activeOrders);
    }

    private void updateLastResult(Map<OrderDto, PnlResult> results, List<OrderDto> activeOrders) {
        if (!lastResults.isEmpty()) {
            lastResults.putAll(results);
            lastResults.keySet().removeIf(order -> !activeOrders.contains(order));
        } else {
            lastResults = new ConcurrentHashMap<>(results);
        }
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
