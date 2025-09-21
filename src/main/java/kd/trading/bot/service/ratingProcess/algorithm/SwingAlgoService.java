package kd.trading.bot.service.ratingProcess.algorithm;

import kd.trading.bot.api.BinanceRestClient;
import kd.trading.bot.enums.Direction;
import kd.trading.bot.enums.Signal;
import kd.trading.bot.model.*;
import kd.trading.bot.model.ml.MLPredictionResponse;
import kd.trading.bot.service.ml.PythonMLService;
import kd.trading.bot.telegram.eventType.TradeClosedUpdateEvent;
import kd.trading.bot.util.IndicatorUtil;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SwingAlgoService {

    final BinanceRestClient restClient;
    final IndicatorUtil indicatorUtil;
    final ApplicationEventPublisher publisher;
    final PythonMLService pythonMLService;

    // ML Configuration
    @Value("${ml.enabled:true}")
    private boolean useMlPredictions;

    @Value("${ml.confidence.threshold:0.6}")
    private double mlConfidenceThreshold;

    // Trading config
    @Value("${trading.moneyUsdt:10000}")
    private double accountBalance;

    @Value("${trading.leverage:1}")
    private double maxLeverage;

    @Value("${trading.stopLimit:2.0}")
    private double stopLossPercent;

    @Value("${trading.winLimit:3.0}")
    private double takeProfitPercent;

    /**
     * Main analysis method - CSAK KALKULÁL, nem dönt!
     * A trading döntést a Python ML hozza meg
     */
    public CoinAnalysis analyzeCoin(String symbol, double lastPrice) {
        try {
            // Get multi-timeframe data
            List<List<Object>> fourHourKlines = restClient.getKlines(symbol, "4h", 300);
            List<List<Object>> dailyKlines = restClient.getKlines(symbol, "1d", 200);
            List<List<Object>> hourlyKlines = restClient.getKlines(symbol, "1h", 100);
            List<List<Object>> fifteenMinKlines = restClient.getKlines(symbol, "15m", 50);

            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                return createNoTradeAnalysis(symbol, lastPrice, "Insufficient data");
            }

            // Extract price data
            List<Double> closes4h = extractCloses(fourHourKlines);
            List<Double> highs4h = extractHighs(fourHourKlines);
            List<Double> lows4h = extractLows(fourHourKlines);
            List<Double> volumes4h = extractVolumes(fourHourKlines);
            List<Double> closesDaily = extractCloses(dailyKlines);
            List<Double> closesHourly = extractCloses(hourlyKlines);
            List<Double> closes15m = extractCloses(fifteenMinKlines);
            List<Double> highs15m = extractHighs(fifteenMinKlines);
            List<Double> lows15m = extractLows(fifteenMinKlines);

            double currentPrice = closes4h.get(closes4h.size() - 1);

            // === PURE TECHNICAL ANALYSIS - NO TRADING DECISIONS ===
            TechnicalIndicators indicators = calculateAllIndicators(
                    closes4h, highs4h, lows4h, volumes4h,
                    closesDaily, closesHourly, closes15m, highs15m, lows15m, currentPrice
            );

            // Create comprehensive analysis object
            CoinAnalysis analysis = new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
            analysis.setTechnicalIndicators(indicators);

            // === ML DECISION MAKING ===
            if (useMlPredictions) {
                // Prepare data for Python ML
                Map<String, Object> mlData = preparePythonMLData(symbol, currentPrice, indicators);

                // Get ML prediction
                CompletableFuture<MLPredictionResponse> mlFuture = pythonMLService.getPrediction(
                        symbol, fourHourKlines, dailyKlines, hourlyKlines, mlData);

                try {
                    // Wait for ML result (max 5 seconds)
                    MLPredictionResponse mlPrediction = mlFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);


                    // Apply ML decision
                    analysis.setScore(mlPrediction.getConfidence() * 10);
                    analysis.setSignal(convertDirectionToSignal(mlPrediction.getDirection()));
                    analysis.setMlPrediction(mlPrediction);
                    analysis.setMlConfidence(mlPrediction.getConfidence());

                    // Set directional info
                    analysis.setDirection(mlPrediction.getDirection());
                    analysis.setProbabilities(mlPrediction.getProbabilities());

                    log.info("🤖 ML Directional Analysis for {}: Direction={}, Confidence={}, " +
                                    "LONG={}%, SHORT={}%, HOLD={}%",
                            symbol,
                            mlPrediction.getDirection(),
                            mlPrediction.getConfidence(),
                            mlPrediction.getProbabilities().getOrDefault("LONG", 0.0) * 100,
                            mlPrediction.getProbabilities().getOrDefault("SHORT", 0.0) * 100,
                            mlPrediction.getProbabilities().getOrDefault("HOLD", 0.0) * 100);

                    // Enhanced logging for trading decisions
                    if (mlPrediction.getDirection() != Direction.HOLD) {
                        String logMessage = String.format(
                                "ML Trading Signal for %s: %s (%.1f%% confidence) | " +
                                        "RSI: %.1f | MACD: %s | Volume: %.1fx | RR: %.2f | " +
                                        "Probabilities [L:%.1f%%, S:%.1f%%, H:%.1f%%]",
                                symbol,
                                mlPrediction.getDirection(),
                                mlPrediction.getConfidence() * 100,
                                indicators.getRsi(),
                                indicators.isMacdBullish() ? "BULLISH" : "BEARISH",
                                indicators.getVolumeRatio(),
                                indicators.getRiskRewardRatio(),
                                mlPrediction.getProbabilities().getOrDefault("LONG", 0.0) * 100,
                                mlPrediction.getProbabilities().getOrDefault("SHORT", 0.0) * 100,
                                mlPrediction.getProbabilities().getOrDefault("HOLD", 0.0) * 100
                        );
                        logTradingDecision(logMessage);
                    }

                } catch (Exception e) {
                    log.warn("⚠️ ML directional prediction failed for {}, using NO_TRADE: {}",
                            symbol, e.getMessage());
                    analysis = createNoTradeAnalysis(symbol, lastPrice, "ML prediction failed");
                }
            } else {
                log.debug("📊 Technical Analysis for {}: RSI={}, MACD={}, Volume={}",
                        symbol, indicators.getRsi(),
                        indicators.isMacdBullish() ? "BULL" : "BEAR",
                        indicators.getVolumeRatio());
                analysis = createNoTradeAnalysis(symbol, lastPrice, "ML disabled");
            }

            return analysis;

        } catch (Exception e) {
            log.error("Failed to analyze coin {}: {}", symbol, e.getMessage(), e);
            return createNoTradeAnalysis(symbol, lastPrice, "Analysis failed: " + e.getMessage());
        }
    }

    /**
     * Generate directional signals for backtesting/training
     */
    private Direction generateBacktestDirectionalSignal(TechnicalIndicators indicators) {
        double longScore = 0.0;
        double shortScore = 0.0;
        double holdScore = 2.0; // Bias towards HOLD for conservative approach

        // === TREND ANALYSIS ===
        if ("BULLISH".equals(indicators.getPrimaryTrend())) {
            longScore += 2.5;
            if ("BULLISH".equals(indicators.getShortTermTrend())) {
                longScore += 1.5; // Trend alignment
            }
        } else if ("BEARISH".equals(indicators.getPrimaryTrend())) {
            shortScore += 2.5;
            if ("BEARISH".equals(indicators.getShortTermTrend())) {
                shortScore += 1.5; // Trend alignment
            }
        }

        // === MOMENTUM ANALYSIS ===
        double rsi = indicators.getRsi();

        // RSI oversold/overbought conditions
        if (rsi < 35 && rsi > 25) {
            longScore += 2.0; // Potential bounce
        } else if (rsi > 65 && rsi < 75) {
            shortScore += 2.0; // Potential reversal
        }

        // RSI trend
        if (indicators.isRsiRising() && rsi > 40) {
            longScore += 1.0;
        } else if (!indicators.isRsiRising() && rsi < 60) {
            shortScore += 1.0;
        }

        // MACD signals
        if (indicators.isMacdBullish() && indicators.getMacdHistogram() > 0) {
            longScore += 1.5;
        } else if (indicators.isMacdBearish() && indicators.getMacdHistogram() < 0) {
            shortScore += 1.5;
        }

        // === VOLUME CONFIRMATION ===
        if (indicators.getVolumeRatio() > 1.4) {
            // Strong volume supports both directions
            longScore += 1.0;
            shortScore += 1.0;
        } else if (indicators.getVolumeRatio() < 0.8) {
            // Low volume - prefer HOLD
            holdScore += 1.0;
        }

        // === MARKET STRUCTURE ===
        if (indicators.isBullishStructure()) {
            longScore += 1.5;
        } else if (indicators.isBearishStructure()) {
            shortScore += 1.5;
        } else if (indicators.isConsolidation()) {
            holdScore += 1.5;
        }

        // === RISK/REWARD ANALYSIS ===
        if (indicators.getRiskRewardRatio() < 1.2) {
            holdScore += 1.0; // Poor risk/reward
        } else if (indicators.getRiskRewardRatio() > 2.0) {
            // Good risk/reward supports current trend
            if (longScore > shortScore) {
                longScore += 1.0;
            } else if (shortScore > longScore) {
                shortScore += 1.0;
            }
        }

        // === VOLATILITY FILTER ===
        if (indicators.getVolatilityPercent() > 8.0) {
            holdScore += 0.5; // High volatility - be cautious
        }

        // === DECISION LOGIC ===
        double maxScore = Math.max(Math.max(longScore, shortScore), holdScore);
        double threshold = 3.5; // Minimum score for action

        // Require clear winner with sufficient confidence
        if (maxScore < threshold) {
            return Direction.HOLD;
        }

        if (longScore == maxScore && longScore > shortScore + 0.5) {
            return Direction.LONG;
        } else if (shortScore == maxScore && shortScore > longScore + 0.5) {
            return Direction.SHORT;
        } else {
            return Direction.HOLD; // Too close to call
        }
    }

    /**
     * Generate synthetic probabilities for training data
     */
    private Map<String, Double> generateSyntheticProbabilities(Direction direction, double score) {
        Map<String, Double> probabilities = new HashMap<>();

        // Convert score (0-10) to confidence (0.5-0.95)
        double confidence = Math.min(0.95, 0.5 + (score / 10.0) * 0.45);

        if (direction == Direction.LONG) {
            probabilities.put("LONG", confidence);
            probabilities.put("SHORT", (1.0 - confidence) * 0.3);
            probabilities.put("HOLD", (1.0 - confidence) * 0.7);
        } else if (direction == Direction.SHORT) {
            probabilities.put("SHORT", confidence);
            probabilities.put("LONG", (1.0 - confidence) * 0.3);
            probabilities.put("HOLD", (1.0 - confidence) * 0.7);
        } else {
            probabilities.put("HOLD", 0.7);
            probabilities.put("LONG", 0.15);
            probabilities.put("SHORT", 0.15);
        }

        return probabilities;
    }

    /**
     * Calculate score for directional signal
     */
    private double calculateDirectionalSignalScore(TechnicalIndicators indicators, Direction direction) {
        if (direction == Direction.HOLD) {
            return 0.0;
        }

        double score = 5.0; // Base score

        if (direction == Direction.LONG) {
            // Long-specific scoring
            if ("BULLISH".equals(indicators.getPrimaryTrend())) score += 2.0;
            if ("BULLISH".equals(indicators.getShortTermTrend())) score += 1.0;
            if (indicators.isTrendAlignment()) score += 1.0;

            if (indicators.getRsi() > 30 && indicators.getRsi() < 70) score += 1.0;
            if (indicators.isMacdBullish()) score += 1.5;
            if (indicators.isRsiRising()) score += 0.5;

            if (indicators.getVolumeRatio() > 1.3) score += 1.0;
            if (indicators.isVolumeBreakout()) score += 0.5;

            if (indicators.getRiskRewardRatio() > 2.0) score += 1.0;
            if (indicators.getVolatilityPercent() < 8.0) score += 0.5;

        } else if (direction == Direction.SHORT) {
            // Short-specific scoring (mirror logic)
            if ("BEARISH".equals(indicators.getPrimaryTrend())) score += 2.0;
            if ("BEARISH".equals(indicators.getShortTermTrend())) score += 1.0;
            if (indicators.isTrendAlignment()) score += 1.0;

            if (indicators.getRsi() > 30 && indicators.getRsi() < 70) score += 1.0;
            if (indicators.isMacdBearish()) score += 1.5;
            if (!indicators.isRsiRising()) score += 0.5;

            if (indicators.getVolumeRatio() > 1.3) score += 1.0;
            if (indicators.isVolumeBreakout()) score += 0.5;

            if (indicators.getRiskRewardRatio() > 2.0) score += 1.0;
            if (indicators.getVolatilityPercent() < 8.0) score += 0.5;
        }

        // Cap between 0-10
        return Math.max(0.0, Math.min(10.0, score));
    }

    /**
     * Calculate ALL technical indicators without making trading decisions
     */
    private TechnicalIndicators calculateAllIndicators(List<Double> closes4h, List<Double> highs4h,
                                                       List<Double> lows4h, List<Double> volumes4h,
                                                       List<Double> closesDaily, List<Double> closesHourly,
                                                       List<Double> closes15m, List<Double> highs15m,
                                                       List<Double> lows15m, double currentPrice) {

        TechnicalIndicators indicators = new TechnicalIndicators();
        indicators.setCurrentPrice(currentPrice);
        // === TREND INDICATORS ===
        indicators.setEma20_4h(indicatorUtil.EMA(closes4h, 20));
        indicators.setEma50_4h(indicatorUtil.EMA(closes4h, 50));
        indicators.setEma200_daily(indicatorUtil.EMA(closesDaily, 200));
        indicators.setEma10_1h(indicatorUtil.EMA(closesHourly, 10));
        indicators.setEma20_1h(indicatorUtil.EMA(closesHourly, 20));

        // Trend classification
        indicators.setPrimaryTrend(calculatePrimaryTrend(currentPrice, indicators.getEma200_daily(),
                indicators.getEma20_4h(), indicators.getEma50_4h()));
        indicators.setShortTermTrend(calculateShortTermTrend(currentPrice, indicators.getEma10_1h(), indicators.getEma20_1h()));
        indicators.setTrendAlignment(indicators.getPrimaryTrend().equals(indicators.getShortTermTrend())
                && !indicators.getPrimaryTrend().equals("NEUTRAL"));
        indicators.setTrendStrength(calculateTrendStrength(currentPrice, indicators.getEma200_daily(), indicators.getPrimaryTrend()));

        // === MOMENTUM INDICATORS ===
        indicators.setRsi(indicatorUtil.RSI(closes4h, 14));

        double[] macd = indicatorUtil.MACD(closes4h, 12, 26, 9);
        indicators.setMacdLine(macd[0]);
        indicators.setMacdSignal(macd[1]);
        indicators.setMacdHistogram(macd[2]);
        indicators.setMacdBullish(macd[0] > macd[1] && macd[2] > 0);
        indicators.setMacdBearish(macd[0] < macd[1] && macd[2] < 0);

        indicators.setRsiBullishZone(indicators.getRsi() > 30 && indicators.getRsi() < 75);
        indicators.setRsiBearishZone(indicators.getRsi() > 25 && indicators.getRsi() < 70);
        indicators.setRsiOversold(indicators.getRsi() < 30);
        indicators.setRsiOverbought(indicators.getRsi() > 70);

        // RSI trend
        List<Double> recentCloses = closes4h.subList(closes4h.size() - 5, closes4h.size());
        double rsi5PeriodsAgo = indicatorUtil.RSI(recentCloses.subList(0, 4), 14);
        indicators.setRsiRising(indicators.getRsi() > rsi5PeriodsAgo);

        // === VOLUME INDICATORS ===
        double currentVolume = volumes4h.get(volumes4h.size() - 1);
        double avgVolume20 = volumes4h.subList(volumes4h.size() - 20, volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgVolume50 = volumes4h.subList(Math.max(0, volumes4h.size() - 50), volumes4h.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        indicators.setCurrentVolume(currentVolume);
        indicators.setAverageVolume20(avgVolume20);
        indicators.setAverageVolume50(avgVolume50);
        indicators.setVolumeRatio(avgVolume20 > 0 ? currentVolume / avgVolume20 : 0.0);
        indicators.setStrongVolume(indicators.getVolumeRatio() > 1.3);
        indicators.setVolumeBreakout(calculateVolumePercentile(volumes4h, currentVolume) > 75);

        List<Double> last5Volumes = volumes4h.subList(volumes4h.size() - 5, volumes4h.size());
        indicators.setVolumeTrendUp(last5Volumes.get(4) > last5Volumes.get(0));

        // === VOLATILITY & RISK INDICATORS ===
        double atr = indicatorUtil.calculateATR(highs4h, lows4h, closes4h, 14);
        double atr15m = indicatorUtil.calculateATR(highs15m, lows15m, closes15m, 14);

        indicators.setAtr(atr);
        indicators.setVolatilityPercent((atr / currentPrice) * 100);
        indicators.setShortTermVolatility((atr15m / currentPrice) * 100);

        // Support/Resistance
        double[] srLevels = indicatorUtil.calculateSupportResistance(highs4h, lows4h, closes4h);
        indicators.setNearestSupport(srLevels[0]);
        indicators.setNearestResistance(srLevels[1]);
        indicators.setDistanceFromSupport((currentPrice - srLevels[0]) / currentPrice * 100);
        indicators.setDistanceFromResistance((srLevels[1] - currentPrice) / currentPrice * 100);

        // Risk/Reward calculation
        if (indicators.getDistanceFromSupport() > 0.8) {
            indicators.setRiskRewardRatio(indicators.getDistanceFromResistance() / indicators.getDistanceFromSupport());
        } else {
            indicators.setRiskRewardRatio(0.0);
        }

        // === MARKET STRUCTURE ===
        indicators.setHigherHighs(indicatorUtil.isHigherHighsPattern(highs4h, 8));
        indicators.setLowerLows(indicatorUtil.isLowerLowsPattern(lows4h, 8));
        indicators.setHigherLows(indicatorUtil.isHigherLowsPattern(lows4h, 8));
        indicators.setLowerHighs(indicatorUtil.isLowerHighsPattern(highs4h, 8));

        indicators.setBullishStructure(indicators.isHigherHighs() && indicators.isHigherLows());
        indicators.setBearishStructure(indicators.isLowerLows() && indicators.isLowerHighs());
        indicators.setConsolidation(!indicators.isHigherHighs() && !indicators.isLowerLows()
                && !indicators.isHigherLows() && !indicators.isLowerHighs());

        return indicators;
    }

    /**
     * Prepare NUMERICAL data for Python ML service
     * All features converted to numbers for better ML training
     */
    private Map<String, Object> preparePythonMLData(String symbol, double currentPrice, TechnicalIndicators indicators) {
        Map<String, Object> mlData = new HashMap<>();

        mlData.put("symbol", symbol);
        mlData.put("currentPrice", currentPrice);
        mlData.put("timestamp", System.currentTimeMillis());

        // === TREND FEATURES (numerical) ===
        mlData.put("ema20_4h", indicators.getEma20_4h());
        mlData.put("ema50_4h", indicators.getEma50_4h());
        mlData.put("ema200_daily", indicators.getEma200_daily());
        mlData.put("ema10_1h", indicators.getEma10_1h());
        mlData.put("ema20_1h", indicators.getEma20_1h());

        // Price relative to EMAs (numerical ratios)
        mlData.put("price_vs_ema20_4h", currentPrice / indicators.getEma20_4h());
        mlData.put("price_vs_ema50_4h", currentPrice / indicators.getEma50_4h());
        mlData.put("price_vs_ema200_daily", currentPrice / indicators.getEma200_daily());
        mlData.put("ema20_vs_ema50_4h", indicators.getEma20_4h() / indicators.getEma50_4h());
        mlData.put("ema10_vs_ema20_1h", indicators.getEma10_1h() / indicators.getEma20_1h());

        // Trend scores (numerical: -1 = bearish, 0 = neutral, +1 = bullish)
        mlData.put("primaryTrendScore", convertTrendToScore(indicators.getPrimaryTrend()));
        mlData.put("shortTermTrendScore", convertTrendToScore(indicators.getShortTermTrend()));
        mlData.put("trendAlignment", indicators.isTrendAlignment() ? 1.0 : 0.0);
        mlData.put("trendStrength", indicators.getTrendStrength());

        // === MOMENTUM FEATURES (all numerical) ===
        mlData.put("rsi", indicators.getRsi());
        mlData.put("rsiNormalized", (indicators.getRsi() - 50.0) / 50.0); // -1 to +1
        mlData.put("macdLine", indicators.getMacdLine());
        mlData.put("macdSignal", indicators.getMacdSignal());
        mlData.put("macdHistogram", indicators.getMacdHistogram());
        mlData.put("macdDivergence", indicators.getMacdLine() - indicators.getMacdSignal());

        // RSI zones as scores
        mlData.put("rsiOverboughtScore", indicators.getRsi() > 70 ? (indicators.getRsi() - 70) / 30 : 0.0);
        mlData.put("rsiOversoldScore", indicators.getRsi() < 30 ? (30 - indicators.getRsi()) / 30 : 0.0);
        mlData.put("rsiMomentumScore", calculateRsiMomentumScore(indicators));

        // === VOLUME FEATURES (all numerical) ===
        mlData.put("currentVolume", indicators.getCurrentVolume());
        mlData.put("averageVolume20", indicators.getAverageVolume20());
        mlData.put("volumeRatio", indicators.getVolumeRatio());
        mlData.put("volumeRatioLog", Math.log(Math.max(0.01, indicators.getVolumeRatio()))); // Log scale
        mlData.put("volumeStrengthScore", calculateVolumeStrengthScore(indicators));
        mlData.put("volumeTrendScore", indicators.isVolumeTrendUp() ? 1.0 : -1.0);

        // === VOLATILITY & RISK FEATURES (all numerical) ===
        mlData.put("atr", indicators.getAtr());
        mlData.put("atrPercent", indicators.getVolatilityPercent());
        mlData.put("shortTermVolatility", indicators.getShortTermVolatility());
        mlData.put("volatilityRatio", indicators.getShortTermVolatility() / Math.max(0.1, indicators.getVolatilityPercent()));

        // Support/Resistance as ratios
        mlData.put("supportRatio", indicators.getNearestSupport() / currentPrice);
        mlData.put("resistanceRatio", indicators.getNearestResistance() / currentPrice);
        mlData.put("distanceFromSupport", indicators.getDistanceFromSupport());
        mlData.put("distanceFromResistance", indicators.getDistanceFromResistance());
        mlData.put("riskRewardRatio", indicators.getRiskRewardRatio());
        mlData.put("riskRewardScore", Math.min(5.0, indicators.getRiskRewardRatio())); // Cap at 5

        // === MARKET STRUCTURE FEATURES (numerical) ===
        mlData.put("structureBullishScore", calculateStructureBullishScore(indicators));
        mlData.put("structureBearishScore", calculateStructureBearishScore(indicators));
        mlData.put("consolidationScore", indicators.isConsolidation() ? 1.0 : 0.0);

        // === COMPOSITE SCORES ===
        mlData.put("trendMomentumScore", calculateTrendMomentumScore(indicators));
        mlData.put("volumePriceScore", calculateVolumePriceScore(indicators));
        mlData.put("riskAdjustedScore", calculateRiskAdjustedScore(indicators));

        return mlData;
    }

    // === HELPER METHODS FOR NUMERICAL CONVERSION ===

    private double convertTrendToScore(String trend) {
        return switch (trend) {
            case "BULLISH" -> 1.0;
            case "BEARISH" -> -1.0;
            default -> 0.0;
        };
    }

    private double calculateRsiMomentumScore(TechnicalIndicators indicators) {
        double rsi = indicators.getRsi();
        double score = 0.0;

        if (indicators.isRsiRising()) score += 0.5;
        if (rsi > 30 && rsi < 70) score += 0.3; // Good zone
        if (rsi > 50) score += 0.2; // Above midline

        return score;
    }

    private double calculateVolumeStrengthScore(TechnicalIndicators indicators) {
        double score = 0.0;
        double ratio = indicators.getVolumeRatio();

        if (ratio > 2.0) score = 1.0;
        else if (ratio > 1.5) score = 0.8;
        else if (ratio > 1.2) score = 0.6;
        else if (ratio > 1.0) score = 0.4;
        else if (ratio > 0.8) score = 0.2;
        else score = 0.0;

        if (indicators.isVolumeBreakout()) score += 0.2;

        return score;
    }

    private double calculateStructureBullishScore(TechnicalIndicators indicators) {
        double score = 0.0;

        if (indicators.isHigherHighs()) score += 0.4;
        if (indicators.isHigherLows()) score += 0.4;
        if (indicators.isBullishStructure()) score += 0.2;

        return score;
    }

    private double calculateStructureBearishScore(TechnicalIndicators indicators) {
        double score = 0.0;

        if (indicators.isLowerLows()) score += 0.4;
        if (indicators.isLowerHighs()) score += 0.4;
        if (indicators.isBearishStructure()) score += 0.2;

        return score;
    }

    private double calculateTrendMomentumScore(TechnicalIndicators indicators) {
        double trendScore = convertTrendToScore(indicators.getPrimaryTrend());
        double momentumScore = (indicators.getRsi() - 50.0) / 50.0;
        double macdScore = indicators.isMacdBullish() ? 0.5 : (indicators.isMacdBearish() ? -0.5 : 0.0);

        return (trendScore + momentumScore + macdScore) / 3.0;
    }

    private double calculateVolumePriceScore(TechnicalIndicators indicators) {
        double volumeScore = Math.min(1.0, indicators.getVolumeRatio() / 2.0);
        double priceScore = convertTrendToScore(indicators.getShortTermTrend());

        return volumeScore * priceScore;
    }

    private double calculateRiskAdjustedScore(TechnicalIndicators indicators) {
        double baseScore = calculateTrendMomentumScore(indicators);
        double riskPenalty = indicators.getVolatilityPercent() > 10.0 ? 0.5 : 0.0;
        double rrBonus = indicators.getRiskRewardRatio() > 2.0 ? 0.2 : 0.0;

        return baseScore - riskPenalty + rrBonus;
    }

    /**
     * Historical analysis - csak számítások, ugyanúgy mint az élő elemzésben
     */
    public CoinAnalysis analyzeHistoricalCoin(String symbol, double lastPrice,
                                              List<List<Object>> fourHourKlines,
                                              List<List<Object>> dailyKlines,
                                              List<List<Object>> hourlyKlines) {
        try {
            if (fourHourKlines.size() < 100 || dailyKlines.size() < 50) {
                return createNoTradeAnalysis(symbol, lastPrice, "Insufficient historical data");
            }

            // Extract price data
            List<Double> closes4h = extractCloses(fourHourKlines);
            List<Double> highs4h = extractHighs(fourHourKlines);
            List<Double> lows4h = extractLows(fourHourKlines);
            List<Double> volumes4h = extractVolumes(fourHourKlines);
            List<Double> closesDaily = extractCloses(dailyKlines);
            List<Double> closesHourly = extractCloses(hourlyKlines);

            double currentPrice = closes4h.get(closes4h.size() - 1);

            // Calculate all technical indicators
            TechnicalIndicators indicators = calculateAllIndicators(
                    closes4h, highs4h, lows4h, volumes4h,
                    closesDaily, closesHourly,
                    closes4h, highs4h, lows4h, // Use 4h for 15m approximation
                    currentPrice
            );

            // BACKTEST MODE: Generate directional signals for training data
            Direction direction = generateBacktestDirectionalSignal(indicators);
            Signal signal = convertDirectionToSignal(direction);
            double score = calculateDirectionalSignalScore(indicators, direction);

            // Create analysis result with ACTUAL DIRECTIONAL SIGNALS for backtesting
            CoinAnalysis analysis = new CoinAnalysis(symbol, score, signal, lastPrice);
            analysis.setTechnicalIndicators(indicators);
            analysis.setDirection(direction);
            analysis.setTradingRule(direction != Direction.HOLD ? 1 : 0);

            // Set synthetic probabilities for training
            Map<String, Double> syntheticProbabilities = generateSyntheticProbabilities(direction, score);
            analysis.setProbabilities(syntheticProbabilities);

            return analysis;
        } catch (Exception e) {
            log.error("Failed to analyze historical coin {}: {}", symbol, e.getMessage());
            return createNoTradeAnalysis(symbol, lastPrice, "Historical analysis failed");
        }
    }

    /**
     * Convert Direction enum to Signal enum for backwards compatibility
     */
    private Signal convertDirectionToSignal(Direction direction) {
        return switch (direction) {
            case LONG -> Signal.LONG;
            case SHORT -> Signal.SHORT;
            case HOLD -> Signal.NO_TRADE;
        };
    }

    private double calculateSignalScore(TechnicalIndicators indicators, Signal signal) {
        if (signal == Signal.NO_TRADE) {
            return 0.0;
        }

        double score = 5.0; // Base score

        if (signal == Signal.LONG) {
            // Trend bonus
            if ("BULLISH".equals(indicators.getPrimaryTrend())) score += 2.0;
            if ("BULLISH".equals(indicators.getShortTermTrend())) score += 1.0;
            if (indicators.isTrendAlignment()) score += 1.0;

            // Momentum bonus
            if (indicators.getRsi() > 30 && indicators.getRsi() < 70) score += 1.0;
            if (indicators.isMacdBullish()) score += 1.5;
            if (indicators.isRsiRising()) score += 0.5;

            // Volume bonus
            if (indicators.getVolumeRatio() > 1.3) score += 1.0;
            if (indicators.isVolumeBreakout()) score += 0.5;

            // Risk adjustment
            if (indicators.getRiskRewardRatio() > 2.0) score += 1.0;
            if (indicators.getVolatilityPercent() < 8.0) score += 0.5;

        } else if (signal == Signal.SHORT) {
            // Mirror logic for SHORT
            if ("BEARISH".equals(indicators.getPrimaryTrend())) score += 2.0;
            if ("BEARISH".equals(indicators.getShortTermTrend())) score += 1.0;
            if (indicators.isTrendAlignment()) score += 1.0;

            if (indicators.getRsi() > 30 && indicators.getRsi() < 70) score += 1.0;
            if (indicators.isMacdBearish()) score += 1.5;
            if (!indicators.isRsiRising()) score += 0.5;

            if (indicators.getVolumeRatio() > 1.3) score += 1.0;
            if (indicators.isVolumeBreakout()) score += 0.5;

            if (indicators.getRiskRewardRatio() > 2.0) score += 1.0;
            if (indicators.getVolatilityPercent() < 8.0) score += 0.5;
        }

        // Cap between 0-10
        return Math.max(0.0, Math.min(10.0, score));
    }

    // ÚJ metódus hozzáadása:
    private Signal generateBacktestSignal(TechnicalIndicators indicators) {
        // Egyszerű signál generálás backtesthez
        double bullScore = 0.0;
        double bearScore = 0.0;

        // Trend
        if ("BULLISH".equals(indicators.getPrimaryTrend())) bullScore += 2.0;
        if ("BEARISH".equals(indicators.getPrimaryTrend())) bearScore += 2.0;

        // RSI
        if (indicators.getRsi() < 35) bullScore += 2.0;
        if (indicators.getRsi() > 65) bearScore += 2.0;

        // MACD
        if (indicators.isMacdBullish()) bullScore += 1.5;
        if (indicators.isMacdBearish()) bearScore += 1.5;

        // Volume
        if (indicators.getVolumeRatio() > 1.3) {
            bullScore += 1.0;
            bearScore += 1.0;
        }

        // ALACSONY küszöbök több signal generáláshoz
        if (bullScore >= 3.0 && bullScore > bearScore) return Signal.LONG;
        if (bearScore >= 3.0 && bearScore > bullScore) return Signal.SHORT;

        return Signal.NO_TRADE;
    }

    // === HELPER METHODS ===

    private CoinAnalysis createNoTradeAnalysis(String symbol, double lastPrice, String reason) {
        CoinAnalysis analysis = new CoinAnalysis(symbol, 0.0, Signal.NO_TRADE, lastPrice);
        analysis.setAnalysisReason(reason);
        return analysis;
    }

    private String calculatePrimaryTrend(double currentPrice, double ema200_daily, double ema20_4h, double ema50_4h) {
        if (currentPrice > ema200_daily * 0.98 && ema20_4h > ema50_4h * 0.995) {
            return "BULLISH";
        } else if (currentPrice < ema200_daily * 1.02 && ema20_4h < ema50_4h * 1.005) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    private String calculateShortTermTrend(double currentPrice, double ema10_1h, double ema20_1h) {
        if (ema10_1h > ema20_1h && currentPrice > ema10_1h * 0.99) {
            return "BULLISH";
        } else if (ema10_1h < ema20_1h && currentPrice < ema10_1h * 1.01) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    private double calculateTrendStrength(double currentPrice, double ema200_daily, String primaryTrend) {
        if (primaryTrend.equals("BULLISH")) {
            return (currentPrice - ema200_daily) / ema200_daily * 100;
        } else if (primaryTrend.equals("BEARISH")) {
            return (ema200_daily - currentPrice) / ema200_daily * 100;
        }
        return 0.0;
    }

    private double calculateVolumePercentile(List<Double> volumes, double currentVolume) {
        List<Double> recentVolumes = volumes.subList(Math.max(0, volumes.size() - 50), volumes.size());
        long belowCurrent = recentVolumes.stream().mapToLong(v -> v < currentVolume ? 1 : 0).sum();
        return (double) belowCurrent / recentVolumes.size() * 100;
    }

    private void logTradingDecision(String message) {
        publisher.publishEvent(new TradeClosedUpdateEvent(this, message));
    }

    // Extract methods for cleaner code
    private List<Double> extractCloses(List<List<Object>> klines) {
        return klines.stream()
                .map(k -> Double.parseDouble(k.get(4).toString()))
                .toList();
    }

    private List<Double> extractHighs(List<List<Object>> klines) {
        return klines.stream()
                .map(k -> Double.parseDouble(k.get(2).toString()))
                .toList();
    }

    private List<Double> extractLows(List<List<Object>> klines) {
        return klines.stream()
                .map(k -> Double.parseDouble(k.get(3).toString()))
                .toList();
    }

    private List<Double> extractVolumes(List<List<Object>> klines) {
        return klines.stream()
                .map(k -> Double.parseDouble(k.get(5).toString()))
                .toList();
    }

    /**
     * Convert HistoricalCandle list to klines format
     */
    public List<List<String>> convertToKlines(List<HistoricalCandle> candles) {
        return candles.stream()
                .map(candle -> List.of(
                        candle.getTimestamp().toString(),
                        String.valueOf(candle.getOpen()),
                        String.valueOf(candle.getHigh()),
                        String.valueOf(candle.getLow()),
                        String.valueOf(candle.getClose()),
                        String.valueOf(candle.getVolume())
                ))
                .collect(Collectors.toList());
    }

    /**
     * Resample candles to different timeframe
     */
    public List<HistoricalCandle> resample(List<HistoricalCandle> candles, int hours) {
        if (hours == 1) return candles;

        List<HistoricalCandle> resampled = new ArrayList<>();

        for (int i = 0; i < candles.size(); i += hours) {
            List<HistoricalCandle> group = candles.subList(i, Math.min(i + hours, candles.size()));

            if (group.isEmpty()) continue;

            double open = group.get(0).getOpen();
            double high = group.stream().mapToDouble(HistoricalCandle::getHigh).max().orElse(0.0);
            double low = group.stream().mapToDouble(HistoricalCandle::getLow).min().orElse(0.0);
            double close = group.get(group.size() - 1).getClose();
            double volume = group.stream().mapToDouble(HistoricalCandle::getVolume).sum();

            HistoricalCandle resampledCandle = HistoricalCandle.builder()
                    .timestamp(group.get(0).getTimestamp())
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build();

            resampled.add(resampledCandle);
        }

        return resampled;
    }
}