// MomentumAnalysis.java
package kd.trading.bot.model.analysis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MomentumAnalysis {
    private double rsi;
    private boolean macdBullish;
    private boolean macdBearish;
    private boolean rsiBullishZone;
    private boolean rsiBearishZone;
    private boolean rsiOversold;
    private boolean rsiOverbought;
    private boolean rsiRising;
}
