package kd.trading.bot.service;

import jakarta.annotation.PostConstruct;
import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.config.trading.TradingConfig;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.enums.TradeStatus;
import kd.trading.bot.model.SymbolInfo;
import kd.trading.bot.model.TradeDto;
import kd.trading.bot.util.TradeServiceHelper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class TradeService {
    BinanceRestClient restClient;
    TradingConfig tradingConfig;
    TradeServiceHelper helper;

    @Getter
    public static final List<TradeDto> activeTrades = new CopyOnWriteArrayList<>();
    public static final Set<String> openTrades = ConcurrentHashMap.newKeySet();

    // --- queue + worker thread ---
    private final BlockingQueue<Runnable> tradeQueue = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void initWorker() {
        worker.submit(this::processTrades);
    }

    private void processTrades() {
        while (true) {
            try {
                Runnable task = tradeQueue.take();
                task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Trade worker interrupted, exiting.");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in trade worker", e);
            }
        }
    }

    // --- Trade nyitása ---
    public void openTrade(Signal signal, BigDecimal lastPrice, SymbolInfo info) {
        tradeQueue.offer(() -> makeTrade(signal, lastPrice, info));
    }

    private void makeTrade(Signal signal, BigDecimal lastPrice, SymbolInfo info) {
        if (activeTrades.size() >= tradingConfig.maxTrade()) {
            log.info("Max trades reached, skipping {}", info.getSymbol());
            return;
        }

        if (checkIfContainsSymbol(info.getSymbol()) ) {
            log.info("Symbol {} is in activeTrades, skipping", info.getSymbol());
            return;
        }

        TradeDto trade = helper.generateTradeDto(signal, lastPrice, info);

        TradeStatus status = restClient.placeOrder(
                info.getSymbol(),
                trade.getQuantity(),
                trade.getEntryPrice(),
                signal
        );

        if (status == TradeStatus.SUCCESS) {
            activeTrades.add(trade);
        } else if (status == TradeStatus.OPEN) {
            openTrades.add(info.getSymbol());
        }
    }

    public void closeOpenTrades() {
        // 1. Nyitott, de még nem teljesült megbízások törlése
        if (!openTrades.isEmpty()) {
            List<String> toRemove = new ArrayList<>();
            for (String symbol : openTrades) {
                log.info("Close open order: {}", symbol);
                boolean success = restClient.cancelOrdersForSymbol(symbol);
                if (success) {
                    toRemove.add(symbol);
                }
            }
            toRemove.forEach(openTrades::remove);
        }

    }

    private Boolean checkIfContainsSymbol(String symbol) {
        return activeTrades.stream()
                .map(t -> t.getSymbol().getSymbol())
                .anyMatch(s -> s.equals(symbol));
    }


}
