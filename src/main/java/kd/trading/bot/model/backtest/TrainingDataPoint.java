package kd.trading.bot.model.backtest;

import com.fasterxml.jackson.annotation.JsonInclude;
import kd.trading.bot.enums.Direction;
import kd.trading.bot.enums.Signal;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

// Utility classes and methods
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrainingDataPoint {
    private LocalDateTime timestamp;
    private String symbol;
    private double price;
    private Direction predictedDirection;
    private Signal predictedSignal;
    private Double confidence;
    private Double score;
    private Map<String, Object> technicalIndicators;
    private Integer tradingRule;
    private String actualOutcome;
    private Double actualPnl;
}