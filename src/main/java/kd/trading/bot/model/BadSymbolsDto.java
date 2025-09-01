package kd.trading.bot.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class BadSymbolsDto {
    private String symbol;
    private LocalDateTime stamp;
}
