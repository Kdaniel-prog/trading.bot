package kd.trading.bot.model;

import kd.trading.bot.model.backtest.BacktestTrade;
import lombok.*;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class SwingAlgoBacktestResult {
    private List<BacktestTrade> trades;
    private List<SwingAlgoTrainingPoint> analysisPoints;
    private double finalBalance;
    private int totalAnalysisPoints;
}
