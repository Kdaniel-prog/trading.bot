package kd.trading.bot.service;

import kd.trading.bot.api.BinanceRestClient;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TelegramCommandService {
    BinanceRestClient client;
    public static Boolean SLEEP_MODE = false;
    public static Boolean CANCEL_ORDERS = false;

    public String switchMode() {
        SLEEP_MODE = !SLEEP_MODE;
        return SLEEP_MODE ? "SLEEP MODE ON" : "SLEEP MODE OFF";
    }

    public String cancelAllOrders() {
        TradeService.getOrderDtoList().forEach(o-> client.cancelOrdersForSymbol(o.getSymbol()));

        return "All order canceled";
    }
}
