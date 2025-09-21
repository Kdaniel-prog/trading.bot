#!/usr/bin/env python3
"""
ML Predictor - Java-Python Bridge
Használja a meglévő Neural Net és XGBoost modelleket
a SwingAlgoService technikai adatai alapján
"""

import sys
import json
import numpy as np
from pathlib import Path
import pickle
from tensorflow.keras.models import load_model

class SwingTradingPredictor:
    def __init__(self):
        self.version = "v1.1"
        self.confidence_threshold = 0.65
        self.model_dir = Path("src/main/resources/data/models")
        self.model_dir.mkdir(parents=True, exist_ok=True)

        # Betöltjük a modelleket
        self.nn_model = None
        self.xgb_model = None
        self.scaler = None

        nn_path = self.model_dir / "neural_net_classifier.keras"
        xgb_path = self.model_dir / "xgb_classifier.pkl"
        scaler_path = self.model_dir / "scaler.pkl"

        if nn_path.exists():
            self.nn_model = load_model(nn_path)
            print("Loaded Neural Net model")
        if xgb_path.exists():
            with open(xgb_path, "rb") as f:
                self.xgb_model = pickle.load(f)
            print("Loaded XGBoost model")
        if scaler_path.exists():
            with open(scaler_path, "rb") as f:
                self.scaler = pickle.load(f)
            print("Loaded scaler")

    def predict_trading_signal(self, java_data):
        try:
            symbol = java_data.get('symbol', 'UNKNOWN')
            indicators = self.extract_technical_indicators(java_data)

            # Konvertálás numpy array-be
            features = np.array([list(indicators.values())], dtype=float)

            # Skálázás
            if self.scaler:
                features_scaled = self.scaler.transform(features)
            else:
                features_scaled = features

            # Neural Net predikció
            nn_signal = 'NO_TRADE'
            nn_conf = 0.5
            if self.nn_model:
                nn_pred = self.nn_model.predict(features_scaled, verbose=0)
                nn_conf = float(nn_pred[0,0])
                nn_signal = 'LONG' if nn_conf > 0.5 else 'SHORT'

            # XGBoost predikció
            xgb_signal = 'NO_TRADE'
            xgb_conf = 0.5
            if self.xgb_model:
                xgb_pred = self.xgb_model.predict(features_scaled)
                xgb_proba = self.xgb_model.predict_proba(features_scaled)[0,1]
                xgb_conf = float(xgb_proba)
                xgb_signal = 'LONG' if xgb_pred[0] == 1 else 'SHORT'

            # Kombinált döntés (egyszerű átlagszabály)
            combined_conf = (nn_conf + xgb_conf) / 2
            if combined_conf >= self.confidence_threshold:
                final_signal = 'LONG'
            elif combined_conf <= 1 - self.confidence_threshold:
                final_signal = 'SHORT'
            else:
                final_signal = 'NO_TRADE'

            return {
                'signal': final_signal,
                'confidence': combined_conf,
                'probability': combined_conf,
                'nn_signal': nn_signal,
                'nn_confidence': nn_conf,
                'xgb_signal': xgb_signal,
                'xgb_confidence': xgb_conf
            }

        except Exception as e:
            print(f"Prediction error: {e}")
            return {
                'signal': 'NO_TRADE',
                'confidence': 0.0,
                'probability': 0.5,
                'error': str(e)
            }

    def extract_technical_indicators(self, java_data):
        """
        Egyszerűsített: minden technikai indikátor float vagy bool formátumba
        """
        tech = java_data.get('technicalIndicators', {})
        indicators = {}
        # trend
        indicators['primary_trend'] = self.convert_trend(tech.get('primaryTrend', 'NEUTRAL'))
        indicators['short_term_trend'] = self.convert_trend(tech.get('shortTermTrend', 'NEUTRAL'))
        indicators['trend_alignment'] = float(tech.get('trendAlignment', False))
        indicators['trend_strength'] = float(tech.get('trendStrength', 0.0))
        # EMA
        current_price = float(java_data.get('currentPrice', 1.0))
        indicators['price_vs_ema20_4h'] = current_price / max(float(tech.get('ema20_4h', current_price)), 0.1)
        indicators['price_vs_ema50_4h'] = current_price / max(float(tech.get('ema50_4h', current_price)), 0.1)
        indicators['price_vs_ema200'] = current_price / max(float(tech.get('ema200_daily', current_price)), 0.1)
        # RSI
        indicators['rsi'] = float(tech.get('rsi', 50.0))
        indicators['rsi_normalized'] = (float(tech.get('rsi', 50.0)) - 50.0)/50.0
        indicators['rsi_oversold'] = float(tech.get('rsiOversold', False))
        indicators['rsi_overbought'] = float(tech.get('rsiOverbought', False))
        indicators['rsi_rising'] = float(tech.get('rsiRising', False))
        # MACD
        indicators['macd_bullish'] = float(tech.get('macdBullish', False))
        indicators['macd_bearish'] = float(tech.get('macdBearish', False))
        # Volume
        indicators['volume_ratio'] = float(tech.get('volumeRatio', 1.0))
        indicators['strong_volume'] = float(tech.get('strongVolume', False))
        indicators['volume_breakout'] = float(tech.get('volumeBreakout', False))
        indicators['volume_trend_up'] = float(tech.get('volumeTrendUp', False))
        # Risk
        indicators['volatility_percent'] = float(tech.get('volatilityPercent', 0.0))
        indicators['risk_reward_ratio'] = float(tech.get('riskRewardRatio', 0.0))
        indicators['distance_from_support'] = float(tech.get('distanceFromSupport', 0.0))
        indicators['distance_from_resistance'] = float(tech.get('distanceFromResistance', 0.0))
        # Structure
        indicators['bullish_structure'] = float(tech.get('bullishStructure', False))
        indicators['bearish_structure'] = float(tech.get('bearishStructure', False))
        indicators['higher_highs'] = float(tech.get('higherHighs', False))
        indicators['lower_lows'] = float(tech.get('lowerLows', False))
        indicators['consolidation'] = float(tech.get('consolidation', False))

        return indicators

    def convert_trend(self, trend_str):
        if trend_str == 'BULLISH':
            return 1.0
        elif trend_str == 'BEARISH':
            return -1.0
        else:
            return 0.0

