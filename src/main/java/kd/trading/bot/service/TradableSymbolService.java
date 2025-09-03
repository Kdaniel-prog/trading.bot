package kd.trading.bot.service;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.model.SymbolInfo;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TradableSymbolService {
    final BinanceRestClient restClient;

    @Getter
    Set<SymbolInfo> symbols;

    public Set<SymbolInfo> getTradableSymbols() {
        return restClient.getBinanceTradableSymbols().stream()
                .filter(s -> !s.getSymbol().isBlank())
                .filter(s -> "TRADING".equalsIgnoreCase(s.getStatus()))
                .filter(s -> {
                    if (s.getOnboardDate() == null) return false;
                    LocalDateTime onboard = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(s.getOnboardDate()), ZoneOffset.UTC);
                    return onboard.isBefore(LocalDateTime.now(ZoneOffset.UTC).minusMonths(5));
                })
                .collect(Collectors.toSet());
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void refreshTradableSymbols() {
        try {
            symbols = getTradableSymbols();
            log.info("Refreshed tradableSymbols. size: {}", symbols.size());
        } catch (Exception e) {
            log.error("Error refreshing tradableSymbols", e);
        }
    }
}
