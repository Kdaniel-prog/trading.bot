package kd.trading.bot.model;

import lombok.Data;

import java.util.List;

@Data
public class ExchangeInfo {
    private String timezone;
    private long serverTime;
    private List<SymbolInfo> symbols;
}
