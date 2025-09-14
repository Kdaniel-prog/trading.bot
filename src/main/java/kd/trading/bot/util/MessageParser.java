package kd.trading.bot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.enums.OrderSide;
import kd.trading.bot.enums.PositionSide;
import kd.trading.bot.model.BinanceStreamWrapper;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.OrderDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

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
            ObjectMapper objectMapper = new ObjectMapper();
            BinanceStreamWrapper<List<BinanceTickerData>> wrapper =
                    objectMapper.readValue(message, new TypeReference<BinanceStreamWrapper<List<BinanceTickerData>>>() {});

            return wrapper.getData();

        } catch (Exception e) {
            log.error("Failed to parse market data message", e);
            return List.of(); // üres lista, hogy a pipeline ne törjön el
        }
    }

    public OrderDto mapToOrderDto(Map<String, Object> pos) {
        OrderDto dto = new OrderDto();
        try {
            dto.setSymbol((String) pos.get("symbol"));

            // mennyiség (pozitív = LONG, negatív = SHORT)
            BigDecimal qty = new BigDecimal((String) pos.get("positionAmt"));
            dto.setOrigQty(qty.abs()); // eredeti mennyiség
            dto.setExecutedQty(qty.abs()); // már teljesült
            dto.setCumQty(qty.abs());
            dto.setClientOrderId((String) pos.get("clientId"));
            dto.setOrderId((Long) pos.get("orderId"));
            // entry price és current price
            dto.setPrice(new BigDecimal((String) pos.get("entryPrice")));
            dto.setAvgPrice(new BigDecimal((String) pos.get("markPrice")));

            // unrealized PnL → cumQuote mezőbe mentjük (jobb lenne külön, de nincs a DTO-ban)
            dto.setCumQuote(new BigDecimal((String) pos.get("unRealizedProfit")));

            // side
            if (qty.compareTo(BigDecimal.ZERO) > 0) {
                dto.setSide(OrderSide.BUY);
            } else if (qty.compareTo(BigDecimal.ZERO) < 0) {
                dto.setSide(OrderSide.SELL);
            }

            // positionSide
            String posSide = (String) pos.get("positionSide");
            if (posSide != null) {
                dto.setPositionSide(PositionSide.valueOf(posSide));
            } else {
                dto.setPositionSide(PositionSide.BOTH);
            }

            // update time
            Object ts = pos.get("updateTime");
            if (ts != null) {
                dto.setUpdateTime(Long.parseLong(ts.toString()));
            }

        } catch (Exception e) {
            log.error("Hiba a pozíció DTO mapping közben: {}", pos, e);
        }
        return dto;
    }

}
