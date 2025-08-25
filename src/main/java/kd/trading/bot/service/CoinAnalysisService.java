package kd.trading.bot.service;

import kd.trading.bot.model.BinanceTickerData;
import kd.trading.bot.model.CoinAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoinAnalysisService {

    private final AlgorithmService algorithmService;

    private final ExecutorService analysisPool =
            Executors.newFixedThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors()));

    public List<BinanceTickerData> analyze(List<BinanceTickerData> tickers) {
        List<CompletableFuture<BinanceTickerData>> futures = tickers.stream()
                .map(ticker -> CompletableFuture.supplyAsync(() -> {
                            CoinAnalysis analysis = algorithmService.analyzeCoin(ticker.getSymbol());
                            ticker.setSignal(analysis.getSignal());
                            ticker.setScore(analysis.getScore());
                            return ticker;
                        }, analysisPool).orTimeout(4, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            ticker.setScore(Double.NEGATIVE_INFINITY);
                            return ticker;
                        }))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(t -> !Double.isInfinite(t.getScore()))
                .toList();
    }
}

