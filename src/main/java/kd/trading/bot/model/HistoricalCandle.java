package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoricalCandle {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;

    /**
     * Convert to Binance kline format for compatibility
     * [timestamp, open, high, low, close, volume, ...]
     */
    public Object[] toKlineArray() {
        return new Object[]{
                timestamp.toString(),
                String.valueOf(open),
                String.valueOf(high),
                String.valueOf(low),
                String.valueOf(close),
                String.valueOf(volume),
                null, // closeTime
                null, // quoteAssetVolume
                null, // numberOfTrades
                null, // takerBuyBaseAssetVolume
                null, // takerBuyQuoteAssetVolume
                null  // ignore
        };
    }

    /**
     * Price change percentage from open to close
     */
    public double getPriceChangePercent() {
        if (open == 0) return 0.0;
        return ((close - open) / open) * 100.0;
    }

    /**
     * High-Low range percentage
     */
    public double getHighLowRangePercent() {
        if (low == 0) return 0.0;
        return ((high - low) / low) * 100.0;
    }

    /**
     * Check if candle is bullish (close > open)
     */
    public boolean isBullish() {
        return close > open;
    }

    /**
     * Check if candle is bearish (close < open)
     */
    public boolean isBearish() {
        return close < open;
    }

    /**
     * Get candle body size as percentage of full range
     */
    public double getBodySizePercent() {
        double range = high - low;
        if (range == 0) return 0.0;

        double bodySize = Math.abs(close - open);
        return (bodySize / range) * 100.0;
    }

    /**
     * Get upper shadow size
     */
    public double getUpperShadow() {
        return high - Math.max(open, close);
    }

    /**
     * Get lower shadow size
     */
    public double getLowerShadow() {
        return Math.min(open, close) - low;
    }
}