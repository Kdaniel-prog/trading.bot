package kd.trading.bot.config.telegram;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TelegramConfig.class)
public class TelegramPropertiesConfig {
}
