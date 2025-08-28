package kd.trading.bot.service;

import kd.trading.bot.model.CoinAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class RankingService {

    public RankedCoins rank(List<CoinAnalysis> analyzed) {
        if (analyzed.isEmpty()) {
            return new RankedCoins(List.of(), List.of());
        }

        List<CoinAnalysis> sorted = analyzed.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore())) // csökkenő
                .toList();

        List<CoinAnalysis> top = sorted.stream().limit(5).toList();
        List<CoinAnalysis> bottom = sorted.stream()
                .skip(Math.max(sorted.size() - 5, 0))
                .toList();

        return new RankedCoins(top, bottom);
    }

    public record RankedCoins(List<CoinAnalysis> top, List<CoinAnalysis> bottom) {}
}
