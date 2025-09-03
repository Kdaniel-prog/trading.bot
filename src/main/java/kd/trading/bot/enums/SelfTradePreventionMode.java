package kd.trading.bot.enums;

public enum SelfTradePreventionMode {
    NONE, // nincs védelem
    EXPIRE_TAKER, // ütközésnél a taker oldali order törlődik
    EXPIRE_MAKER, // ütközésnél a maker oldali order törlődik
    EXPIRE_BOTH   // mindkettő törlődik
}
