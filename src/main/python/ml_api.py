"""
Python ML Prediction Service for Java Integration
Receives data from SwingAlgoService and returns LONG/SHORT/HOLD decisions
"""

import json
import pickle
import sys
import numpy as np
import pandas as pd
from pathlib import Path
from tensorflow import keras
from sklearn.preprocessing import StandardScaler
import logging
from typing import Dict, List, Tuple, Optional
from flask import Flask, request, jsonify
import warnings
warnings.filterwarnings('ignore')

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)

class JavaMLPredictionService:
    def __init__(self, model_dir=None):
        """
        ML Service that integrates with Java SwingAlgoService
        """
        if model_dir:
            self.model_dir = Path(model_dir)
        else:
            # Auto-detect model directory
            self.model_dir = self._find_model_directory()
        self.models = {}
        self.scaler = None
        self.feature_encoder = None
        self.model_info = None

        # Expected feature names from Java
        self.expected_features = [
            # Price ratios
            'price_vs_ema20_4h', 'price_vs_ema50_4h', 'price_vs_ema200_daily',
            'ema20_vs_ema50_4h', 'ema10_vs_ema20_1h',

            # Trend scores
            'primaryTrendScore', 'shortTermTrendScore', 'trendAlignment', 'trendStrength',

            # Momentum indicators
            'rsi', 'rsiNormalized', 'macdLine', 'macdSignal', 'macdHistogram', 'macdDivergence',
            'rsiOverboughtScore', 'rsiOversoldScore', 'rsiMomentumScore',

            # Volume features
            'volumeRatio', 'volumeRatioLog', 'volumeStrengthScore', 'volumeTrendScore',

            # Risk features
            'atrPercent', 'shortTermVolatility', 'volatilityRatio',
            'distanceFromSupport', 'distanceFromResistance', 'riskRewardRatio', 'riskRewardScore',

            # Structure features
            'structureBullishScore', 'structureBearishScore', 'consolidationScore',

            # Composite scores
            'trendMomentumScore', 'volumePriceScore', 'riskAdjustedScore'
        ]

        self.load_models()

    def _find_model_directory(self):
        """Find the models directory automatically"""
        possible_paths = [
            Path("E:/work/trading.bot/src/data/models"),
            Path("src/data/models"),
            Path("../src/data/models"),
            Path("../../src/data/models"),
            Path("models"),
            Path("data/models")
        ]

        for path in possible_paths:
            if path.exists():
                logger.info(f"Found model directory: {path}")
                return path

        # If none found, create the expected one
        expected_path = Path("E:/work/trading.bot/src/data/models")
        expected_path.mkdir(parents=True, exist_ok=True)
        logger.warning(f"No model directory found, created: {expected_path}")
        return expected_path

    def load_models(self):
        """Load trained models and preprocessors"""
        try:
            # Load directional models
            xgb_path = self.model_dir / "xgb_directional.pkl"
            nn_path = self.model_dir / "neural_net_directional.keras"
            scaler_path = self.model_dir / "directional_scaler.pkl"
            info_path = self.model_dir / "directional_model_info.json"

            # Load XGBoost
            if xgb_path.exists():
                with open(xgb_path, 'rb') as f:
                    self.models['xgboost'] = pickle.load(f)
                logger.info("Loaded XGBoost directional model")
            else:
                logger.warning("XGBoost model not found")

            # Load Neural Network
            if nn_path.exists():
                self.models['neural_net'] = keras.models.load_model(nn_path)
                logger.info("Loaded Neural Network directional model")
            else:
                logger.warning("Neural Network model not found")

            # Load scaler
            if scaler_path.exists():
                with open(scaler_path, 'rb') as f:
                    self.scaler = pickle.load(f)
                logger.info("Loaded scaler")
            else:
                logger.warning("Scaler not found")

            # Load model info
            if info_path.exists():
                with open(info_path, 'r') as f:
                    self.model_info = json.load(f)
                logger.info("Loaded model info")

            if not self.models:
                logger.error("No models loaded!")
                return False

            return True

        except Exception as e:
            logger.error(f"Failed to load models: {e}")
            return False

    def extract_features_from_java_data(self, java_data: Dict) -> np.ndarray:
        """
        Extract and prepare features from Java mlData
        """
        try:
            features = []

            for feature_name in self.expected_features:
                value = java_data.get(feature_name, 0.0)

                # Handle potential None/null values
                if value is None:
                    value = 0.0

                # Convert to float
                try:
                    value = float(value)
                except (ValueError, TypeError):
                    logger.warning(f"Invalid value for {feature_name}: {value}, using 0.0")
                    value = 0.0

                # Handle infinite values
                if np.isinf(value) or np.isnan(value):
                    value = 0.0

                features.append(value)

            # Convert to numpy array
            features_array = np.array(features).reshape(1, -1)

            logger.info(f"Extracted {len(features)} features from Java data")
            return features_array

        except Exception as e:
            logger.error(f"Error extracting features: {e}")
            # Return zeros as fallback
            return np.zeros((1, len(self.expected_features)))

    def predict_direction(self, java_data: Dict) -> Dict:
        """
        Main prediction method called from Java
        Returns: {'direction': 'LONG/SHORT/HOLD', 'confidence': 0.0-1.0, 'probabilities': [...]}
        """
        try:
            symbol = java_data.get('symbol', 'UNKNOWN')
            current_price = java_data.get('currentPrice', 0.0)

            logger.info(f"Making prediction for {symbol} at ${current_price}")

            # Extract features
            features = self.extract_features_from_java_data(java_data)

            # Scale features if scaler is available
            if self.scaler:
                features_scaled = self.scaler.transform(features)
            else:
                features_scaled = features

            # Make predictions with both models
            predictions = {}

            # XGBoost prediction
            if 'xgboost' in self.models:
                xgb_pred_proba = self.models['xgboost'].predict_proba(features_scaled)[0]
                xgb_pred_class = np.argmax(xgb_pred_proba)

                predictions['xgboost'] = {
                    'class': int(xgb_pred_class),
                    'probabilities': xgb_pred_proba.tolist(),
                    'confidence': float(xgb_pred_proba[xgb_pred_class])
                }

            # Neural Network prediction
            if 'neural_net' in self.models:
                nn_pred_proba = self.models['neural_net'].predict(features_scaled, verbose=0)[0]
                nn_pred_class = np.argmax(nn_pred_proba)

                predictions['neural_net'] = {
                    'class': int(nn_pred_class),
                    'probabilities': nn_pred_proba.tolist(),
                    'confidence': float(nn_pred_proba[nn_pred_class])
                }

            # Ensemble prediction (weighted average)
            if 'xgboost' in predictions and 'neural_net' in predictions:
                # Weights: XGBoost 60%, Neural Net 40%
                xgb_probs = np.array(predictions['xgboost']['probabilities'])
                nn_probs = np.array(predictions['neural_net']['probabilities'])

                ensemble_probs = 0.6 * xgb_probs + 0.4 * nn_probs
                ensemble_class = np.argmax(ensemble_probs)
                ensemble_confidence = ensemble_probs[ensemble_class]

                final_prediction = {
                    'class': int(ensemble_class),
                    'probabilities': ensemble_probs.tolist(),
                    'confidence': float(ensemble_confidence)
                }

                logger.info(f"Ensemble prediction: class={ensemble_class}, confidence={ensemble_confidence:.3f}")

            elif 'xgboost' in predictions:
                final_prediction = predictions['xgboost']
                logger.info("Using XGBoost only")

            elif 'neural_net' in predictions:
                final_prediction = predictions['neural_net']
                logger.info("Using Neural Net only")

            else:
                # No models available - return HOLD
                final_prediction = {
                    'class': 0,
                    'probabilities': [1.0, 0.0, 0.0],
                    'confidence': 0.0
                }
                logger.warning("No models available, returning HOLD")

            # Convert class to direction string
            class_mapping = {0: 'HOLD', 1: 'LONG', 2: 'SHORT'}
            direction = class_mapping.get(final_prediction['class'], 'HOLD')

            # Apply confidence threshold for risk management
            min_confidence = 0.55  # 55% minimum confidence
            if final_prediction['confidence'] < min_confidence:
                direction = 'HOLD'
                logger.info(f"Low confidence ({final_prediction['confidence']:.3f}), switching to HOLD")

            # Prepare response for Java
            response = {
                'symbol': symbol,
                'direction': direction,
                'predictedSignal': direction,  # Java expects this field
                'confidence': final_prediction['confidence'],
                'probabilities': {
                    'HOLD': final_prediction['probabilities'][0],
                    'LONG': final_prediction['probabilities'][1],
                    'SHORT': final_prediction['probabilities'][2]
                },
                'model_predictions': predictions,
                'features_used': len(self.expected_features),
                'timestamp': java_data.get('timestamp', 0)
            }

            logger.info(f"Final prediction for {symbol}: {direction} ({final_prediction['confidence']:.3f} confidence)")

            return response

        except Exception as e:
            logger.error(f"Prediction failed: {e}")
            import traceback
            traceback.print_exc()

            # Return safe fallback
            return {
                'symbol': java_data.get('symbol', 'UNKNOWN'),
                'direction': 'HOLD',
                'predictedSignal': 'HOLD',
                'confidence': 0.0,
                'probabilities': {'HOLD': 1.0, 'LONG': 0.0, 'SHORT': 0.0},
                'error': str(e)
            }

