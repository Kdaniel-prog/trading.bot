package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExchangeInfo {
    private String timezone;
    private long serverTime;
    private List<SymbolInfo> symbols;
}
