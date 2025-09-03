package kd.trading.bot.service.ratingProcess;

import kd.trading.bot.api.BinanceRestClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BinanceHistoryService {

    private final BinanceRestClient restClient;

    public double getATH(String symbol) {
        List<List<Object>> klines = restClient.getKlines(symbol, "1d", 1000);
        if (klines.isEmpty()) {
            throw new IllegalStateException("No klines data for symbol: " + symbol);
        }

        return klines.stream()
                .mapToDouble(k -> Double.parseDouble(k.get(2).toString())) // index 2 = high
                .max()
                .orElseThrow(() -> new IllegalStateException("No high values for: " + symbol));
    }
}