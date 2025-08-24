package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BinanceStreamMessage(
        @JsonProperty("stream") String stream,
        @JsonProperty("data") BinanceTickerData data
) {}
