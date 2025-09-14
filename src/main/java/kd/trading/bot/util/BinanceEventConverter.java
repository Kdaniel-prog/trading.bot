package kd.trading.bot.util;

import kd.trading.bot.model.AccountUpdateDto;
import kd.trading.bot.model.OrderTradeUpdateDto;
import kd.trading.bot.model.TradeLiteDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.model.UnknownEventDto;
import org.springframework.stereotype.Component;

@Component
public class BinanceEventConverter {

    public Object convert(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            String eventType = root.get("e").asText();

            return switch (eventType) {
                case "ORDER_TRADE_UPDATE" -> mapper.readValue(json, OrderTradeUpdateDto.class);
                case "ACCOUNT_UPDATE" -> mapper.readValue(json, AccountUpdateDto.class);
                case "TRADE_LITE" -> mapper.readValue(json, TradeLiteDto.class);
                default -> new UnknownEventDto();
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Binance event: " + json, e);
        }
    }
}
