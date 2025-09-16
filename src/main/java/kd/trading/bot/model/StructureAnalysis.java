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
public class StructureAnalysis {
    boolean higherHighs;
    boolean lowerLows;
    boolean higherLows;
    boolean lowerHighs;
    boolean bullishPattern;
    boolean bearishPattern;
    boolean consolidation;
    boolean breakoutPattern;
    boolean reversalPattern;
    String marketStructure; // "UPTREND", "DOWNTREND", "SIDEWAYS"
    double structureStrength;
    boolean structureBreak;

}
