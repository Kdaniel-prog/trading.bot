package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BinanceTickerData(
        @JsonProperty("e") String eventType,             // Event type
        @JsonProperty("E") long eventTime,              // Event time
        @JsonProperty("s") String symbol,               // Symbol
        @JsonProperty("p") double priceChange,          // Price change
        @JsonProperty("P") double priceChangePercent,   // Price change percent
        @JsonProperty("w") double weightedAvgPrice,     // Weighted average price
        @JsonProperty("c") double lastPrice,            // Last price
        @JsonProperty("Q") double lastQty,              // Last quantity
        @JsonProperty("o") double openPrice,            // Open price
        @JsonProperty("h") double highPrice,            // High price
        @JsonProperty("l") double lowPrice,             // Low price
        @JsonProperty("v") double baseVolume,           // Total traded base asset volume
        @JsonProperty("q") double quoteVolume,          // Total traded quote asset volume
        @JsonProperty("O") long openTime,               // Statistics open time
        @JsonProperty("C") long closeTime,              // Statistics close time
        @JsonProperty("F") long firstTradeId,           // First trade ID
        @JsonProperty("L") long lastTradeId,            // Last trade ID
        @JsonProperty("n") long numTrades               // Number of trades
) {}
