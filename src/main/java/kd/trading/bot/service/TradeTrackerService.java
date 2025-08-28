package kd.trading.bot.service;

import kd.trading.bot.model.TradeResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class TradeTrackerService {

    private double startingBalance = 0.0;
    private double currentBalance = 0.0;
    private final List<TradeResult> completedTrades = new ArrayList<>();

    public void setStartingBalance(double balance) {
        this.startingBalance = balance;
        this.currentBalance = balance;
    }

    public void updateBalance(double balance) {
        this.currentBalance = balance;
    }

    public void recordTrade(String symbol, String side, double qty, double entry, double exit) {
        double pnl = (side.equals("BUY") ? (exit - entry) : (entry - exit)) * qty;
        double profitPercent = (pnl / (entry * qty)) * 100.0;

        TradeResult result = TradeResult.builder()
                .symbol(symbol)
                .side(side)
                .qty(qty)
                .entryPrice(entry)
                .exitPrice(exit)
                .profit(pnl)
                .profitPercent(profitPercent)
                .build();

        completedTrades.add(result);
        currentBalance += pnl;

        log.info("TRADE CLOSED {} {} qty={} PnL={} USDT ({}%) | New Balance: {} USDT",
                side, symbol, qty,
                String.format("%.2f", pnl),
                String.format("%.2f", profitPercent),
                String.format("%.2f", currentBalance));
    }

    public double getCurrentBalance() {
        return currentBalance;
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
}

