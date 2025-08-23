package kd.trading.bot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BinanceConfig.class)
public class BinancePropertiesConfig {
}
