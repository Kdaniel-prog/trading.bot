package kd.trading.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TradeLiteDto {
    public String e;
    public long E;
    public long T;
    public String s;
    public String q;
    public String p;
    public boolean m;
    public String c;
    public String S;
    public String L;
    public String l;
    public long t;
    public long i;
}
