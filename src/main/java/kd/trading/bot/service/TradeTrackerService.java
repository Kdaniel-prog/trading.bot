package kd.trading.bot.service;

import kd.trading.bot.model.Signal;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.model.TradeDto;
import kd.trading.bot.model.TradeResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class TradeTrackerService {

    private double startingBalance = 0.0;
    @Getter
    private double currentBalance = 0.0;
    private final List<TradeResult> completedTrades = new ArrayList<>();


    public void setStartingBalance(double balance) {
        this.startingBalance = balance;
        this.currentBalance = balance;
    }

    public void updateBalance(double balance) {
        this.currentBalance = balance;
    }


    public double getProfitPercentTotal() {
        if (startingBalance == 0) return 0;
        return ((currentBalance - startingBalance) / startingBalance) * 100.0;
    }

    public int getTradeCount() {
        return completedTrades.size();
    }

    public List<TradeResult> getCompletedTrades() {
        return List.copyOf(completedTrades);
    }

    public void recordTrade(String symbol, Signal signal, double quantity, double entryPrice, double exitPrice) {
        double pnl = signal.equals(Signal.LONG) ? (exitPrice - entryPrice) : (entryPrice - exitPrice) * quantity;
        double profitPercent = (pnl / (entryPrice * quantity)) * 100.0;

        TradeResult result = TradeResult.builder()
                .symbol(symbol)
                .side(signal)
                .qty(quantity)
                .entryPrice(entryPrice)
                .exitPrice(exitPrice)
                .profit(pnl)
                .profitPercent(profitPercent)
                .build();

        completedTrades.add(result);
        currentBalance += pnl;

        log.info("TRADE CLOSED {} {} qty={} PnL={} USDT ({}%) | New Balance: {} USDT",
                signal, symbol, quantity,
                String.format("%.2f", pnl),
                String.format("%.2f", profitPercent),
                String.format("%.2f", currentBalance));
    }

}

