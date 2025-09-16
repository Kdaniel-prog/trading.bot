package kd.trading.bot.service.ratingProcess;

import kd.trading.bot.enums.Signal;
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

        // csökkenő score szerint rendezés
        List<CoinAnalysis> sorted = analyzed.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .toList();

        // TOP 5
        List<CoinAnalysis> top = sorted.stream()
                .filter(t -> t.getSignal() != Signal.SHORT)
                .limit(3)
                .toList();

        // BOTTOM 5, de csak azok, akik nincsenek a topban
        List<String> topSymbols = top.stream()
                .map(CoinAnalysis::getSymbol)
                .toList();

        List<CoinAnalysis> bottom = sorted.stream()
                .filter(t -> t.getSignal() != Signal.LONG)
                .filter(c -> !topSymbols.contains(c.getSymbol())) // szűrés
                .sorted((a, b) -> Double.compare(a.getScore(), b.getScore())) // növekvő score bottomhoz
                .limit(3)
                .toList();

        return new RankedCoins(top, bottom);
    }

    public record RankedCoins(List<CoinAnalysis> top, List<CoinAnalysis> bottom) {}
}
