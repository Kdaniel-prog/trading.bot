package kd.trading.bot.dto;

import lombok.Data;

@Data
public class TradeEvent {
    private Long t;     // trade id
    private Long E;     // event time
    private String s;   // symbol
    private Double p;   // price
    private Double q;   // quantity
    private Long T;     // trade time
    private Boolean m;  // is the buyer market maker?
}
