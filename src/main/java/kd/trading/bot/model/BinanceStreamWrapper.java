package kd.trading.bot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BinanceStreamWrapper<T> {
    private String stream;
    private T data;
}
