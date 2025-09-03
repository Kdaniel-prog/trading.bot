package kd.trading.bot.telegram;

import kd.trading.bot.config.telegram.TelegramConfig;
import kd.trading.bot.service.AccountProfitService;
import kd.trading.bot.service.TelegramCommandService;
import kd.trading.bot.service.TradeCheckingService;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE ,makeFinal = true)
public class TradingTelegramBot extends TelegramLongPollingBot implements ApplicationListener<ApplicationEvent> {

    AccountProfitService accountProfitService;
    TradeCheckingService tradeCheckingService;
    TelegramCommandService commandService;
    TelegramConfig config;

    @Override
    public String getBotUsername() {
        return config.username();
    }

    @Override
    public String getBotToken() {
        return config.token();
    }

    @Override
    public void onUpdateReceived(Update update) {
        String chatId = null;

        if (update.hasMessage() && update.getMessage().hasText()) {
            chatId = update.getMessage().getChatId().toString();
            handleCommand(chatId, update.getMessage().getText());
        } else if (update.hasCallbackQuery()) {
            chatId = update.getCallbackQuery().getMessage().getChatId().toString();
            handleCommand(chatId, update.getCallbackQuery().getData());
        }

    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof TradeClosedUpdateEvent closedUpdateEvent) {
            sendMessage(closedUpdateEvent.getMessage());
        }
    }

    private void handleCommand(String chatId, String command) {
        if (!config.chatIds().contains(chatId)) {
            // Nem engedélyezett felhasználó
            sendChatId(chatId,
                    "Your chat ID: " + chatId + "\n" +
                            "❌ You are not authorized to use this bot.\n" +
                            "ℹ️ Please ask the admin to add you.");
            return;
        }

        switch (command.toLowerCase()) {
            case "/start":
                sendChatId(chatId, "Hi! 👋 Here is the quick buttons:");
                break;
            case "/chatid":
                sendChatId(chatId, "Your chat ID: " + chatId);
                sendReplyKeyboard(chatId);
                break;
            case "/stats":
                sendMessage(accountProfitService.getProfitStatsReport());
                sendReplyKeyboard(chatId);
                break;
            case "/trades list":
                sendMessage(accountProfitService.getTrades());
                sendReplyKeyboard(chatId);
                break;
            case "/cancel orders":
                sendMessage(commandService.cancelAllOrders());
                sendReplyKeyboard(chatId);
                break;
            case "/check trades":
                sendMessage(tradeCheckingService.getTradeInfos());
                sendReplyKeyboard(chatId);
                break;
            case "/sleep mode":
                sendMessage(commandService.switchMode());
                sendReplyKeyboard(chatId);
                break;
            default:
                sendMessage("Ismeretlen parancs. Használd: /profit, /stats, /trades");
                sendReplyKeyboard(chatId);
        }
    }

    public void sendChatId(String chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(String text) {
        for (String chatId : config.chatIds()) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build();
            try {
                execute(message);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        }
    }

    public void sendReplyKeyboard(String chatId) {
        KeyboardButton statsButton = new KeyboardButton("/stats");
        KeyboardButton tradesButton = new KeyboardButton("/trades list");
        KeyboardButton checkTrades = new KeyboardButton("/check trades");
        KeyboardButton sleepMode = new KeyboardButton("/sleep mode");
        KeyboardButton cancelOrdersButton = new KeyboardButton("/cancel orders");

        KeyboardRow row1 = new KeyboardRow();
        row1.add(statsButton);
        row1.add(checkTrades);
        row1.add(sleepMode);
        row1.add(cancelOrdersButton);

        KeyboardRow row2 = new KeyboardRow();
        row2.add(tradesButton);

        ReplyKeyboardMarkup keyboardMarkup = ReplyKeyboardMarkup.builder()
                .keyboard(List.of(row1, row2))
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .build();

        // Nem kell külön "Keys" szöveg
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("🔘 Choose command:")
                .replyMarkup(keyboardMarkup)
                .build();

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }


}


