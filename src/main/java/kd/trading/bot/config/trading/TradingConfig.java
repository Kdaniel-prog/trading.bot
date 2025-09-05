package kd.trading.bot.config.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "trading")
public record TradingConfig(
        Double stopLimit,
        Double winLimit,
        String tradeWithPercent,
        Double moneyUsdt,
        Integer maxTrade,
        String coinType,
        Integer coinMinMonth,
        Integer leverage,
        Double swipeValue,
        Set<String> banSymbol
) {
}