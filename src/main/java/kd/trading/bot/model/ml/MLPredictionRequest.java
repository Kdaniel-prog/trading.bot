package kd.trading.bot.model.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MLPredictionRequest {
    private String symbol;
    private Map<String, Object> mlData;
    private List<List<Object>> fourHourKlines;
    private List<List<Object>> dailyKlines;
    private List<List<Object>> hourlyKlines;
    private Map<String, Object> technicalIndicators;
    private long timestamp;
    private boolean fastMode;
}