package kd.trading.bot.service.ratingProcess;

import kd.trading.bot.model.BadSymbolsDto;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.service.TradeService;
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
     * Előszűrés: csak a tradable szimbólumok, nincs a bad listában,
     * és a legjobban mozgó + legnagyobb volumenű coinok.
     */
    public List<BinanceTickerData> prefilter(List<BinanceTickerData> tickers, Set<SymbolInfo> tradableSymbols) {
        if (tickers == null || tickers.isEmpty()) {
            return List.of();
        }

        return tickers.stream()
                // csak engedélyezett symbol
                .filter(ticker -> tradableSymbols.stream()
                        .anyMatch(s -> s.getSymbol().equals(ticker.getSymbol())))
                // ne legyen benne a bad listában
                .filter(ticker -> !TradeService.BAD_SYMBOL_LIST.contains(new BadSymbolsDto(ticker.getSymbol() )))
                // szűrés, hogy tényleg legyen forgalom (pl. min. 1M USDT forgalom)
                .filter(ticker -> ticker.getQuoteVolume() > 3_000_000)
                // szűrés, hogy mozogjon is (pl. abszolút árkülönbség > 0.5%)
                .filter(ticker -> Math.abs(ticker.getPriceChangePercent()) > 0.5)
                // rendezés: először volumen, aztán ármozgás %-ban
                .sorted(Comparator
                        .comparingDouble(BinanceTickerData::getQuoteVolume).reversed()
                        .thenComparingDouble(t -> Math.abs(t.getPriceChangePercent())).reversed()
                )
                .limit(MAX_CANDIDATES)
                .toList();
    }
}