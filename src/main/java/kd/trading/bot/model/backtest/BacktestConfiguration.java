package kd.trading.bot.model.backtest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestConfiguration {

    // Balance settings
    @Builder.Default
    private double initialBalance = 10000.0;

    @Builder.Default
    private double positionSizePercent = 0.1; // 10% of balance per trade

    @Builder.Default
    private double feeRate = 0.0004; // 0.04% trading fee

    // Risk management
    @Builder.Default
    private double stopLossPercent = 5.0; // 5% stop loss

    @Builder.Default
    private double takeProfitPercent = 8.0; // 8% take profit

    @Builder.Default
    private int maxHoldingHours = 168; // 7 days max holding

    // Algorithm settings
    @Builder.Default
    private double minimumScore = 0.3; // Minimum algo confidence score

    @Builder.Default
    private boolean enableRuleFiltering = true;

    @Builder.Default
    private int[] allowedTradingRules = {1, 2, 3, 4, 5, 6}; // Which rules to use

    // Multi-timeframe settings
    @Builder.Default
    private boolean useMultiTimeframe = true;

    @Builder.Default
    private String[] requiredTimeframes = {"1h", "4h", "1d"};

    // ML Training settings
    @Builder.Default
    private boolean collectMLData = false; // Whether to collect training data

    @Builder.Default
    private boolean useLiveMLPredictions = false; // Whether to use ML predictions

    @Builder.Default
    private double mlConfidenceThreshold = 0.6; // Minimum ML confidence

    // Advanced settings
    @Builder.Default
    private boolean enableSlippage = true;

    @Builder.Default
    private double slippagePercent = 0.05; // 0.05% slippage

    @Builder.Default
    private boolean enablePartialFills = false;

    // Validation
    public boolean isValid() {
        return initialBalance > 0
                && positionSizePercent > 0 && positionSizePercent <= 1.0
                && feeRate >= 0 && feeRate <= 0.01
                && stopLossPercent > 0 && stopLossPercent <= 50
                && takeProfitPercent > 0 && takeProfitPercent <= 100
                && maxHoldingHours > 0;
    }

    public static BacktestConfiguration getDefault() {
        return BacktestConfiguration.builder().build();
    }

    public static BacktestConfiguration getConservative() {
        return BacktestConfiguration.builder()
                .positionSizePercent(0.05) // 5% per trade
                .stopLossPercent(3.0)
                .takeProfitPercent(6.0)
                .minimumScore(0.5)
                .build();
    }

    public static BacktestConfiguration getAggressive() {
        return BacktestConfiguration.builder()
                .positionSizePercent(0.2) // 20% per trade
                .stopLossPercent(8.0)
                .takeProfitPercent(15.0)
                .minimumScore(0.2)
                .build();
    }
}