package kd.trading.bot.config.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading")
public record TradingConfig(
        Double stopLimit,
        Double winLimit,
        String tradeWithPercent,
        Double moneyUsdt,
        Integer maxTrade,
        String coinType,
        Integer coinMinMonth
) {
}