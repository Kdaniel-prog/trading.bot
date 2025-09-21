#!/usr/bin/env python3
"""
ML Predictor - Java-Python Bridge
Fogadja a SwingAlgoService technikai adatait és ML döntést hoz
"""

import sys
import json
import numpy as np
import pandas as pd
from datetime import datetime
import os
from pathlib import Path


class SwingTradingPredictor:
    def __init__(self):
        """
        Swing Trading ML Predictor
        """
        self.version = "v1.0"
        self.confidence_threshold = 0.65

        # Model directory - resources/data under src/main/resources/data
        self.model_dir = Path("src/main/resources/data/models")
        self.model_dir.mkdir(parents=True, exist_ok=True)

        print(f"SwingTradingPredictor {self.version} initialized")

    def predict_trading_signal(self, java_data):
        """
        Fő predikciós logika - Java technikai adatok alapján

        Input: SwingAlgoService által kalkulált technikai indikátorok
        Output: LONG/SHORT/NO_TRADE + confidence
        """
        try:
            symbol = java_data.get('symbol', 'UNKNOWN')
            current_price = java_data.get('currentPrice', 0.0)

            print(f"Analyzing {symbol} at price {current_price}")

            # Technikai indikátorok kinyerése
            indicators = self.extract_technical_indicators(java_data)

            # ML döntési logika
            decision = self.make_trading_decision(indicators, symbol)

            return {
                'predictedSignal': decision['signal'],
                'confidence': decision['confidence'],
                'probability': decision['probability'],
                'reasoning': decision['reasoning'],
                'technicalScore': decision['technical_score']
            }

        except Exception as e:
            print(f"Prediction error: {e}")
            return {
                'predictedSignal': 'NO_TRADE',
                'confidence': 0.0,
                'probability': 0.5,
                'error': str(e)
            }

    def extract_technical_indicators(self, java_data):
        """
        Java TechnicalIndicators objektumból feature-ök kinyerése
        """
        indicators = {}

        try:
            tech = java_data.get('technicalIndicators', {})

            # Get current price from multiple sources
            current_price = java_data.get('currentPrice',
                                          tech.get('currentPrice',
                                                   java_data.get('price', 1.0)))

            if current_price <= 0:
                current_price = 1.0

            indicators['current_price'] = current_price

            # Trend indicators
            indicators['primary_trend'] = self.convert_trend(tech.get('primaryTrend', 'NEUTRAL'))
            indicators['short_term_trend'] = self.convert_trend(tech.get('shortTermTrend', 'NEUTRAL'))
            indicators['trend_alignment'] = tech.get('trendAlignment', False)
            indicators['trend_strength'] = tech.get('trendStrength', 0.0)

            # Price vs EMAs (handle division by zero)
            indicators['price_vs_ema20_4h'] = current_price / max(tech.get('ema20_4h', current_price), 0.1)
            indicators['price_vs_ema50_4h'] = current_price / max(tech.get('ema50_4h', current_price), 0.1)
            indicators['price_vs_ema200'] = current_price / max(tech.get('ema200_daily', current_price), 0.1)

            # Momentum indicators
            indicators['rsi'] = tech.get('rsi', 50.0)
            indicators['rsi_normalized'] = (tech.get('rsi', 50.0) - 50.0) / 50.0
            indicators['macd_bullish'] = tech.get('macdBullish', False)
            indicators['macd_bearish'] = tech.get('macdBearish', False)
            indicators['rsi_bullish_zone'] = tech.get('rsiBullishZone', False)
            indicators['rsi_bearish_zone'] = tech.get('rsiBearishZone', False)
            indicators['rsi_oversold'] = tech.get('rsiOversold', False)
            indicators['rsi_overbought'] = tech.get('rsiOverbought', False)
            indicators['rsi_rising'] = tech.get('rsiRising', False)

            # Volume indicators
            volume_ratio = tech.get('volumeRatio', 1.0)
            indicators['volume_ratio'] = volume_ratio
            indicators['strong_volume'] = tech.get('strongVolume', False)
            indicators['volume_breakout'] = tech.get('volumeBreakout', False)
            indicators['volume_trend_up'] = tech.get('volumeTrendUp', False)

            # Risk indicators
            indicators['volatility_percent'] = tech.get('volatilityPercent', 0.0)
            indicators['risk_reward_ratio'] = tech.get('riskRewardRatio', 0.0)
            indicators['distance_from_support'] = tech.get('distanceFromSupport', 0.0)
            indicators['distance_from_resistance'] = tech.get('distanceFromResistance', 0.0)

            # Structure indicators
            indicators['bullish_structure'] = tech.get('bullishStructure', False)
            indicators['bearish_structure'] = tech.get('bearishStructure', False)
            indicators['higher_highs'] = tech.get('higherHighs', False)
            indicators['lower_lows'] = tech.get('lowerLows', False)
            indicators['consolidation'] = tech.get('consolidation', False)

            return indicators

        except Exception as e:
            print(f"Indicator extraction error: {e}")
            return {'current_price': java_data.get('currentPrice', 1.0)}

    def make_trading_decision(self, indicators, symbol):
        """
        ML-alapú trading döntés
        """
        # Alapértelmezett értékek
        signal = 'NO_TRADE'
        confidence = 0.0
        probability = 0.5
        reasoning = []
        technical_score = 0.0

        try:
            # === BULLISH SCORE CALCULATION ===
            bullish_score = 0.0

            # Trend scoring (max 30 points)
            if indicators.get('primary_trend', 0) > 0:
                bullish_score += 12
                reasoning.append("Primary trend bullish")
            if indicators.get('short_term_trend', 0) > 0:
                bullish_score += 8
                reasoning.append("Short-term trend bullish")
            if indicators.get('trend_alignment', False) and indicators.get('primary_trend', 0) > 0:
                bullish_score += 10
                reasoning.append("Trend alignment bullish")

            # Price vs EMA scoring (max 15 points)
            if indicators.get('price_vs_ema20_4h', 1.0) > 1.02:
                bullish_score += 5
                reasoning.append("Price above EMA20")
            if indicators.get('price_vs_ema50_4h', 1.0) > 1.01:
                bullish_score += 5
                reasoning.append("Price above EMA50")
            if indicators.get('price_vs_ema200', 1.0) > 1.005:
                bullish_score += 5
                reasoning.append("Price above EMA200")

            # RSI scoring (max 20 points)
            rsi = indicators.get('rsi', 50.0)
            if indicators.get('rsi_oversold', False):
                bullish_score += 15
                reasoning.append("RSI oversold - bounce expected")
            elif 35 < rsi < 65 and indicators.get('rsi_rising', False):
                bullish_score += 10
                reasoning.append("RSI rising in healthy zone")
            elif indicators.get('rsi_bullish_zone', False):
                bullish_score += 5
                reasoning.append("RSI in bullish zone")

            # MACD scoring (max 10 points)
            if indicators.get('macd_bullish', False):
                bullish_score += 10
                reasoning.append("MACD bullish crossover")

            # Volume scoring (max 15 points)
            if indicators.get('strong_volume', False):
                bullish_score += 8
                reasoning.append("Strong volume support")
            if indicators.get('volume_breakout', False):
                bullish_score += 7
                reasoning.append("Volume breakout")

            # Structure scoring (max 10 points)
            if indicators.get('bullish_structure', False):
                bullish_score += 6
                reasoning.append("Bullish market structure")
            if indicators.get('higher_highs', False):
                bullish_score += 4
                reasoning.append("Higher highs pattern")

            # === BEARISH SCORE CALCULATION ===
            bearish_score = 0.0

            # Trend scoring (max -30 points)
            if indicators.get('primary_trend', 0) < 0:
                bearish_score -= 12
                reasoning.append("Primary trend bearish")
            if indicators.get('short_term_trend', 0) < 0:
                bearish_score -= 8
                reasoning.append("Short-term trend bearish")
            if indicators.get('trend_alignment', False) and indicators.get('primary_trend', 0) < 0:
                bearish_score -= 10
                reasoning.append("Trend alignment bearish")

            # Price vs EMA scoring (max -15 points)
            if indicators.get('price_vs_ema20_4h', 1.0) < 0.98:
                bearish_score -= 5
                reasoning.append("Price below EMA20")
            if indicators.get('price_vs_ema50_4h', 1.0) < 0.99:
                bearish_score -= 5
                reasoning.append("Price below EMA50")
            if indicators.get('price_vs_ema200', 1.0) < 0.995:
                bearish_score -= 5
                reasoning.append("Price below EMA200")

            # RSI scoring (max -20 points)
            if indicators.get('rsi_overbought', False):
                bearish_score -= 15
                reasoning.append("RSI overbought - correction expected")
            elif 35 < rsi < 65 and not indicators.get('rsi_rising', False):
                bearish_score -= 8
                reasoning.append("RSI falling")
            elif indicators.get('rsi_bearish_zone', False):
                bearish_score -= 5
                reasoning.append("RSI in bearish zone")

            # MACD scoring (max -10 points)
            if indicators.get('macd_bearish', False):
                bearish_score -= 10
                reasoning.append("MACD bearish crossover")

            # Structure scoring (max -10 points)
            if indicators.get('bearish_structure', False):
                bearish_score -= 6
                reasoning.append("Bearish market structure")
            if indicators.get('lower_lows', False):
                bearish_score -= 4
                reasoning.append("Lower lows pattern")

            # === RISK MANAGEMENT ===
            risk_penalty = 0.0

            # Volatility check
            volatility = indicators.get('volatility_percent', 0.0)
            if volatility > 15.0:
                risk_penalty -= 10
                reasoning.append(f"High volatility risk: {volatility:.1f}%")

            # Risk/Reward ratio
            rr_ratio = indicators.get('risk_reward_ratio', 0.0)
            if rr_ratio < 1.5:
                risk_penalty -= 8
                reasoning.append(f"Poor R:R ratio: {rr_ratio:.2f}")
            elif rr_ratio > 3.0:
                bullish_score += 5
                reasoning.append(f"Excellent R:R ratio: {rr_ratio:.2f}")

            # === FINAL DECISION ===
            total_score = bullish_score + bearish_score + risk_penalty
            technical_score = total_score

            # Decision thresholds
            if total_score >= 25:
                signal = 'LONG'
                confidence = min(0.95, (total_score - 15) / 50 + 0.65)
                probability = 0.7 + min(0.25, total_score / 100)
            elif total_score <= -25:
                signal = 'SHORT'
                confidence = min(0.95, abs(total_score - 15) / 50 + 0.65)
                probability = 0.7 + min(0.25, abs(total_score) / 100)
            else:
                signal = 'NO_TRADE'
                confidence = 0.5 - abs(total_score) / 100
                probability = 0.5
                reasoning.append("Insufficient signal strength")

            # Confidence threshold check
            if confidence < self.confidence_threshold:
                signal = 'NO_TRADE'
                confidence = max(0.1, confidence * 0.8)
                reasoning.append(f"Below confidence threshold: {self.confidence_threshold}")

            print(f"Decision: {signal}, Score: {total_score:.1f}, Confidence: {confidence:.2f}")
            print(f"Reasoning: {'; '.join(reasoning[:5])}")  # Top 5 reasons

        except Exception as e:
            print(f"Decision making error: {e}")
            reasoning.append(f"Error in calculation: {str(e)}")

        return {
            'signal': signal,
            'confidence': confidence,
            'probability': probability,
            'reasoning': reasoning,
            'technical_score': technical_score
        }

    def convert_trend(self, trend_str):
        """Trend string konvertálása numerikus értékre"""
        if trend_str == 'BULLISH':
            return 1.0
        elif trend_str == 'BEARISH':
            return -1.0
        else:
            return 0.0


