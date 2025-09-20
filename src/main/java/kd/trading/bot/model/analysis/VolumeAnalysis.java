// VolumeAnalysis.java
package kd.trading.bot.model.analysis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VolumeAnalysis {
    private double currentVolume;
    private double averageVolume20;
    private double volumeRatio;
    private boolean strongVolume;
    private boolean strongBullishVolume;
    private boolean strongBearishVolume;
    private boolean aboveAverageVolume;
    private double volumePercentile;
    private boolean volumeBreakout;
    private boolean volumeDrying;
    private boolean volumeTrendUp;
    private boolean volumeMomentum;
}