package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import kd.trading.bot.enums.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderDto {
    private Long orderId;
    private String symbol;
    private OrderStatus status;        // rendelés státusza (NEW = order bookban, FILLED = teljesült, CANCELED = törölt)
    private String clientOrderId;
    private BigDecimal price;          // megadott ár
    private BigDecimal avgPrice;       // teljesült order átlagára
    private BigDecimal origQty;        // eredeti mennyiség
    private BigDecimal executedQty;    // teljesült mennyiség
    private BigDecimal cumQty;         // összes eddig teljesült mennyiség
    private BigDecimal cumQuote;       // teljesült mennyiség quote-ban
    private TimeInForce timeInForce;   // pl. GTC = Good-Til-Cancelled
    private OrderType type;            // pl. LIMIT, MARKET, STOP_MARKET
    private boolean reduceOnly;        // true = csak csökkentheti a pozíciót
    private boolean closePosition;     // true = teljes pozíciót zár
    private OrderSide side;            // BUY vagy SELL
    private PositionSide positionSide; // BOTH, LONG vagy SHORT
    private BigDecimal stopPrice;      // stop orderhez
    private WorkingType workingType;   // CONTRACT_PRICE vagy MARK_PRICE
    private boolean priceProtect;      // true = ár védelmet használ
    private OrderType origType;        // eredeti order típusa
    private PriceMatch priceMatch;     // ár illesztési mód (NONE, OPPONENT, stb.)
    private SelfTradePreventionMode selfTradePreventionMode; // pl. EXPIRE_MAKER
    private Long goodTillDate;         // ha GTE_GTD, akkor meddig aktív
    private Long updateTime;           // utolsó frissítés időbélyeg
    private LocalDateTime started;
}
