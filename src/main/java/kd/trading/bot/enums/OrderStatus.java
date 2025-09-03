package kd.trading.bot.enums;

public enum OrderStatus {
    NEW,            // order könyvben van, de nem teljesült
    PARTIALLY_FILLED, // részben teljesült
    FILLED,         // teljesen teljesült
    CANCELED,       // törölték
    PENDING_CANCEL, // még cancel feldolgozás alatt
    REJECTED,       // exchange elutasította
    EXPIRED         // lejárt
}