// StructureAnalysis.java
package kd.trading.bot.model.analysis;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StructureAnalysis {
    private boolean higherHighs;
    private boolean lowerLows;
    private boolean higherLows;
    private boolean lowerHighs;
    private boolean bullishPattern;
    private boolean bearishPattern;
    private boolean consolidation;
    private boolean breakoutPattern;
    private boolean reversalPattern;
    private String marketStructure;
    private double structureStrength;
    private boolean structureBreak;
}