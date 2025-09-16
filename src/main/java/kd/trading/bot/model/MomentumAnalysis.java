package kd.trading.bot.model;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MomentumAnalysis {
    double rsi;
    boolean macdBullish;
    boolean macdBearish;
    boolean rsiBullishZone;
    boolean rsiBearishZone;
    boolean rsiRising;
    boolean rsiOversold;
    boolean rsiOverbought;
}
