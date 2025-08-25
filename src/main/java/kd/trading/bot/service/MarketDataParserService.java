package kd.trading.bot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kd.trading.bot.model.BinanceTickerData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class MarketDataParserService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Binance arr ticker üzenet parse-olása.
     * @param message a WebSocket-ről kapott JSON string
     * @return Ticker adatok listája vagy üres lista ha hiba történt
     */
    public List<BinanceTickerData> parseMessage(String message) {
        try {
            return MAPPER.readValue(
                    message,
                    new TypeReference<List<BinanceTickerData>>() {}
            );
        } catch (Exception e) {
            log.error("Failed to parse market data message", e);
            return List.of(); // üres lista, hogy a pipeline ne törjön el
        }
    }
}
