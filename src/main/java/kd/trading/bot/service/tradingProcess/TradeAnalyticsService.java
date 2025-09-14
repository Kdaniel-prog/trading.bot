package kd.trading.bot.service.tradingProcess;

import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.PnlResult;
import kd.trading.bot.model.TradeRisk;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradeAnalyticsService {
    PnlCalculationService pnlCalculationService;

    // In-memory cache - could be Redis in production
    Map<OrderDto, PnlResult> lastResults = new ConcurrentHashMap<>();

    public Map<OrderDto, PnlResult> getCurrentTradeAnalytics() {
        log.debug("getCurrentTradeAnalytics called - returning {} trades", lastResults.size());
        lastResults.keySet().forEach(order ->
                log.debug("Trade in cache: {}", order.getSymbol()));
        return Collections.unmodifiableMap(lastResults);
    }

    public void updateTradeAnalytics(List<OrderDto> activeOrders, List<BinanceTickerData> tickers) {
        activeOrders.forEach(order -> log.debug("Active order: {}", order.getSymbol()));

        Map<OrderDto, PnlResult> newResults = pnlCalculationService.calculateBatchPnl(activeOrders, tickers);

        updateLastResult(newResults, activeOrders);
    }

    private void updateLastResult(Map<OrderDto, PnlResult> results, List<OrderDto> activeOrders) {
        // Log before update
        log.debug("Before update: cache has {} entries", lastResults.size());

        // Clear old results and add new ones
        lastResults.clear();
        lastResults.putAll(results);

        log.debug("After update: cache has {} entries", lastResults.size());

        // Verify all active orders are in results
        Set<String> activeSymbols = activeOrders.stream()
                .map(OrderDto::getSymbol)
                .collect(Collectors.toSet());

        Set<String> resultSymbols = lastResults.keySet().stream()
                .map(OrderDto::getSymbol)
                .collect(Collectors.toSet());

        Set<String> missingSymbols = new HashSet<>(activeSymbols);
        missingSymbols.removeAll(resultSymbols);

        if (!missingSymbols.isEmpty()) {
            log.warn("Missing PnL results for symbols: {}", missingSymbols);
        }
    }

    public BigDecimal getTotalPnl() {
        BigDecimal total = lastResults.values().stream()
                .map(PnlResult::getPnlAbs)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.debug("Total PnL calculated: {} from {} trades", total, lastResults.size());
        return total;
    }

    public List<TradeRisk> getHighRiskTrades(BigDecimal riskThreshold) {
        List<TradeRisk> risks = lastResults.entrySet().stream()
                .filter(entry -> entry.getValue().getPnlPercent().abs().compareTo(riskThreshold) > 0)
                .map(entry -> new TradeRisk(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());

        log.debug("Found {} high risk trades (threshold: {}%)", risks.size(), riskThreshold);
        return risks;
    }

    // Add method to force refresh if needed
    public void clearCache() {
        log.info("Clearing trade analytics cache");
        lastResults.clear();
    }

    // Add method to get cache size for debugging
    public int getCacheSize() {
        return lastResults.size();
    }
}