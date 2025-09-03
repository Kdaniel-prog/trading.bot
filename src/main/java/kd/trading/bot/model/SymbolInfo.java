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

    // Ha van ilyen a response-ban (pl. optionsnál)
    private Integer priceScale;       // ár pontosság
    private Integer quantityScale;    // mennyiség pontosság

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

    /**
     * Ár pontosság számítása
     */
    public int getPriceScaleOrDefault() {
        if (priceScale != null) return priceScale;

        Filter f = getFilter("PRICE_FILTER");
        if (f != null && f.getTickSize() != null) {
            return new java.math.BigDecimal(f.getTickSize())
                    .stripTrailingZeros()
                    .scale();
        }
        return 8; // fallback default
    }

    /**
     * Mennyiség pontosság számítása
     */
    public int getQuantityScaleOrDefault() {
        if (quantityScale != null) return quantityScale;

        Filter f = getFilter("LOT_SIZE");
        if (f != null && f.getStepSize() != null) {
            return new java.math.BigDecimal(f.getStepSize())
                    .stripTrailingZeros()
                    .scale();
        }
        return 8; // fallback default
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