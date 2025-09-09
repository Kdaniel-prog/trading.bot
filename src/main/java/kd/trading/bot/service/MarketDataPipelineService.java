package kd.trading.bot.service;

import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.CoinAnalysis;
import kd.trading.bot.model.OrderDto;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.service.ratingProcess.algorithm.EmaAlgoService;
import kd.trading.bot.service.ratingProcess.algorithm.SwingAlgoService;
import kd.trading.bot.service.ratingProcess.AthFilterService;
import kd.trading.bot.service.ratingProcess.RankingService;
import kd.trading.bot.service.ratingProcess.TickerPrefilterService;
import kd.trading.bot.util.MessageParser;
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
    final MessageParser parser;
    final TickerPrefilterService prefilterService;
    final AthFilterService athFilterService;
    final RankingService rankingService;
    final TradingConfig tradingConfig;
    final TradeService tradeService;

    final SwingAlgoService algorithmService;
    final EmaAlgoService emaAlgoService;

    @Getter
    volatile RankingService.RankedCoins latestResult = new RankingService.RankedCoins(List.of(), List.of());

    public void processMessage(String message, Set<SymbolInfo> tradableSymbols) {
        try {
            //0. lépés nézzük meg hogy van e már 5 active tradünk
            if(tradeService.getListSize() >= tradingConfig.maxTrade()) return;
            log.info("Check tradeservice 0: trade limit");
            // 1. parse
            List<BinanceTickerData> tickers = parser.parseMarketMessage(message);
            if (tickers.isEmpty()) return;
            log.info("check tradeservice 1: parse");

            // 2. prefilter
            List<BinanceTickerData> prefiltered = prefilterService.prefilter(tickers, tradableSymbols);
            if (prefiltered.isEmpty()) return;
            log.info("check tradeservice 2: prefilter");

            // 3. ath filter
            List<BinanceTickerData> athFiltered = athFilterService.filterBelowAth(prefiltered);
            if (athFiltered.isEmpty()) return;
            log.info("check tradeservice 3: athFilter");

            // 4. analysis
            List<CoinAnalysis> analyzed = athFiltered.stream()
                    .map(ticker -> algorithmService.analyzeCoin(ticker.getSymbol(), ticker.getLastPrice()) )
                    .toList();
            if (analyzed.isEmpty()) return;
            log.info("check tradeservice 4: algorithm");

            List<CoinAnalysis> analyses = analyzed.stream()
                    .filter(c -> !c.getSymbol().isBlank()).toList();

            // 5. ranking
            latestResult = rankingService.rank(analyses);

            // logoljuk a top és bottom coinokat
            log.info("---- Ranking Results ----");
            latestResult.top().forEach(c ->
                    log.info("TOP  -> {} | score={} | signal={} | price={}",
                            c.getSymbol(), c.getScore(), c.getSignal(), c.getLastPrice())
            );
            latestResult.bottom().forEach(c ->
                    log.info("BOTTOM -> {} | score={} | signal={} | price={}",
                            c.getSymbol(), c.getScore(), c.getSignal(), c.getLastPrice())
            );
            log.info("---- End Ranking ----");

            //6. trade
            runTradingCycle(tradableSymbols);

            //7. log
            for(OrderDto order: TradeService.getOrderDtoList()) {
                log.info("**Order list: {}", order.getSymbol());
            }
        } catch (Exception e) {
            log.error("MarketData pipeline failed", e);
        }
    }

    public void runTradingCycle(Set<SymbolInfo> tradableSymbols) {
        if (latestResult == null) return;

        int freeSlots = tradingConfig.maxTrade() - tradeService.getListSize();
        if (freeSlots <= 0) return;

        int topLimit = (int) Math.ceil(freeSlots / 2.0);   // felső lista kapja a kerekítést
        int bottomLimit = freeSlots - topLimit;            // maradék megy az alsónak

        latestResult.top().stream()
            .filter(t -> t.getSignal() != Signal.NO_TRADE)
            .limit(topLimit)
            .forEach(t -> {
                tradableSymbols.stream()
                    .filter(s -> s.getSymbol().equals(t.getSymbol()))
                    .findFirst()
                    .ifPresent(symbol ->
                            tradeService.openTrade(t, symbol)
                    );
            });

        latestResult.bottom().stream()
            .filter(t -> t.getSignal() != Signal.NO_TRADE)
            .limit(bottomLimit)
            .forEach(t -> {
                tradableSymbols.stream()
                    .filter(s -> s.getSymbol().equals(t.getSymbol()))
                    .findFirst()
                    .ifPresent(symbol ->
                        tradeService.openTrade(t, symbol)
                    );
            });
    }

}
