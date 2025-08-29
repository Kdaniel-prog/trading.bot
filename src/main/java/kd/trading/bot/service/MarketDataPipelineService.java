package kd.trading.bot.service;

import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.CoinAnalysis;
import kd.trading.bot.model.SymbolInfo;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MarketDataPipelineService {
    final MarketDataParserService parserService;
    final TickerPrefilterService prefilterService;
    final AthFilterService athFilterService;
    final RankingService rankingService;
    final IndicatorService indicatorService;
    final AlgorithmService algorithmService;

    @Getter
    volatile RankingService.RankedCoins latestResult = new RankingService.RankedCoins(List.of(), List.of());

    public void processMessage(String message, Set<SymbolInfo> tradableSymbols) {
        try {
            // 1. parse
            List<BinanceTickerData> tickers = parserService.parseMessage(message);
            if (tickers.isEmpty()) return;

            // 2. prefilter
            List<BinanceTickerData> prefiltered = prefilterService.prefilter(tickers, tradableSymbols);
            if (prefiltered.isEmpty()) return;

            // 3. ath filter
            List<BinanceTickerData> athFiltered = athFilterService.filterBelowAth(prefiltered);
            if (athFiltered.isEmpty()) return;

            // 4. analysis
            List<CoinAnalysis> analyzed = athFiltered.stream()
                    .map(ticker -> algorithmService.analyzeSwingCoin(ticker.getSymbol(), ticker.getLastPrice()) )
                    .toList();
            if (analyzed.isEmpty()) return;

            // 5. ranking
            latestResult = rankingService.rank(analyzed);

            log.info("Coins Rated!");
        } catch (Exception e) {
            log.error("MarketData pipeline failed", e);
        }
    }

}
