package kd.trading.bot.model.ml;

import lombok.*;

import java.util.List;

@Data
@RequiredArgsConstructor
public class MLTrainingRequest {
    private List<String> symbols;
    private String timeframe;
    private String startDate;
    private String endDate;
    private String modelName;
    private boolean runBacktests = true;

}
