#!/usr/bin/env python3
"""
Deep Learning Trading Predictor
Használja a SwingAlgoService adatait deep learning modellel
"""

import sys
import json
import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
from sklearn.preprocessing import StandardScaler, LabelEncoder
import warnings
import os
from datetime import datetime
import joblib

warnings.filterwarnings('ignore')
tf.get_logger().setLevel('ERROR')

class DeepTradingPredictor:
    def __init__(self):
        """
        Deep Learning model trading predikcióhoz
        """
        self.model_path = 'src/main/python/models/deep_trading_model.h5'
        self.scaler_path = 'src/main/python/models/scaler.pkl'
        self.label_encoder_path = 'src/main/python/models/label_encoder.pkl'

        # Mappák létrehozása
        os.makedirs(os.path.dirname(self.model_path), exist_ok=True)

        # Model paraméterek
        self.input_features = 25  # Java-ból jövő feature-ök száma
        self.model = None
        self.scaler = StandardScaler()
        self.label_encoder = LabelEncoder()

        # Betöltés vagy új model létrehozása
        self.load_or_create_model()

    def load_or_create_model(self):
        """Model betöltése vagy új létrehozása"""
        try:
            if os.path.exists(self.model_path):
                self.model = keras.models.load_model(self.model_path)
                self.scaler = joblib.load(self.scaler_path)
                self.label_encoder = joblib.load(self.label_encoder_path)
                print("✅ Deep Learning model betöltve")
            else:
                self.create_deep_model()
                print("🔧 Új Deep Learning model létrehozva")
        except Exception as e:
            print(f"❌ Model betöltési hiba: {e}")
            self.create_deep_model()

    def create_deep_model(self):
        """
        Deep Neural Network létrehozása
        Inputok: SwingAlgoService technikai indikátorai
        Output: LONG, SHORT, NO_TRADE + confidence
        """

        # Input layer - Java-ból jövő 25 feature
        inputs = keras.Input(shape=(self.input_features,), name='trading_features')

        # Deep Neural Network architektúra
        # 1. réteg - Feature extraction
        x = layers.Dense(128, activation='relu', name='feature_dense_1')(inputs)
        x = layers.BatchNormalization(name='bn1')(x)
        x = layers.Dropout(0.3, name='dropout1')(x)

        # 2. réteg - Pattern recognition
        x = layers.Dense(64, activation='relu', name='pattern_dense_1')(x)
        x = layers.BatchNormalization(name='bn2')(x)
        x = layers.Dropout(0.25, name='dropout2')(x)

        # 3. réteg - Market regime detection
        x = layers.Dense(32, activation='relu', name='regime_dense')(x)
        x = layers.BatchNormalization(name='bn3')(x)
        x = layers.Dropout(0.2, name='dropout3')(x)

        # 4. réteg - Signal processing
        x = layers.Dense(16, activation='relu', name='signal_dense')(x)
        x = layers.Dropout(0.1, name='dropout4')(x)

        # Output rétegek
        # Signal prediction (3 classes: LONG=0, NO_TRADE=1, SHORT=2)
        signal_output = layers.Dense(3, activation='softmax', name='signal_prediction')(x)

        # Confidence prediction (regression 0-1)
        confidence_output = layers.Dense(1, activation='sigmoid', name='confidence_prediction')(x)

        # Model összeállítása
        self.model = keras.Model(
            inputs=inputs,
            outputs=[signal_output, confidence_output],
            name='DeepTradingPredictor'
        )

        # Optimizer és loss funkciók
        self.model.compile(
            optimizer=keras.optimizers.Adam(learning_rate=0.001),
            loss={
                'signal_prediction': 'categorical_crossentropy',
                'confidence_prediction': 'mse'
            },
            loss_weights={
                'signal_prediction': 0.8,  # Signal fontosabb
                'confidence_prediction': 0.2
            },
            metrics={
                'signal_prediction': ['accuracy'],
                'confidence_prediction': ['mae']
            }
        )

        print("🧠 Deep Learning architektúra:")
        self.model.summary()

    def extract_features_from_java_data(self, java_data):
        """
        SwingAlgoService adataiból feature-ök kinyerése
        """
        try:
            features = []

            # 1. Trend Analysis adatok
            trend = java_data.get('trendAnalysis', {})
            features.extend([
                1.0 if trend.get('primaryTrend') == 'BULLISH' else (-1.0 if trend.get('primaryTrend') == 'BEARISH' else 0.0),
                1.0 if trend.get('shortTermTrend') == 'BULLISH' else (-1.0 if trend.get('shortTermTrend') == 'BEARISH' else 0.0),
                1.0 if trend.get('trendAlignment', False) else 0.0,
                float(trend.get('trendStrength', 0.0)),
                float(trend.get('ema20_4h', 0.0)) / float(java_data.get('currentPrice', 1.0)),
                float(trend.get('ema50_4h', 0.0)) / float(java_data.get('currentPrice', 1.0)),
                ])

            # 2. Momentum Analysis adatok
            momentum = java_data.get('momentumAnalysis', {})
            features.extend([
                float(momentum.get('rsi', 50.0)) / 100.0,  # Normalized RSI
                1.0 if momentum.get('macdBullish', False) else 0.0,
                1.0 if momentum.get('macdBearish', False) else 0.0,
                1.0 if momentum.get('rsiBullishZone', False) else 0.0,
                1.0 if momentum.get('rsiBearishZone', False) else 0.0,
                1.0 if momentum.get('rsiRising', False) else 0.0,
                ])

            # 3. Volume Analysis adatok
            volume = java_data.get('volumeAnalysis', {})
            features.extend([
                float(volume.get('volumeRatio', 1.0)),
                1.0 if volume.get('strongVolume', False) else 0.0,
                float(volume.get('volumePercentile', 50.0)) / 100.0,
                1.0 if volume.get('volumeBreakout', False) else 0.0,
                1.0 if volume.get('volumeTrendUp', False) else 0.0,
                ])

            # 4. Risk Analysis adatok
            risk = java_data.get('riskAnalysis', {})
            features.extend([
                float(risk.get('riskRewardRatio', 0.0)),
                float(risk.get('volatilityPercent', 0.0)) / 100.0,
                float(risk.get('distanceFromSupport', 0.0)) / 100.0,
                float(risk.get('distanceFromResistance', 0.0)) / 100.0,
                1.0 if risk.get('goodVolatility', False) else 0.0,
                1.0 if risk.get('nearSupport', False) else 0.0,
                ])

            # 5. Structure Analysis adatok
            structure = java_data.get('structureAnalysis', {})
            features.extend([
                1.0 if structure.get('higherHighs', False) else 0.0,
                1.0 if structure.get('lowerLows', False) else 0.0,
                1.0 if structure.get('bullishPattern', False) else 0.0,
                1.0 if structure.get('bearishPattern', False) else 0.0,
                float(structure.get('structureStrength', 0.0)),
            ])

            # Ha kevés feature van, 0-val töltjük fel 25-re
            while len(features) < self.input_features:
                features.append(0.0)

            # Ha több van, levágjuk 25-re
            features = features[:self.input_features]

            return np.array(features, dtype=np.float32)

        except Exception as e:
            print(f"❌ Feature extraction error: {e}")
            return np.zeros(self.input_features, dtype=np.float32)

    def predict_signal(self, java_data):
        """
        Fő predikciós függvény
        """
        try:
            # Feature-ök kinyerése Java adatokból
            features = self.extract_features_from_java_data(java_data)

            # Scaling (ha van betanított scaler)
            if hasattr(self.scaler, 'scale_'):
                features = self.scaler.transform(features.reshape(1, -1))[0]

            # Predikció
            features_input = features.reshape(1, -1)
            signal_pred, confidence_pred = self.model.predict(features_input, verbose=0)

            # Signal dekódolás
            signal_class = np.argmax(signal_pred[0])
            signal_confidence = float(np.max(signal_pred[0]))
            confidence_value = float(confidence_pred[0][0])

            # Signal mapping
            signal_map = {0: "LONG", 1: "NO_TRADE", 2: "SHORT"}
            predicted_signal = signal_map[signal_class]

            # Kombinált confidence (signal + confidence prediction)
            final_confidence = (signal_confidence + confidence_value) / 2.0

            # Minimum confidence threshold
            if final_confidence < 0.6:
                predicted_signal = "NO_TRADE"
                final_confidence = 0.5

            return {
                'predictedSignal': predicted_signal,
                'confidence': final_confidence,
                'probability': signal_confidence,
                'rawPredictions': {
                    'long_prob': float(signal_pred[0][0]),
                    'no_trade_prob': float(signal_pred[0][1]),
                    'short_prob': float(signal_pred[0][2]),
                    'confidence_raw': confidence_value
                }
            }

        except Exception as e:
            print(f"❌ Prediction error: {e}")
            return {
                'predictedSignal': 'NO_TRADE',
                'confidence': 0.0,
                'probability': 0.5,
                'error': str(e)
            }

    def save_model(self):
        """Model mentése"""
        try:
            self.model.save(self.model_path)
            joblib.dump(self.scaler, self.scaler_path)
            joblib.dump(self.label_encoder, self.label_encoder_path)
            print("✅ Model sikeresen elmentve")
        except Exception as e:
            print(f"❌ Model mentési hiba: {e}")

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
        with open(input_file, 'r') as f:
            request_data = json.load(f)

        print(f"📊 Processing prediction for: {request_data.get('symbol', 'UNKNOWN')}")

        # Deep Learning Predictor inicializálása
        predictor = DeepTradingPredictor()

        # Predikció végrehajtása
        start_time = datetime.now()
        result = predictor.predict_signal(request_data)
        processing_time = (datetime.now() - start_time).total_seconds() * 1000

        # Response összeállítása
        response = {
            'symbol': request_data.get('symbol', 'UNKNOWN'),
            'predictedSignal': result['predictedSignal'],
            'confidence': result['confidence'],
            'probability': result['probability'],
            'modelVersion': 'DeepLearning_v1.0',
            'processingTimeMs': processing_time,
            'additionalMetrics': result.get('rawPredictions', {})
        }

        if 'error' in result:
            response['error'] = result['error']

        # Eredmény mentése
        with open(output_file, 'w') as f:
            json.dump(response, f, indent=2)

        print(f"✅ Prediction complete: {result['predictedSignal']} ({result['confidence']:.2f})")

    except Exception as e:
        # Hiba esetén default válasz
        error_response = {
            'symbol': 'ERROR',
            'predictedSignal': 'NO_TRADE',
            'confidence': 0.0,
            'probability': 0.5,
            'error': str(e),
            'processingTimeMs': 0
        }

        with open(output_file, 'w') as f:
            json.dump(error_response, f, indent=2)

        print(f"❌ Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()