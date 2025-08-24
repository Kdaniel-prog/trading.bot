package kd.trading.bot.config.trading;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TradingConfig.class)
public class TradingPropertiesConfig {
}
