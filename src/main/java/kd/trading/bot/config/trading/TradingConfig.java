package kd.trading.bot.config.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;


@ConfigurationProperties(prefix = "trading")
public record TradingConfig(
        Boolean enabled,
        Double stopLimit,
        Double winLimit,
        Double moneyUsdt,
        Integer maxTrade,
        String coinType,
        Integer coinMinMonth,
        Integer leverage,
        Double swipeValue,
        List<String> banSymbol,  // Set helyett List
        String evaluationInterval
) {

}