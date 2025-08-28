package kd.trading.bot.service;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.model.SymbolInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TradableSymbolService {

    private final BinanceRestClient restClient;

    public synchronized Set<SymbolInfo> getTradableSymbols() {
        return restClient.getTradableSymbols().stream()
                .filter(s -> "TRADING".equalsIgnoreCase(s.getStatus()))
                .filter(s -> {
                    if (s.getOnboardDate() == null) return false;
                    LocalDateTime onboard = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(s.getOnboardDate()), ZoneOffset.UTC);
                    return onboard.isBefore(LocalDateTime.now(ZoneOffset.UTC).minusMonths(5));
                })
                .collect(Collectors.toSet());
    }

}
