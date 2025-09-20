// HistoricalCandle.java (if not exists)
package kd.trading.bot.model.backtest;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class HistoricalCandle {
    private LocalDateTime timestamp;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;

    public double getTypicalPrice() {
        return (high + low + close) / 3.0;
    }

    public double getRange() {
        return high - low;
    }

    public double getRangePercent() {
        return (high - low) / close * 100;
    }

    public boolean isBullish() {
        return close > open;
    }

    public boolean isBearish() {
        return close < open;
    }
}
