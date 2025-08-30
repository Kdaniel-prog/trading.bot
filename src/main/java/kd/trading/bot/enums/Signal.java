package kd.trading.bot.enums;

public enum Signal {
    LONG,
    SHORT,
    NO_TRADE;

    public Object opposite(Signal signal) {
        if (signal == Signal.SHORT) return Signal.LONG;
        return Signal.SHORT;
    }
}
