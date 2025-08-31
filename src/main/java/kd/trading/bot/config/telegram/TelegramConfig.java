package kd.trading.bot.config.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "telegram")
public record TelegramConfig (
        String username,
        String token,
        List<String>chatIds // több ID is jöhet
) {
}
