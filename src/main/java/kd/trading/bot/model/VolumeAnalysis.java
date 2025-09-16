package kd.trading.bot.model;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class VolumeAnalysis {

    double currentVolume;
    double averageVolume20;
    double volumeRatio;
    boolean strongVolume;
    boolean strongBullishVolume;
    boolean strongBearishVolume;
    boolean aboveAverageVolume;
    double volumePercentile;
    boolean volumeBreakout;
    boolean volumeDrying;

}
