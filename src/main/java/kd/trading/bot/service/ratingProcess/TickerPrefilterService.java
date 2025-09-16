package kd.trading.bot.service.ratingProcess;

import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.model.BadSymbolsDto;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.service.TradeService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequiredArgsConstructor
public class TickerPrefilterService {

    static int MAX_CANDIDATES = 60;

    // Futures trading specifikus konstansok
    static double MIN_QUOTE_VOLUME = 5_000_000; // 5M USDT minimum forgalom
    static double MIN_PRICE_CHANGE = 0.8; // minimum 0.8% ármozgás
    static double MAX_PRICE_CHANGE = 15.0; // maximum 15% ármozgás (túl volatilis)
    static double MIN_PRICE = 0.001; // minimum ár (túl olcsó coinok kiszűrése)
    static long MIN_TRADES = 1000; // minimum trade count (long-ra javítva)

    TradingConfig config;
    LiquidityFilterService liquidityFilterService;

    /**
     * Futures trading specifikus előszűrés:
     * - Megfelelő likviditás és volumen
     * - Optimális volatilitás (nem túl kicsi, nem túl nagy)
     * - Futures tradingre alkalmas árszint
     */
    public List<BinanceTickerData> prefilter(List<BinanceTickerData> tickers, Set<SymbolInfo> tradableSymbols) {
        if (tickers == null || tickers.isEmpty()) {
            return List.of();
        }

        return tickers.stream()
                // csak USDT futures párok
                .filter(ticker -> ticker.getSymbol().endsWith(config.coinType()))

                // bad list kizárása
                .filter(ticker -> !TradeService.BAD_SYMBOL_LIST.contains(new BadSymbolsDto(ticker.getSymbol())))

                // futures trading specifikus szűrők
                .filter(this::hasSufficientLiquidity)
                .filter(this::hasOptimalVolatility)
                .filter(this::hasSuitablePrice)
                .filter(this::hasActiveTrading)

                // csak tradable symbolok
                .filter(ticker -> tradableSymbols.stream()
                        .anyMatch(symbol -> symbol.getSymbol().equals(ticker.getSymbol())))

                // likviditás ellenőrzés (külső service)
                .filter(ticker -> liquidityFilterService.hasGoodLiquidity(ticker.getSymbol()))

                // rendezés: likviditás, volumen, majd volatilitás
                .sorted(createFuturesComparator())
                .limit(MAX_CANDIDATES)
                .toList();
    }

    /**
     * Likviditás ellenőrzés - futures tradinghez megfelelő forgalom
     */
    private boolean hasSufficientLiquidity(BinanceTickerData ticker) {
        double quoteVolume = ticker.getQuoteVolume();
        return quoteVolume > MIN_QUOTE_VOLUME;
    }

    /**
     * Optimális volatilitás - nem túl kicsi, nem túl nagy
     */
    private boolean hasOptimalVolatility(BinanceTickerData ticker) {
        double absChange = Math.abs(ticker.getPriceChangePercent());
        return absChange >= MIN_PRICE_CHANGE && absChange <= MAX_PRICE_CHANGE;
    }

    /**
     * Megfelelő árszint - nem túl olcsó coinok
     */
    private boolean hasSuitablePrice(BinanceTickerData ticker) {
        double price = ticker.getLastPrice();
        return price >= MIN_PRICE;
    }

    /**
     * Aktív kereskedés ellenőrzés - javított mezőnév
     */
    private boolean hasActiveTrading(BinanceTickerData ticker) {
        long tradeCount = ticker.getNumTrades(); // getCount() helyett getNumTrades()
        boolean active = tradeCount >= MIN_TRADES;

        if (!active) {
            log.debug("Symbol {} filtered out: trade count {} too low",
                    ticker.getSymbol(), tradeCount);
        }

        return active;
    }

    /**
     * Futures trading specifikus rendezés - javított mezőnevek
     */
    private Comparator<BinanceTickerData> createFuturesComparator() {
        return Comparator
                // Első: volumen (likviditás proxy)
                .comparingDouble(BinanceTickerData::getQuoteVolume).reversed()
                // Második: trade count (aktivitás) - javított mezőnév
                .thenComparingLong(BinanceTickerData::getNumTrades).reversed()
                // Harmadik: volatilitás (de nem túl magas)
                .thenComparingDouble(this::calculateVolatilityScore).reversed();
    }

    /**
     * Volatilitás score számítás - preferálja a közepes volatilitást
     */
    private double calculateVolatilityScore(BinanceTickerData ticker) {
        double absChange = Math.abs(ticker.getPriceChangePercent());
        // Optimális tartomány: 2-8%
        if (absChange >= 2.0 && absChange <= 8.0) {
            return absChange;
        } else if (absChange < 2.0) {
            return absChange * 0.5; // alacsony volatilitás büntetése
        } else {
            return Math.max(0, 10.0 - absChange); // magas volatilitás büntetése
        }
    }

    /**
     * Debug info - hasznos a teszteléshez
     */
    public void logFilteringStats(List<BinanceTickerData> original, List<BinanceTickerData> filtered) {
        if (!filtered.isEmpty()) {
            BinanceTickerData best = filtered.get(0);
        }
    }
}