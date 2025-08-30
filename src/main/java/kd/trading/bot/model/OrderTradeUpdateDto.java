package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderTradeUpdateDto {
    public String e; // event type
    public long E;   // event time
    public long T;   // transaction time
    public Order o;  // order data

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Order {
        public String s;  // symbol
        public String c;  // client order ID
        public String S;  // side
        public String o;  // order type
        public String f;  // time in force
        public String q;  // quantity
        public String p;  // price
        public String ap; // average price
        public String rp;
        public String x;  // execution type
        public String X;  // order status
        public long i;    // order ID
        public String l;  // last filled quantity
        public String z;  // cumulative filled quantity
        public String L;  // last filled price
        public String n;  // commission amount
        public String N;  // commission asset
    }
}
