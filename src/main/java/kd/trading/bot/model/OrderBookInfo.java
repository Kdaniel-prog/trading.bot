package kd.trading.bot.model;

public record OrderBookInfo(String symbol, double spreadPercent, double depthUsdt) {
    public boolean hasGoodLiquidity() {
        return spreadPercent <= 0.1 && depthUsdt >= 50_000;
    }
}