def main():
    if len(sys.argv) != 3:
        print("Usage: python ml_predictor.py <input_file> <output_file>")
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2]

    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            request_data = json.load(f)

        symbol = request_data.get('symbol', 'UNKNOWN')
        print(f"Processing prediction for: {symbol}")

        predictor = SwingTradingPredictor()
        result = predictor.predict_trading_signal(request_data)

        response = {
            'symbol': symbol,
            'predictedSignal': result['signal'],
            'confidence': result['confidence'],
            'probability': result['probability'],
            'modelVersion': f'SwingPredictor_{predictor.version}',
            'nn_signal': result.get('nn_signal'),
            'nn_confidence': result.get('nn_confidence'),
            'xgb_signal': result.get('xgb_signal'),
            'xgb_confidence': result.get('xgb_confidence')
        }

        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(response, f, indent=2, ensure_ascii=False)

        print(f"Prediction complete: {result['signal']} ({result['confidence']:.2f} confidence)")

    except Exception as e:
        error_response = {
            'symbol': request_data.get('symbol', 'ERROR') if 'request_data' in locals() else 'ERROR',
            'predictedSignal': 'NO_TRADE',
            'confidence': 0.0,
            'probability': 0.5,
            'error': str(e),
            'modelVersion': 'SwingPredictor_v1.1'
        }
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(error_response, f, indent=2, ensure_ascii=False)
        print(f"Error: {e}")
        sys
