package kd.trading.bot.config.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading")
public record TradingConfig(
        String stopLimit,
        String winLimit,
        String tradeWithPercent,
        Double moneyUsdt,
        Integer maxTrade
) {
}