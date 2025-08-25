package kd.trading.bot.model;

import lombok.Data;

@Data
public class SymbolInfo {
    private String symbol;
    private String status;
    private String baseAsset;
    private String quoteAsset;
    private long onboardDate; // millisec timestamp
}