package kd.trading.bot.model;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TrendAnalysis {
    String primaryTrend;
    String shortTermTrend;
    double trendStrength;
    boolean trendAlignment;
    double ema20_4h;
    double ema50_4h;
    double ema200_daily;
}
