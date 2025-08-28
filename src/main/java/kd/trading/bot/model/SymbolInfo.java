package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;


@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SymbolInfo {
    private String symbol;
    private String status;
    private String baseAsset;
    private String quoteAsset;
    private Long onboardDate; // millisec timestamp

    @JsonProperty("filters")
    private List<Filter> filters;

    /**
     * Kényelmi metódus: adott típusú filter visszaadása
     */
    public Filter getFilter(String filterType) {
        if (filters == null) return null;
        return filters.stream()
                .filter(f -> filterType.equals(f.getFilterType()))
                .findFirst()
                .orElse(null);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Filter {
        private String filterType;

        // LOT_SIZE
        private String minQty;
        private String maxQty;
        private String stepSize;

        // PRICE_FILTER
        private String minPrice;
        private String maxPrice;
        private String tickSize;

        // MIN_NOTIONAL
        private String notional;
    }
}