package kd.trading.bot.config.telegram;

import kd.trading.bot.telegram.TradingTelegramBot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Configuration
public class TelegramBotInitializer {

    private final TradingTelegramBot tradingTelegramBot;

    public TelegramBotInitializer(TradingTelegramBot tradingTelegramBot) {
        this.tradingTelegramBot = tradingTelegramBot;
    }

    @Bean
    public TelegramBotsApi telegramBotsApi() throws Exception {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(tradingTelegramBot);
        return botsApi;
    }
}