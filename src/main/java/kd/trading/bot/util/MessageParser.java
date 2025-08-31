package kd.trading.bot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class MessageParser {

    /**
     * Binance arr ticker üzenet parse-olása.
     * @param message a WebSocket-ről kapott JSON string
     * @return Ticker adatok listája vagy üres lista ha hiba történt
     */
    public List<BinanceTickerData> parseMarketMessage(String message) {
        try {
            ObjectMapper MAPPER = new ObjectMapper();
            return MAPPER.readValue(
                    message,
                    new TypeReference<List<BinanceTickerData>>() {}
            );
        } catch (Exception e) {
            log.error("Failed to parse market data message", e);
            return List.of(); // üres lista, hogy a pipeline ne törjön el
        }
    }

    public OrderDto parseOrder(String message) {
        try {
            ObjectMapper MAPPER = new ObjectMapper();
            return MAPPER.readValue(
                    message,
                    new TypeReference<OrderDto>() {}
            );
        } catch (Exception e) {
            log.error("Failed to parse market data message", e);
            return new OrderDto();
        }
    }
}
