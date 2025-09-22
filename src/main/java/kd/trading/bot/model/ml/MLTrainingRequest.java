package kd.trading.bot.model.ml;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@RequiredArgsConstructor
public class MLTrainingRequest {
    private List<String> symbols;
    private String timeframe;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String modelName;
    private boolean runBacktests = true;
}