# Flask API endpoints for Java integration

ml_service = JavaMLPredictionService()

@app.route('/predict', methods=['POST'])
def predict_endpoint():
    """
    Main prediction endpoint called by Java PythonMLService
    """
    try:
        # Get JSON data from Java
        java_data = request.get_json()

        if not java_data:
            return jsonify({
                'error': 'No data received',
                'direction': 'HOLD',
                'predictedSignal': 'HOLD',
                'confidence': 0.0
            }), 400

        # Make prediction
        prediction = ml_service.predict_direction(java_data)

        return jsonify(prediction)

    except Exception as e:
        logger.error(f"API prediction failed: {e}")
        return jsonify({
            'error': str(e),
            'direction': 'HOLD',
            'predictedSignal': 'HOLD',
            'confidence': 0.0
        }), 500

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    return jsonify({
        'status': 'healthy',
        'models_loaded': list(ml_service.models.keys()),
        'scaler_loaded': ml_service.scaler is not None,
        'expected_features': len(ml_service.expected_features)
    })

@app.route('/reload', methods=['POST'])
def reload_models():
    """Reload models endpoint"""
    success = ml_service.load_models()
    return jsonify({
        'success': success,
        'models_loaded': list(ml_service.models.keys())
    })

# Standalone usage for testing
def test_prediction():
    """Test the prediction service"""

    # Sample data similar to what Java would send
    test_data = {
        'symbol': 'BTCUSDT',
        'currentPrice': 45000.0,
        'timestamp': 1640995200000,

        # Price ratios
        'price_vs_ema20_4h': 1.02,
        'price_vs_ema50_4h': 1.05,
        'price_vs_ema200_daily': 1.15,
        'ema20_vs_ema50_4h': 1.01,
        'ema10_vs_ema20_1h': 1.005,

        # Trend scores
        'primaryTrendScore': 1.0,
        'shortTermTrendScore': 0.5,
        'trendAlignment': 1.0,
        'trendStrength': 0.8,

        # Momentum
        'rsi': 55.0,
        'rsiNormalized': 0.1,
        'macdLine': 100.0,
        'macdSignal': 95.0,
        'macdHistogram': 5.0,
        'macdDivergence': 5.0,
        'rsiOverboughtScore': 0.0,
        'rsiOversoldScore': 0.0,
        'rsiMomentumScore': 0.8,

        # Volume
        'volumeRatio': 1.5,
        'volumeRatioLog': 0.4,
        'volumeStrengthScore': 0.6,
        'volumeTrendScore': 1.0,

        # Risk
        'atrPercent': 3.5,
        'shortTermVolatility': 4.0,
        'volatilityRatio': 1.1,
        'distanceFromSupport': 2.0,
        'distanceFromResistance': 3.0,
        'riskRewardRatio': 1.5,
        'riskRewardScore': 1.5,

        # Structure
        'structureBullishScore': 0.8,
        'structureBearishScore': 0.2,
        'consolidationScore': 0.0,

        # Composite
        'trendMomentumScore': 0.7,
        'volumePriceScore': 0.6,
        'riskAdjustedScore': 0.65
    }

    service = JavaMLPredictionService()
    prediction = service.predict_direction(test_data)

    print("=== TEST PREDICTION ===")
    print(f"Symbol: {prediction['symbol']}")
    print(f"Direction: {prediction['direction']}")
    print(f"Confidence: {prediction['confidence']:.3f}")
    print(f"Probabilities: {prediction['probabilities']}")

    return prediction

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "test":
        test_prediction()
    else:
        # Start Flask server for Java integration
        logger.info("Starting ML Prediction Service for Java integration")
        app.run(host='127.0.0.1', port=5000, debug=False)