def main():
    """
    Fő függvény - Java hívja meg
    """
    if len(sys.argv) != 3:
        print("Usage: python ml_predictor.py <input_file> <output_file>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    try:
        # Input adatok beolvasása Java-ból
        with open(input_file, 'r', encoding='utf-8') as f:
            request_data = json.load(f)

        symbol = request_data.get('symbol', 'UNKNOWN')
        print(f"Processing ML prediction for: {symbol}")

        # Predictor inicializálása
        predictor = SwingTradingPredictor()

        # Predikció végrehajtása
        start_time = datetime.now()
        result = predictor.predict_trading_signal(request_data)
        processing_time = (datetime.now() - start_time).total_seconds() * 1000

        # Response összeállítása
        response = {
            'symbol': symbol,
            'predictedSignal': result['predictedSignal'],
            'confidence': result['confidence'],
            'probability': result['probability'],
            'modelVersion': f'SwingPredictor_{predictor.version}',
            'processingTimeMs': processing_time,
            'reasoning': result.get('reasoning', []),
            'technicalScore': result.get('technical_score', 0.0)
        }

        if 'error' in result:
            response['error'] = result['error']

        # Eredmény mentése
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(response, f, indent=2, ensure_ascii=False)

        print(f"Prediction complete: {result['predictedSignal']} ({result['confidence']:.2f} confidence)")
        if result.get('reasoning'):
            print(f"Key factors: {'; '.join(result['reasoning'][:3])}")

    except Exception as e:
        # Hiba esetén default válasz
        error_response = {
            'symbol': request_data.get('symbol', 'ERROR') if 'request_data' in locals() else 'ERROR',
            'predictedSignal': 'NO_TRADE',
            'confidence': 0.0,
            'probability': 0.5,
            'error': str(e),
            'processingTimeMs': 0,
            'modelVersion': 'SwingPredictor_v1.0'
        }

        try:
            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(error_response, f, indent=2, ensure_ascii=False)
        except:
            pass

        print(f"Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()