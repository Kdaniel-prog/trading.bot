package kd.trading.bot.telegram;

import kd.trading.bot.config.telegram.TelegramConfig;
import kd.trading.bot.service.AccountProfitService;
import kd.trading.bot.service.TelegramCommandService;
import kd.trading.bot.service.TelegramResponseService;
import kd.trading.bot.service.tradingProcess.TradeAnalyticsService;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.jetbrains.annotations.NotNull;
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

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradingTelegramBot extends TelegramLongPollingBot implements ApplicationListener<ApplicationEvent> {
    AccountProfitService accountProfitService;
    TelegramCommandService commandService;
    TelegramResponseService telegramResponseService;
    TradeAnalyticsService analyticsService;
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
    public void onApplicationEvent(@NotNull ApplicationEvent event) {
        if (event instanceof TradeClosedUpdateEvent closedUpdateEvent) {
            sendMessage(closedUpdateEvent.getMessage());
        }
    }

    private void handleCommand(String chatId, String command) {
        if (!config.chatIds().contains(chatId)) {
            sendChatId(chatId,
                    "Your chat ID: " + chatId + "\n" +
                            "❌ You are not authorized to use this bot.\n" +
                            "ℹ️ Please ask the admin to add you.");
            return;
        }

        switch (command.toLowerCase()) {
            case "/start":
                sendChatId(chatId, "Hi! 👋 Welcome to Trading Bot!\n\n" +
                        "🤖 I can help you monitor your trades, check profits, and manage orders.\n" +
                        "Use the buttons below or type commands:");
                sendReplyKeyboard(chatId);
                break;

            case "/chatid":
                sendChatId(chatId, "Your chat ID: " + chatId);
                sendReplyKeyboard(chatId);
                break;

            // === EXISTING COMMANDS ===
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

            case "/sleep mode":
                sendMessage(commandService.switchMode());
                sendReplyKeyboard(chatId);
                break;

            // === NEW ENHANCED COMMANDS ===
            case "/check trades":
            case "📊 active trades":
                sendMessage(telegramResponseService.getTradeInfos());
                sendReplyKeyboard(chatId);
                break;

            case "/summary":
            case "📈 summary":
                sendMessage(telegramResponseService.getTradeSummary());
                sendReplyKeyboard(chatId);
                break;

            case "/top performers":
            case "🏆 top 5":
                sendMessage("🏆 **TOP 5 PERFORMERS**\n" +
                        telegramResponseService.getTopPerformers(5));
                sendReplyKeyboard(chatId);
                break;

            case "/risky trades":
            case "⚠️ risks":
                sendMessage("⚠️ **RISKY TRADES**\n" +
                        telegramResponseService.getRiskyTrades());
                sendReplyKeyboard(chatId);
                break;

            case "/total pnl":
            case "💰 total pnl":
                BigDecimal totalPnl = analyticsService.getTotalPnl();
                String pnlEmoji = totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "📈" : "📉";
                sendMessage(String.format("💰 **TOTAL PNL**\n%s **%.4f USDT**", pnlEmoji, totalPnl));
                sendReplyKeyboard(chatId);
                break;

            case "/quick status":
            case "⚡ quick":
                sendQuickStatus();
                sendReplyKeyboard(chatId);
                break;

            // === HELP COMMAND ===
            case "/help":
            case "❓ help":
                sendMessage(getHelpMessage());
                sendReplyKeyboard(chatId);
                break;

            default:
                sendMessage("❓ Unknown command. Use /help to see available commands or use the buttons below.");
                sendReplyKeyboard(chatId);
        }
    }

    private void sendQuickStatus() {
        var analytics = analyticsService.getCurrentTradeAnalytics();
        BigDecimal totalPnl = analyticsService.getTotalPnl();

        int activeTrades = analytics.size();
        int profitableTrades = (int) analytics.values().stream()
                .filter(pnl -> pnl.getPnlPercent().compareTo(BigDecimal.ZERO) > 0)
                .count();

        String status = totalPnl.compareTo(BigDecimal.ZERO) >= 0 ? "📈 PROFIT" : "📉 LOSS";

        String quickStatus = String.format("""
            ⚡ **QUICK STATUS**
            ━━━━━━━━━━━━━━━━━━━━━━
            📊 Active Trades: **%d**
            🟢 Profitable: **%d** | 🔴 Losing: **%d**
            💰 Total PnL: **%.4f USDT** %s
            ⏱️ Last Update: `%s`
            """,
                activeTrades,
                profitableTrades,
                activeTrades - profitableTrades,
                totalPnl,
                status,
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );

        sendMessage(quickStatus);
    }

    private String getHelpMessage() {
        return """
            ❓ **HELP - Available Commands**
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            **📊 TRADE MONITORING**
            • `/check trades` - Detailed active trades
            • `/summary` - Quick trade summary  
            • `/total pnl` - Total profit/loss
            • `/quick status` - Fast overview
            
            **📈 ANALYTICS**
            • `/top performers` - Best performing trades
            • `/risky trades` - High risk positions
            • `/stats` - Account profit statistics
            
            **🔧 MANAGEMENT** 
            • `/trades list` - All trades list
            • `/cancel orders` - Cancel all orders
            • `/sleep mode` - Toggle sleep mode
            
            **ℹ️ OTHER**
            • `/chatid` - Show your chat ID
            • `/help` - Show this help
            
            💡 **Tip:** Use the keyboard buttons for quick access!
            """;
    }

    public void sendChatId(String chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("Markdown")
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
                    .parseMode("Markdown")
                    .build();
            try {
                execute(message);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        }
    }

    public void sendReplyKeyboard(String chatId) {
        // Row 1: Main trading info
        KeyboardButton activeTradesButton = new KeyboardButton("📊 Active Trades");
        KeyboardButton summaryButton = new KeyboardButton("📈 Summary");
        KeyboardButton quickStatusButton = new KeyboardButton("⚡ Quick");

        // Row 2: Analytics
        KeyboardButton topPerformersButton = new KeyboardButton("🏆 Top 5");
        KeyboardButton riskyTradesButton = new KeyboardButton("⚠️ Risks");
        KeyboardButton totalPnlButton = new KeyboardButton("💰 Total PnL");

        // Row 3: Management
        KeyboardButton statsButton = new KeyboardButton("/stats");
        KeyboardButton sleepModeButton = new KeyboardButton("/sleep mode");
        KeyboardButton cancelOrdersButton = new KeyboardButton("/cancel orders");

        // Row 4: Other
        KeyboardButton tradesListButton = new KeyboardButton("/trades list");
        KeyboardButton helpButton = new KeyboardButton("❓ Help");

        KeyboardRow row1 = new KeyboardRow();
        row1.add(activeTradesButton);
        row1.add(summaryButton);
        row1.add(quickStatusButton);

        KeyboardRow row2 = new KeyboardRow();
        row2.add(topPerformersButton);
        row2.add(riskyTradesButton);
        row2.add(totalPnlButton);

        KeyboardRow row3 = new KeyboardRow();
        row3.add(statsButton);
        row3.add(sleepModeButton);
        row3.add(cancelOrdersButton);

        KeyboardRow row4 = new KeyboardRow();
        row4.add(tradesListButton);
        row4.add(helpButton);

        ReplyKeyboardMarkup keyboardMarkup = ReplyKeyboardMarkup.builder()
                .keyboard(List.of(row1, row2, row3, row4))
                .resizeKeyboard(true)
                .oneTimeKeyboard(false)
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("🎛️ **Control Panel** - Choose a command:")
                .parseMode("Markdown")
                .replyMarkup(keyboardMarkup)
                .build();

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}