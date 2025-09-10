package kd.trading.bot.model;

import kd.trading.bot.enums.TradeAction;

public record TradeDecision(TradeAction action, OrderDto order, String reason) {
    public boolean shouldExecute() {
        return action != TradeAction.HOLD;
    }
}