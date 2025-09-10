package kd.trading.bot.service;

import kd.trading.bot.model.*;
import kd.trading.bot.service.tradingProcess.TradeAnalyticsService;
import kd.trading.bot.service.tradingProcess.TradeDecisionService;
import kd.trading.bot.util.MessageParser;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradeCheckingService {
    MessageParser parser;
    TradeService tradeService;
    TradeAnalyticsService analyticsService;
    TradeDecisionService decisionService;

    public void calculateTradeInfos(String message) {
        List<BinanceTickerData> tickers = parser.parseMarketMessage(message);
        List<OrderDto> activeOrders = TradeService.getActiveOrderList();

        if (activeOrders.isEmpty()) return;

        // Filter relevant tickers
        List<String> mySymbols = activeOrders.stream()
                .map(OrderDto::getSymbol)
                .toList();

        tickers = tickers.stream()
                .filter(t -> mySymbols.contains(t.getSymbol()))
                .toList();

        if (tickers.isEmpty()) return;

        // Update analytics
        analyticsService.updateTradeAnalytics(activeOrders, tickers);

        // Get trade decisions
        Map<OrderDto, PnlResult> currentAnalytics = analyticsService.getCurrentTradeAnalytics();
        List<TradeDecision> decisions = decisionService.evaluateTradeDecisions(currentAnalytics);

        // Execute decisions
        executeTradeDecisions(decisions);
    }

    private void executeTradeDecisions(List<TradeDecision> decisions) {
        for (TradeDecision decision : decisions) {
            switch (decision.action()) {  // Use action() not getAction()
                case CLOSE -> {
                    log.info(decision.reason());  // Use reason() not getReason()
                    tradeService.closeOrder(decision.order());  // Use order() not getOrder()
                }
                case SWIPE_ALL -> {
                    log.info(decision.reason());
                    analyticsService.getCurrentTradeAnalytics().keySet()
                            .forEach(tradeService::closeOrder);
                }
                case HOLD -> {
                    // Do nothing
                }
            }
        }
    }

    // Delegate to TelegramResponseService
    public String getTradeInfos() {
        // This would be injected
        // return telegramResponseService.getTradeInfos();
        return analyticsService.getCurrentTradeAnalytics().toString(); // Simplified
    }

    public void closeTrade(OrderDto orderDto) {
        tradeService.closeOrder(orderDto);
    }
}
