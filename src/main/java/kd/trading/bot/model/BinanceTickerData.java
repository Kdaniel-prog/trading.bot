package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import kd.trading.bot.enums.Signal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BinanceTickerData {

    @JsonProperty("e")
    private String eventType;             // Event type
    @JsonProperty("E")
    private long eventTime;               // Event time
    @JsonProperty("s")
    private String symbol;                // Symbol
    @JsonProperty("p")
    private double priceChange;           // Price change
    @JsonProperty("P")
    private double priceChangePercent;    // Price change percent
    @JsonProperty("w")
    private double weightedAvgPrice;      // Weighted average price
    @JsonProperty("c")
    private double lastPrice;             // Last price
    @JsonProperty("Q")
    private double lastQty;               // Last quantity
    @JsonProperty("o")
    private double openPrice;             // Open price
    @JsonProperty("h")
    private double highPrice;             // High price
    @JsonProperty("l")
    private double lowPrice;              // Low price
    @JsonProperty("v")
    private double baseVolume;            // Total traded base asset volume
    @JsonProperty("q")
    private double quoteVolume;           // Total traded quote asset volume
    @JsonProperty("O")
    private long openTime;                // Statistics open time
    @JsonProperty("C")
    private long closeTime;               // Statistics close time
    @JsonProperty("F")
    private long firstTradeId;            // First trade ID
    @JsonProperty("L")
    private long lastTradeId;             // Last trade ID
    @JsonProperty("n")
    private long numTrades;               // Number of trades

    // Algoritmushoz
    private Signal signal;
    private double score;
}
