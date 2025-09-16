package kd.trading.bot.service.ratingProcess;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.model.OrderBookInfo;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequiredArgsConstructor
public class LiquidityFilterService {

    BinanceRestClient binanceRestClient;

    /**
     * Egyszerű liquidity check a prefiltering során
     */
    public boolean hasGoodLiquidity(String symbol) {
        try {
            Optional<OrderBookInfo> orderBookOpt = binanceRestClient.getOrderBookForLiquidity(symbol);

            if (orderBookOpt.isEmpty()) {
                log.debug("No order book data for {}", symbol);
                return false;
            }

            OrderBookInfo orderBook = orderBookOpt.get();
            boolean isGood = orderBook.hasGoodLiquidity();

            if (!isGood) {
                log.debug("Poor liquidity for {}: spread={}%, depth={}",
                        symbol, orderBook.spreadPercent(), orderBook.depthUsdt());
            }

            return isGood;

        } catch (Exception e) {
            log.debug("Liquidity check failed for {}: {}", symbol, e.getMessage());
            return false;
        }
    }
}