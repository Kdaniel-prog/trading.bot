package kd.trading.bot.service.backtest;

import kd.trading.bot.enums.Direction;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.model.backtest.BacktestResult;
import kd.trading.bot.model.backtest.BacktestRuleAnalysis;
import kd.trading.bot.model.backtest.BacktestTrade;
import kd.trading.bot.service.ratingProcess.algorithm.SwingAlgoService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BacktestService {

    final SwingAlgoService swingAlgoService;
    final FeatherDataLoader featherDataLoader;

    // Intelligens minimum adatszám timeframe alapján
    private static final Map<String, Integer> MIN_DATA_REQUIREMENTS = Map.of(
            "1m", 1440,    // 1 nap
            "5m", 2016,    // 1 hét
            "15m", 1344,   // 2 hét
            "1h", 720,     // 1 hónap
            "4h", 480,     // 2 hónap
            "1d", 180,     // 6 hónap (180 nap elegendő daily timeframe-hez!)
            "1w", 52       // 1 év
    );

    // Configuration from YAML
    @Value("${backtest.initial.balance:10000.0}")
    private double initialBalance;

    @Value("${backtest.fee.rate:0.0004}")
    private double feeRate;

    @Value("${backtest.position.size.percent:0.1}")
    private double positionSizePercent;

    @Value("${backtest.risk.stop.loss.percent:3.0}")
    private double stopLossPercent;

    @Value("${backtest.risk.take.profit.percent:6.0}")
    private double takeProfitPercent;

    @Value("${backtest.risk.max.holding.hours:120}")
    private int maxHoldingHours;

    // Directional trading thresholds
    @Value("${backtest.directional.min.confidence:0.65}")
    private double minDirectionalConfidence;

    @Value("${backtest.directional.min.score:5.0}")
    private double minDirectionalScore;

    @Value("${backtest.directional.hold.exit.confidence:0.8}")
    private double holdExitConfidence;

    private final List<BacktestResult> backtestHistory = new ArrayList<>();

    public BacktestResult runBacktest(String symbol, String timeframe,
                                      LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Starting directional backtest for {} {} from {} to {}",
                symbol, timeframe, startDate, endDate);

        try {
            // 1. Load feather data
            List<HistoricalCandle> candleData = featherDataLoader.loadHistoricalData(
                    symbol, timeframe, startDate, endDate);

            // 2. Intelligens adatszám ellenőrzés
            int requiredMinimum = MIN_DATA_REQUIREMENTS.getOrDefault(timeframe, 300);
            int availableCandles = candleData.size();

            log.info("Data check for {}: {} candles available, {} required",
                    symbol, availableCandles, requiredMinimum);

            if (availableCandles < requiredMinimum) {
                BacktestResult extendedResult = tryWithExtendedDateRange(
                        symbol, timeframe, startDate, endDate, requiredMinimum);

                if (extendedResult != null) {
                    return extendedResult;
                }

                if (availableCandles > 50) {
                    log.warn("Limited data for {}: {} candles (minimum {}), proceeding with reduced accuracy",
                            symbol, availableCandles, requiredMinimum);
                } else {
                    log.error("Critically insufficient data for {}: {} candles", symbol, availableCandles);
                    return createErrorResult(symbol, timeframe,
                            String.format("Insufficient data: %d candles (need %d)",
                                    availableCandles, requiredMinimum));
                }
            }

            // 3. Adatminőség ellenőrzése
            BacktestResult qualityCheck = validateDataQuality(candleData, symbol, timeframe);
            if (!qualityCheck.isSuccess()) {
                return qualityCheck;
            }

            // 4. Multi-timeframe adatok előkészítése
            Map<String, List<HistoricalCandle>> timeframeData = prepareTimeframeDataFixed(candleData, timeframe);

            // 5. Backtest futtatása
            BacktestResult result = simulateDirectionalTrading(symbol, timeframe, timeframeData, startDate, endDate);

            // 6. Eredmény kiegészítése adatminőség információkkal
            enhanceResultWithDataInfo(result, availableCandles, requiredMinimum);

            log.info("Directional backtest completed for {}: Final PnL: {}%, Trades: {}, Data Quality: {}",
                    symbol, result.getTotalReturnPercent(), result.getTotalTrades(),
                    availableCandles >= requiredMinimum ? "GOOD" : "LIMITED");

            return result;

        } catch (Exception e) {
            log.error("Backtest failed for {}: {}", symbol, e.getMessage(), e);
            return createErrorResult(symbol, timeframe, e.getMessage());
        }
    }

    /**
     * MAIN RELAXED BACKTEST METHOD - THIS WAS MISSING
     */
    public BacktestResult runRelaxedBacktest(String symbol, String timeframe,
                                             LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Starting RELAXED directional backtest for {} {} from {} to {}",
                symbol, timeframe, startDate, endDate);

        // Store original parameters
        double originalMinScore = this.minDirectionalScore;
        double originalMinConfidence = this.minDirectionalConfidence;
        double originalHoldExitConfidence = this.holdExitConfidence;

        try {
            // Apply relaxed parameters for training data generation
            this.minDirectionalScore = 1.0;       // Much lower than default 5.0
            this.minDirectionalConfidence = 0.3;  // Much lower than default 0.65
            this.holdExitConfidence = 0.5;        // Lower than default 0.8

            log.info("Applied relaxed parameters: minScore={}, minConfidence={}, holdExit={}",
                    this.minDirectionalScore, this.minDirectionalConfidence, this.holdExitConfidence);

            // Run normal backtest with relaxed parameters
            BacktestResult result = runBacktest(symbol, timeframe, startDate, endDate);

            // Mark the result as using relaxed parameters
            if (result.getMetrics() == null) {
                result.setMetrics(new HashMap<>());
            }
            result.getMetrics().put("relaxed_parameters", true);
            result.getMetrics().put("relaxed_min_score", 1.0);
            result.getMetrics().put("relaxed_min_confidence", 0.3);

            if (result.isSuccess()) {
                log.info("Relaxed backtest completed for {}: {}% return, {} trades (vs 0 with strict params)",
                        symbol, result.getTotalReturnPercent(), result.getTotalTrades());
            } else {
                log.warn("Relaxed backtest failed for {}: {}", symbol, result.getErrorMessage());
            }

            return result;

        } finally {
            // Always restore original parameters
            this.minDirectionalScore = originalMinScore;
            this.minDirectionalConfidence = originalMinConfidence;
            this.holdExitConfidence = originalHoldExitConfidence;

            log.debug("Restored original parameters: minScore={}, minConfidence={}, holdExit={}",
                    this.minDirectionalScore, this.minDirectionalConfidence, this.holdExitConfidence);
        }
    }

    /**
     * JAVÍTOTT multi-timeframe adat előkészítés - helyes resampling logikával
     */
    private Map<String, List<HistoricalCandle>> prepareTimeframeDataFixed(
            List<HistoricalCandle> baseData, String baseTimeframe) {

        Map<String, List<HistoricalCandle>> result = new HashMap<>();

        // Alapértelmezett timeframe hozzáadása
        result.put(baseTimeframe, baseData);

        try {
            log.debug("Preparing timeframe data for base: {}, data size: {}", baseTimeframe, baseData.size());

            switch (baseTimeframe) {
                case "1h" -> {
                    // 1h -> 4h, 1d resampling
                    if (baseData.size() >= 96) { // minimum 4 days
                        result.put("4h", resampleToLowerFrequency(baseData, "1h", "4h", 4));
                    }
                    if (baseData.size() >= 168) { // minimum 1 week
                        result.put("1d", resampleToLowerFrequency(baseData, "1h", "1d", 24));
                    }
                }
                case "4h" -> {
                    // 4h -> 1d resampling
                    if (baseData.size() >= 42) { // minimum 1 week
                        result.put("1d", resampleToLowerFrequency(baseData, "4h", "1d", 6));
                    }
                    // 4h -> 1h (interpolation vagy duplicate - nem ideális)
                    result.put("1h", interpolateToHigherFrequency(baseData, "4h", "1h", 4));
                }
                case "1d" -> {
                    // 1d -> 1w resampling
                    if (baseData.size() >= 14) { // minimum 2 weeks
                        result.put("1w", resampleToLowerFrequency(baseData, "1d", "1w", 7));
                    }
                    // 1d -> 4h, 1h (interpolation - nem pontos de hasznos)
                    result.put("4h", interpolateToHigherFrequency(baseData, "1d", "4h", 6));
                    result.put("1h", interpolateToHigherFrequency(baseData, "1d", "1h", 24));
                }
                default -> {
                    // Fallback - használjuk az alapadatokat minden timeframe-re
                    result.put("1h", baseData);
                    result.put("4h", baseData);
                    result.put("1d", baseData);
                }
            }

            log.debug("Timeframe data prepared: {} timeframes available", result.keySet());

        } catch (Exception e) {
            log.warn("Multi-timeframe preparation failed: {}, using base data only", e.getMessage());
            // Fallback - használjuk csak az alapadatokat
            result.put("1h", baseData);
            result.put("4h", baseData);
            result.put("1d", baseData);
        }

        return result;
    }

    /**
     * VALÓDI resampling alacsonyabb frekvenciára (pl. 1h -> 4h, 4h -> 1d)
     * Proper OHLCV aggregation
     */
    private List<HistoricalCandle> resampleToLowerFrequency(
            List<HistoricalCandle> sourceData,
            String sourceTimeframe,
            String targetTimeframe,
            int ratio) {

        if (sourceData.isEmpty() || ratio <= 1) {
            return sourceData;
        }

        List<HistoricalCandle> resampled = new ArrayList<>();

        try {
            // Csoportosítsuk a gyertyákat a ratio alapján
            for (int i = 0; i < sourceData.size(); i += ratio) {
                int endIndex = Math.min(i + ratio, sourceData.size());
                List<HistoricalCandle> group = sourceData.subList(i, endIndex);

                if (group.isEmpty()) continue;

                // OHLC aggregálás
                HistoricalCandle first = group.get(0);
                HistoricalCandle last = group.get(group.size() - 1);

                Double open = first.getOpen();
                Double close = last.getClose();

                // High/Low megkeresése a csoportban
                Double high = group.stream()
                        .map(HistoricalCandle::getHigh)
                        .filter(Objects::nonNull)
                        .max(Double::compareTo)
                        .orElse(first.getHigh());

                Double low = group.stream()
                        .map(HistoricalCandle::getLow)
                        .filter(Objects::nonNull)
                        .min(Double::compareTo)
                        .orElse(first.getLow());

                // Volume összegzés
                Double volume = group.stream()
                        .map(HistoricalCandle::getVolume)
                        .filter(Objects::nonNull)
                        .mapToDouble(Double::doubleValue)
                        .sum();

                // Új aggregált gyertya létrehozása
                HistoricalCandle aggregated = HistoricalCandle.builder()
                        .timestamp(first.getTimestamp())
                        .open(open)
                        .high(high)
                        .low(low)
                        .close(close)
                        .volume(volume)
                        .build();

                resampled.add(aggregated);
            }

            log.debug("Resampled {} -> {}: {} -> {} candles",
                    sourceTimeframe, targetTimeframe, sourceData.size(), resampled.size());

        } catch (Exception e) {
            log.warn("Resampling failed for {} -> {}: {}", sourceTimeframe, targetTimeframe, e.getMessage());
            return sourceData; // Fallback
        }

        return resampled;
    }

    /**
     * Interpoláció magasabb frekvenciára (nem pontos, de hasznos elemzéshez)
     * Egyszerű duplikálás időbélyeg módosítással
     */
    private List<HistoricalCandle> interpolateToHigherFrequency(
            List<HistoricalCandle> sourceData,
            String sourceTimeframe,
            String targetTimeframe,
            int ratio) {

        if (sourceData.isEmpty()) {
            return sourceData;
        }

        List<HistoricalCandle> interpolated = new ArrayList<>();

        try {
            for (HistoricalCandle candle : sourceData) {
                // Eredeti gyertya hozzáadása
                interpolated.add(candle);

                // További "szintetikus" gyertyák létrehozása
                for (int j = 1; j < ratio; j++) {
                    long intervalMinutes = calculateIntervalMinutes(targetTimeframe);
                    LocalDateTime newTimestamp = candle.getTimestamp().plusMinutes(intervalMinutes * j);

                    // Egyszerű interpoláció - használjuk a close árakat
                    HistoricalCandle synthetic = HistoricalCandle.builder()
                            .timestamp(newTimestamp)
                            .open(candle.getClose())
                            .high(candle.getClose())
                            .low(candle.getClose())
                            .close(candle.getClose())
                            .volume(candle.getVolume() / ratio) // Volume elosztása
                            .build();

                    interpolated.add(synthetic);
                }
            }

            // Időbélyeg szerint rendezés
            interpolated.sort(Comparator.comparing(HistoricalCandle::getTimestamp));

            log.debug("Interpolated {} -> {}: {} -> {} candles",
                    sourceTimeframe, targetTimeframe, sourceData.size(), interpolated.size());

        } catch (Exception e) {
            log.warn("Interpolation failed for {} -> {}: {}", sourceTimeframe, targetTimeframe, e.getMessage());
            return sourceData; // Fallback
        }

        return interpolated;
    }

    /**
     * Timeframe alapú intervallum percben
     */
    private long calculateIntervalMinutes(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> 1;
            case "5m" -> 5;
            case "15m" -> 15;
            case "1h" -> 60;
            case "4h" -> 240;
            case "1d" -> 1440;
            case "1w" -> 10080;
            default -> 60;
        };
    }

    private BacktestResult simulateDirectionalTrading(String symbol, String timeframe,
                                                      Map<String, List<HistoricalCandle>> timeframeData,
                                                      LocalDateTime startDate, LocalDateTime endDate) {

        List<HistoricalCandle> mainData = timeframeData.get(timeframe);
        List<BacktestTrade> trades = new ArrayList<>();

        double currentBalance = initialBalance;
        Position currentPosition = null;

        int totalSignals = 0;
        int holdSignals = 0;
        int longSignals = 0;
        int shortSignals = 0;
        int positionsOpened = 0;

        List<Double> portfolioValues = new ArrayList<>();
        List<Double> returns = new ArrayList<>();
        double maxDrawdown = 0.0;
        double peakValue = initialBalance;

        int startIndex = Math.max(200, 0);

        for (int i = startIndex; i < mainData.size() - 1; i++) {
            HistoricalCandle currentCandle = mainData.get(i);
            double currentPrice = currentCandle.getClose();

            // JAVÍTOTT timeframe data lookup - null check
            int idx4h = findClosestIndex(timeframeData.getOrDefault("4h", mainData), currentCandle.getTimestamp());
            int idx1d = findClosestIndex(timeframeData.getOrDefault("1d", mainData), currentCandle.getTimestamp());
            int idx1h = findClosestIndex(timeframeData.getOrDefault("1h", mainData), currentCandle.getTimestamp());

            List<List<Object>> fourHourKlines = prepareKlinesSlice(timeframeData.getOrDefault("4h", mainData), idx4h, 300);
            List<List<Object>> dailyKlines = prepareKlinesSlice(timeframeData.getOrDefault("1d", mainData), idx1d, 200);
            List<List<Object>> hourlyKlines = prepareKlinesSlice(timeframeData.getOrDefault("1h", mainData), idx1h, 100);

            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                continue;
            }

            CoinAnalysis analysis = swingAlgoService.analyzeHistoricalCoin(
                    symbol, currentPrice, fourHourKlines, dailyKlines, hourlyKlines);

            Direction direction = analysis.getDirection();
            totalSignals++;

            // ADD DEBUG LOGGING HERE for first 10 signals
            if (totalSignals <= 10) {
                log.info("DEBUG Signal #{}: analysis={}, direction={}, score={}, confidence={}",
                        totalSignals,
                        "OK",
                        analysis.getDirection(),
                        analysis.getScore(),
                        analysis.getMlConfidence());
            }

            switch (direction) {
                case HOLD -> holdSignals++;
                case LONG -> longSignals++;
                case SHORT -> shortSignals++;
            }

            // JAVÍTOTT confidence check - null safety
            double safeConfidence = analysis.getMlConfidence();

            if (currentPosition == null) {
                if (direction != Direction.HOLD &&
                        analysis.getScore() >= minDirectionalScore &&
                        safeConfidence >= minDirectionalConfidence) {

                    currentPosition = openDirectionalPosition(symbol, analysis, currentPrice,
                            currentBalance, currentCandle.getTimestamp(), direction);
                    currentBalance -= Math.abs(currentPosition.getPositionSize() * currentPrice * feeRate);
                    positionsOpened++;

                    logDirectionalTrade("OPEN", currentPosition, analysis, currentPrice, direction);
                }
            } else {
                boolean shouldExit = shouldExitDirectionalPosition(currentPosition, analysis,
                        currentPrice, currentCandle, direction);

                if (shouldExit) {
                    BacktestTrade trade = closePosition(currentPosition, currentPrice, currentCandle.getTimestamp());
                    trades.add(trade);

                    currentBalance += trade.getPnl();
                    currentBalance -= Math.abs(trade.getPositionSize() * currentPrice * feeRate);

                    logDirectionalTrade("CLOSE", currentPosition, analysis, currentPrice, direction);
                    currentPosition = null;
                }
            }

            double portfolioValue = currentBalance;
            if (currentPosition != null) {
                double unrealizedPnl = calculateUnrealizedPnl(currentPosition, currentPrice);
                portfolioValue += unrealizedPnl;
            }

            portfolioValues.add(portfolioValue);

            if (portfolioValues.size() > 1) {
                double prevValue = portfolioValues.get(portfolioValues.size() - 2);
                double returnPct = (portfolioValue - prevValue) / prevValue;
                returns.add(returnPct);
            }

            if (portfolioValue > peakValue) {
                peakValue = portfolioValue;
            } else {
                double drawdown = (peakValue - portfolioValue) / peakValue;
                maxDrawdown = Math.max(maxDrawdown, drawdown);
            }
        }

        if (currentPosition != null) {
            HistoricalCandle lastCandle = mainData.get(mainData.size() - 1);
            BacktestTrade trade = closePosition(currentPosition, lastCandle.getClose(), lastCandle.getTimestamp());
            trades.add(trade);
            currentBalance += trade.getPnl();
        }

        return calculateDirectionalBacktestMetrics(symbol, timeframe, trades, portfolioValues,
                returns, maxDrawdown, startDate, endDate,
                currentBalance, totalSignals, holdSignals,
                longSignals, shortSignals, positionsOpened);
    }

    // MISSING METHOD - findClosestIndex
    private int findClosestIndex(List<HistoricalCandle> candles, LocalDateTime timestamp) {
        for (int j = 0; j < candles.size(); j++) {
            if (!candles.get(j).getTimestamp().isBefore(timestamp)) {
                return j;
            }
        }
        return candles.size() - 1;
    }

    private List<List<Object>> prepareKlinesSlice(List<HistoricalCandle> data, int currentIndex, int lookback) {
        int startIdx = Math.max(0, currentIndex - lookback);
        int endIdx = Math.min(currentIndex + 1, data.size());

        return data.subList(startIdx, endIdx).stream()
                .map(candle -> List.<Object>of(
                        candle.getTimestamp().toEpochSecond(ZoneOffset.UTC) * 1000,
                        String.valueOf(candle.getOpen()),
                        String.valueOf(candle.getHigh()),
                        String.valueOf(candle.getLow()),
                        String.valueOf(candle.getClose()),
                        String.valueOf(candle.getVolume())
                ))
                .collect(Collectors.toList());
    }

    private boolean shouldExitDirectionalPosition(Position position, CoinAnalysis analysis,
                                                  double currentPrice, HistoricalCandle candle,
                                                  Direction currentDirection) {

        Direction positionDirection = getPositionDirection(position);

        // JAVÍTOTT confidence check - null safety
        double mlConfidence = analysis.getMlConfidence();

        if (mlConfidence > minDirectionalConfidence) {
            if ((positionDirection == Direction.LONG && currentDirection == Direction.SHORT) ||
                    (positionDirection == Direction.SHORT && currentDirection == Direction.LONG)) {
                return true;
            }

            if (currentDirection == Direction.HOLD && mlConfidence > holdExitConfidence) {
                return true;
            }
        }

        double pnlPercent = calculatePnlPercent(position, currentPrice);

        if (pnlPercent <= -stopLossPercent) {
            return true;
        }

        if (pnlPercent >= takeProfitPercent) {
            return true;
        }

        long hoursInPosition = ChronoUnit.HOURS.between(position.getEntryTime(), candle.getTimestamp());
        if (hoursInPosition >= maxHoldingHours) {
            return true;
        }

        if (analysis.getTechnicalIndicators() != null &&
                analysis.getTechnicalIndicators().getVolatilityPercent() > 12.0) {
            return true;
        }

        return false;
    }

    // JAVÍTOTT log metódus - null safety
    private void logDirectionalTrade(String action, Position position, CoinAnalysis analysis,
                                     double currentPrice, Direction direction) {

        double mlConfidence = analysis.getMlConfidence();

        String message = String.format(
                "%s %s position for %s: Price=%.4f, Direction=%s, Confidence=%.2f, Score=%.1f",
                action,
                getPositionDirection(position),
                position.getSymbol(),
                currentPrice,
                direction,
                mlConfidence,
                analysis.getScore()
        );

        log.info("🔄 " + message);
    }

    // ALL REMAINING HELPER METHODS...

    private BacktestResult tryWithExtendedDateRange(String symbol, String timeframe,
                                                    LocalDateTime originalStart, LocalDateTime originalEnd,
                                                    int requiredMinimum) {
        try {
            long originalDays = ChronoUnit.DAYS.between(originalStart, originalEnd);
            long extendedDays = Math.max(originalDays * 2, requiredMinimum);

            LocalDateTime extendedStart = originalEnd.minusDays(extendedDays);

            log.info("Trying extended date range for {}: {} to {} (was {} to {})",
                    symbol, extendedStart, originalEnd, originalStart, originalEnd);

            List<HistoricalCandle> extendedData = featherDataLoader.loadHistoricalData(
                    symbol, timeframe, extendedStart, originalEnd);

            if (extendedData.size() >= requiredMinimum) {
                log.info("Extended data successful for {}: {} candles", symbol, extendedData.size());

                Map<String, List<HistoricalCandle>> timeframeData = prepareTimeframeDataFixed(extendedData, timeframe);
                BacktestResult result = simulateDirectionalTrading(symbol, timeframe, timeframeData, originalStart, originalEnd);

                result.setDataQuality("EXTENDED");
                result.setActualDataStart(extendedStart);

                return result;
            }

        } catch (Exception e) {
            log.warn("Extended date range failed for {}: {}", symbol, e.getMessage());
        }

        return null;
    }

    private BacktestResult validateDataQuality(List<HistoricalCandle> candles, String symbol, String timeframe) {
        if (candles.isEmpty()) {
            return createErrorResult(symbol, timeframe, "No data available");
        }

        int gaps = 0;
        int maxAllowedGaps = candles.size() / 10;

        for (int i = 1; i < candles.size(); i++) {
            HistoricalCandle prev = candles.get(i-1);
            HistoricalCandle curr = candles.get(i);

            long timeDiff = ChronoUnit.MINUTES.between(prev.getTimestamp(), curr.getTimestamp());
            long expectedDiff = getExpectedTimeframeDiff(timeframe);

            if (timeDiff > expectedDiff * 2) {
                gaps++;
            }
        }

        if (gaps > maxAllowedGaps) {
            log.warn("Poor data quality for {}: {} gaps out of {} candles", symbol, gaps, candles.size());
            return createErrorResult(symbol, timeframe,
                    String.format("Poor data quality: %d gaps", gaps));
        }

        long invalidCandles = candles.stream()
                .filter(c -> Double.isNaN(c.getOpen()) ||
                        Double.isNaN(c.getHigh()) ||
                        Double.isNaN(c.getLow()) ||
                        Double.isNaN(c.getClose()))
                .count();

        if (invalidCandles > candles.size() / 20) {
            return createErrorResult(symbol, timeframe,
                    String.format("Invalid data: %d candles with null OHLC", invalidCandles));
        }

        return BacktestResult.builder().success(true).build();
    }

    private long getExpectedTimeframeDiff(String timeframe) {
        return switch (timeframe.toLowerCase()) {
            case "1m" -> 1;
            case "5m" -> 5;
            case "15m" -> 15;
            case "1h" -> 60;
            case "4h" -> 240;
            case "1d" -> 1440;
            case "1w" -> 10080;
            default -> 60;
        };
    }

    private void enhanceResultWithDataInfo(BacktestResult result, int availableCandles, int requiredMinimum) {
        if (result.getMetrics() == null) {
            result.setMetrics(new HashMap<>());
        }

        result.getMetrics().put("available_candles", availableCandles);
        result.getMetrics().put("required_minimum", requiredMinimum);
        result.getMetrics().put("data_sufficiency_ratio", (double) availableCandles / requiredMinimum);

        if (availableCandles >= requiredMinimum) {
            result.setDataQuality("GOOD");
        } else if (availableCandles >= requiredMinimum * 0.7) {
            result.setDataQuality("ACCEPTABLE");
        } else {
            result.setDataQuality("LIMITED");
        }
    }

    private BacktestResult createErrorResult(String symbol, String timeframe, String errorMessage) {
        return BacktestResult.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    private Position openDirectionalPosition(String symbol, CoinAnalysis analysis, double price,
                                             double balance, LocalDateTime timestamp, Direction direction) {

        double positionSize = (balance * positionSizePercent) / price;
        Signal signal = convertDirectionToSignal(direction);

        return Position.builder()
                .symbol(symbol)
                .side(signal)
                .entryPrice(price)
                .positionSize(positionSize)
                .entryTime(timestamp)
                .tradingRule(analysis.getTradingRule())
                .score(analysis.getScore())
                .direction(direction)
                .build();
    }

    private Direction getPositionDirection(Position position) {
        if (position.getDirection() != null) {
            return position.getDirection();
        }

        return switch (position.getSide()) {
            case LONG -> Direction.LONG;
            case SHORT -> Direction.SHORT;
            default -> Direction.HOLD;
        };
    }

    private Signal convertDirectionToSignal(Direction direction) {
        return switch (direction) {
            case LONG -> Signal.LONG;
            case SHORT -> Signal.SHORT;
            case HOLD -> Signal.NO_TRADE;
        };
    }

    private BacktestTrade closePosition(Position position, double exitPrice, LocalDateTime exitTime) {
        double pnl = calculateUnrealizedPnl(position, exitPrice);
        double pnlPercent = calculatePnlPercent(position, exitPrice);

        return BacktestTrade.builder()
                .symbol(position.getSymbol())
                .side(position.getSide())
                .entryPrice(position.getEntryPrice())
                .exitPrice(exitPrice)
                .positionSize(position.getPositionSize())
                .entryTime(position.getEntryTime())
                .exitTime(exitTime)
                .pnl(pnl)
                .pnlPercent(pnlPercent)
                .tradingRule(position.getTradingRule())
                .score(position.getScore())
                .direction(getPositionDirection(position))
                .build();
    }

    private double calculateUnrealizedPnl(Position position, double currentPrice) {
        if (position.getSide() == Signal.LONG) {
            return position.getPositionSize() * (currentPrice - position.getEntryPrice());
        } else {
            return position.getPositionSize() * (position.getEntryPrice() - currentPrice);
        }
    }

    private double calculatePnlPercent(Position position, double currentPrice) {
        if (position.getSide() == Signal.LONG) {
            return (currentPrice - position.getEntryPrice()) / position.getEntryPrice() * 100;
        } else {
            return (position.getEntryPrice() - currentPrice) / position.getEntryPrice() * 100;
        }
    }

    private BacktestResult calculateDirectionalBacktestMetrics(String symbol, String timeframe,
                                                               List<BacktestTrade> trades,
                                                               List<Double> portfolioValues,
                                                               List<Double> returns,
                                                               double maxDrawdown,
                                                               LocalDateTime startDate, LocalDateTime endDate,
                                                               double finalBalance,
                                                               int totalSignals, int holdSignals,
                                                               int longSignals, int shortSignals,
                                                               int positionsOpened) {

        double totalReturnPercent = (finalBalance - initialBalance) / initialBalance * 100;

        long winningTrades = trades.stream().mapToLong(t -> t.getPnl() > 0 ? 1 : 0).sum();
        long losingTrades = trades.size() - winningTrades;
        double winRate = trades.isEmpty() ? 0.0 : (double) winningTrades / trades.size() * 100;

        double avgWin = trades.stream().filter(t -> t.getPnl() > 0).mapToDouble(BacktestTrade::getPnlPercent).average().orElse(0.0);
        double avgLoss = trades.stream().filter(t -> t.getPnl() < 0).mapToDouble(BacktestTrade::getPnlPercent).average().orElse(0.0);

        double avgReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double returnStd = calculateStandardDeviation(returns);
        double sharpeRatio = returnStd > 0 ? avgReturn / returnStd : 0.0;

        double grossProfit = trades.stream().filter(t -> t.getPnl() > 0).mapToDouble(BacktestTrade::getPnl).sum();
        double grossLoss = Math.abs(trades.stream().filter(t -> t.getPnl() < 0).mapToDouble(BacktestTrade::getPnl).sum());
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : Double.MAX_VALUE;

        double holdRatio = totalSignals > 0 ? (double) holdSignals / totalSignals * 100 : 0.0;
        double longRatio = totalSignals > 0 ? (double) longSignals / totalSignals * 100 : 0.0;
        double shortRatio = totalSignals > 0 ? (double) shortSignals / totalSignals * 100 : 0.0;
        double positionFillRate = totalSignals > holdSignals ? (double) positionsOpened / (totalSignals - holdSignals) * 100 : 0.0;

        BacktestResult result = BacktestResult.builder()
                .symbol(symbol)
                .timeframe(timeframe)
                .startDate(startDate)
                .endDate(endDate)
                .initialBalance(initialBalance)
                .finalBalance(finalBalance)
                .totalReturnPercent(totalReturnPercent)
                .maxDrawdownPercent(maxDrawdown * 100)
                .totalTrades(trades.size())
                .winningTrades((int) winningTrades)
                .losingTrades((int) losingTrades)
                .winRate(winRate)
                .averageWinPercent(avgWin)
                .averageLossPercent(avgLoss)
                .profitFactor(profitFactor)
                .sharpeRatio(sharpeRatio)
                .trades(trades)
                .success(true)
                .totalSignals(totalSignals)
                .holdSignals(holdSignals)
                .longSignals(longSignals)
                .shortSignals(shortSignals)
                .holdRatio(holdRatio)
                .longRatio(longRatio)
                .shortRatio(shortRatio)
                .positionsOpened(positionsOpened)
                .positionFillRate(positionFillRate)
                .build();

        log.info("Directional Backtest Stats for {}:", symbol);
        log.info("  Total Signals: {}, Hold: {} ({}%), Long: {} ({}%), Short: {} ({}%)",
                totalSignals, holdSignals, String.format("%.1f", holdRatio),
                longSignals, String.format("%.1f", longRatio),
                shortSignals, String.format("%.1f", shortRatio));
        log.info("  Positions Opened: {} / {} actionable signals ({}% fill rate)",
                positionsOpened, totalSignals - holdSignals, String.format("%.1f", positionFillRate));

        return result;
    }

    private double calculateStandardDeviation(List<Double> values) {
        if (values.isEmpty()) return 0.0;

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }

    public BacktestResult runBacktest(String symbol, String timeframe, String startDate, String endDate) {
        try {
            LocalDateTime startDateTime = LocalDateTime.parse(startDate);
            LocalDateTime endDateTime = LocalDateTime.parse(endDate);

            BacktestResult result = runBacktest(symbol, timeframe, startDateTime, endDateTime);

            if (result.isSuccess()) {
                backtestHistory.add(result);
                if (backtestHistory.size() > 100) {
                    backtestHistory.remove(0);
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to parse dates for backtest: {}", e.getMessage());
            return BacktestResult.builder()
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .success(false)
                    .errorMessage("Invalid date format: " + e.getMessage())
                    .build();
        }
    }

    public List<BacktestResult> runMultiSymbolBacktest(List<String> symbols, String timeframe,
                                                       LocalDateTime startDate, LocalDateTime endDate) {

        return symbols.parallelStream()
                .map(symbol -> runBacktest(symbol, timeframe, startDate, endDate))
                .filter(BacktestResult::isSuccess)
                .sorted((r1, r2) -> Double.compare(r2.getTotalReturnPercent(), r1.getTotalReturnPercent()))
                .collect(Collectors.toList());
    }

    public Map<Integer, BacktestRuleAnalysis> analyzeByTradingRules(BacktestResult result) {
        Map<Integer, List<BacktestTrade>> tradesByRule = result.getTrades().stream()
                .collect(Collectors.groupingBy(BacktestTrade::getTradingRule));

        Map<Integer, BacktestRuleAnalysis> analysis = new HashMap<>();

        for (Map.Entry<Integer, List<BacktestTrade>> entry : tradesByRule.entrySet()) {
            Integer rule = entry.getKey();
            List<BacktestTrade> ruleTrades = entry.getValue();

            long wins = ruleTrades.stream().mapToLong(t -> t.getPnl() > 0 ? 1 : 0).sum();
            double totalPnl = ruleTrades.stream().mapToDouble(BacktestTrade::getPnl).sum();
            double avgPnlPercent = ruleTrades.stream().mapToDouble(BacktestTrade::getPnlPercent).average().orElse(0.0);
            double winRate = ruleTrades.isEmpty() ? 0.0 : (double) wins / ruleTrades.size() * 100;

            BacktestRuleAnalysis ruleAnalysis = BacktestRuleAnalysis.builder()
                    .tradingRule(rule)
                    .totalTrades(ruleTrades.size())
                    .winningTrades((int) wins)
                    .winRate(winRate)
                    .totalPnl(totalPnl)
                    .averagePnlPercent(avgPnlPercent)
                    .trades(ruleTrades)
                    .build();

            analysis.put(rule, ruleAnalysis);
        }

        return analysis;
    }

    public List<BacktestResult> getAllBacktestResults() {
        return new ArrayList<>(backtestHistory);
    }

    public List<BacktestResult> getBacktestResults(String symbol, String timeframe) {
        return backtestHistory.stream()
                .filter(result -> symbol == null || symbol.equals(result.getSymbol()))
                .filter(result -> timeframe == null || timeframe.equals(result.getTimeframe()))
                .collect(Collectors.toList());
    }

    public void clearBacktestHistory() {
        backtestHistory.clear();
    }

    public int getBacktestHistorySize() {
        return backtestHistory.size();
    }

    /**
     * Alternative relaxed backtest with custom parameters
     */
    public BacktestResult runRelaxedBacktest(String symbol, String timeframe,
                                             LocalDateTime startDate, LocalDateTime endDate,
                                             double customMinScore, double customMinConfidence) {
        log.info("Starting CUSTOM RELAXED backtest for {} with score={}, confidence={}",
                symbol, customMinScore, customMinConfidence);

        // Store original parameters
        double originalMinScore = this.minDirectionalScore;
        double originalMinConfidence = this.minDirectionalConfidence;

        try {
            // Apply custom relaxed parameters
            this.minDirectionalScore = customMinScore;
            this.minDirectionalConfidence = customMinConfidence;

            // Run backtest with custom parameters
            BacktestResult result = runBacktest(symbol, timeframe, startDate, endDate);

            // Mark as custom relaxed
            if (result.getMetrics() == null) {
                result.setMetrics(new HashMap<>());
            }
            result.getMetrics().put("custom_relaxed_parameters", true);
            result.getMetrics().put("custom_min_score", customMinScore);
            result.getMetrics().put("custom_min_confidence", customMinConfidence);

            return result;

        } finally {
            // Restore original parameters
            this.minDirectionalScore = originalMinScore;
            this.minDirectionalConfidence = originalMinConfidence;
        }
    }

    /**
     * Method to test different parameter combinations and find optimal thresholds
     */
    public Map<String, BacktestResult> testParameterCombinations(String symbol, String timeframe,
                                                                 LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Testing parameter combinations for {} {}", symbol, timeframe);

        Map<String, BacktestResult> results = new HashMap<>();

        // Test different combinations
        double[][] paramCombinations = {
                {0.5, 0.2},   // Very relaxed
                {1.0, 0.3},   // Relaxed (default for training)
                {2.0, 0.4},   // Moderate
                {3.0, 0.5},   // Semi-strict
                {5.0, 0.65}   // Original strict
        };

        for (double[] params : paramCombinations) {
            double testScore = params[0];
            double testConfidence = params[1];

            String key = String.format("score_%.1f_conf_%.2f", testScore, testConfidence);

            try {
                BacktestResult result = runRelaxedBacktest(symbol, timeframe, startDate, endDate,
                        testScore, testConfidence);
                results.put(key, result);

                log.info("Test {}: {} trades, {}% return",
                        key, result.getTotalTrades(), String.format("%.2f", result.getTotalReturnPercent()));

            } catch (Exception e) {
                log.warn("Parameter test failed for {}: {}", key, e.getMessage());
            }
        }

        return results;
    }

    /**
     * FIXED: Analyze signal distribution method with proper closing brace
     */
    public void analyzeSignalDistribution(String symbol, String timeframe,
                                          LocalDateTime startDate, LocalDateTime endDate,
                                          int sampleSize) {
        try {
            List<HistoricalCandle> candleData = featherDataLoader.loadHistoricalData(
                    symbol, timeframe, startDate, endDate);

            Map<String, List<HistoricalCandle>> timeframeData = prepareTimeframeDataFixed(candleData, timeframe);
            List<HistoricalCandle> mainData = timeframeData.get(timeframe);

            int startIndex = Math.max(200, 0);
            int step = Math.max(1, (mainData.size() - startIndex) / sampleSize);

            List<Double> scores = new ArrayList<>();
            List<Double> confidences = new ArrayList<>();

            log.info("Analyzing signal distribution for {} (sample size: {})", symbol, sampleSize);

            for (int i = startIndex; i < mainData.size() - 1; i += step) {
                HistoricalCandle currentCandle = mainData.get(i);

                int idx4h = findClosestIndex(timeframeData.getOrDefault("4h", mainData), currentCandle.getTimestamp());
                int idx1d = findClosestIndex(timeframeData.getOrDefault("1d", mainData), currentCandle.getTimestamp());
                int idx1h = findClosestIndex(timeframeData.getOrDefault("1h", mainData), currentCandle.getTimestamp());

                List<List<Object>> fourHourKlines = prepareKlinesSlice(timeframeData.getOrDefault("4h", mainData), idx4h, 300);
                List<List<Object>> dailyKlines = prepareKlinesSlice(timeframeData.getOrDefault("1d", mainData), idx1d, 200);
                List<List<Object>> hourlyKlines = prepareKlinesSlice(timeframeData.getOrDefault("1h", mainData), idx1h, 100);

                if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) continue;

                try {
                    CoinAnalysis analysis = swingAlgoService.analyzeHistoricalCoin(
                            symbol, currentCandle.getClose(), fourHourKlines, dailyKlines, hourlyKlines);

                    if (analysis != null && analysis.getDirection() != Direction.HOLD) {
                        scores.add(analysis.getScore());
                        confidences.add(analysis.getMlConfidence());
                    }
                } catch (Exception e) {
                    // Skip failed analyses
                }
            }

            if (!scores.isEmpty()) {
                scores.sort(Double::compareTo);
                confidences.sort(Double::compareTo);

                double scoreP50 = scores.get(scores.size() / 2);
                double scoreP75 = scores.get(scores.size() * 3 / 4);
                double scoreP90 = scores.get(scores.size() * 9 / 10);

                double confP50 = confidences.get(confidences.size() / 2);
                double confP75 = confidences.get(confidences.size() * 3 / 4);
                double confP90 = confidences.get(confidences.size() * 9 / 10);

                log.info("=== SIGNAL DISTRIBUTION ANALYSIS ===");
                log.info("Sample size: {}", scores.size());
                log.info("Score percentiles - P50: {}, P75: {}, P90: {}", scoreP50, scoreP75, scoreP90);
                log.info("Confidence percentiles - P50: {}, P75: {}, P90: {}", confP50, confP75, confP90);
                log.info("Current thresholds - Score: {}, Confidence: {}", minDirectionalScore, minDirectionalConfidence);
                log.info("Suggested relaxed thresholds - Score: {}, Confidence: {}", scoreP50, confP50);
                log.info("====================================");
            } else {
                log.warn("No valid signals found for analysis");
            }

        } catch (Exception e) {
            log.error("Signal distribution analysis failed: {}", e.getMessage());
        }
    }

}