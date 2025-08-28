package kd.trading.bot.service;

import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.SymbolInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class TickerPrefilterService {

    private static final int MAX_CANDIDATES = 60;

    /**
     * Előszűrés: csak a tradable szimbólumok és top volumen alapján.
     *
     * @param tickers WebSocket üzenetből érkező ticker lista
     * @param tradableSymbols Binance által engedélyezett szimbólumok
     * @return max 60 legnagyobb forgalmú ticker
     */
    public List<BinanceTickerData> prefilter(List<BinanceTickerData> tickers, Set<SymbolInfo> tradableSymbols) {
        if (tickers == null || tickers.isEmpty()) {
            return List.of();
        }

        return tickers.stream()
                .filter(ticker -> tradableSymbols.stream().anyMatch(s-> s.getSymbol().equals(ticker.getSymbol())))
                .sorted(Comparator.comparingDouble(BinanceTickerData::getQuoteVolume).reversed())
                .limit(MAX_CANDIDATES)
                .toList();
    }
}