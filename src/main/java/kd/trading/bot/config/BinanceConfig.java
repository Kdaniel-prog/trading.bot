package kd.trading.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "binance")
public record BinanceConfig(
        String restBaseUrl,
        String wsBaseUrl,
        String apiKey,
        String secretKey
) {
}
