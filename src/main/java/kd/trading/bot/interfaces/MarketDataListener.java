package kd.trading.bot.interfaces;

public interface MarketDataListener {
    void onMarketData(String message);
    void onChangeData(String message);
}
