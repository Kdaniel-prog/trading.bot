package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.ToString;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@ToString
public class AccountUpdateDto {
    public String e;  // event type
    public long E;    // event time
    public long T;    // transaction time
    public Account a; // account update

    @JsonIgnoreProperties(ignoreUnknown = true)
    @ToString
    public static class Account {
        public List<Balance> B;
        public List<Position> P;
        public String m; // reason (ORDER, FUNDING, etc.)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @ToString
    public static class Balance {
        public String a;  // asset
        public String wb; // wallet balance
        public String cw; // cross wallet balance
        public String bc; // balance change
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @ToString
    public static class Position {
        public String s;   // symbol
        public String pa;  // position amount
        public String ep;  // entry price
        public String cr;  // realized PnL
        public String up;  // unrealized PnL
        public String ps;  // position side
        public String bep; // break even price
    }
}

