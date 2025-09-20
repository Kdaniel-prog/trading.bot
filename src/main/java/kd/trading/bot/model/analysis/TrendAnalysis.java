// TrendAnalysis.java
package kd.trading.bot.model.analysis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TrendAnalysis {
    private String primaryTrend;
    private String shortTermTrend;
    private boolean trendAlignment;
    private double trendStrength;
    private double ema20_4h;
    private double ema50_4h;
    private double ema200_daily;
    private int rule; // Trading rule that triggered

}
