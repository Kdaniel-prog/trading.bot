package kd.trading.bot.listenKey;

/**
 * Singleton Listener Key
 */

public final class BinanceListenerKey {
    public String listenerKey;
    private static volatile BinanceListenerKey instance;

    private BinanceListenerKey(String value) {
        this.listenerKey = value;
    }

    public static BinanceListenerKey getInstance(String value) {
        // The approach taken here is called double-checked locking (DCL). It
        // exists to prevent race condition between multiple threads that may
        // attempt to get singleton instance at the same time, creating separate
        // instances as a result.
        BinanceListenerKey result = instance;
        if (result != null) {
            return result;
        }
        synchronized(BinanceListenerKey.class) {
            if (instance == null) {
                instance = new BinanceListenerKey(value);
            }
            return instance;
        }
    }
}
