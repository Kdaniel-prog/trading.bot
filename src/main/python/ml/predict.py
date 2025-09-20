# predict.py
import argparse
import json
import numpy as np
import pickle
from pathlib import Path
import tensorflow as tf
from sklearn.preprocessing import StandardScaler
import warnings
warnings.filterwarnings('ignore')
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class TradingMLPredictor:
    def __init__(self, model_name="swing_trader", model_path="/app/models"):
        self.model_name = model_name
        self.model_path = Path(model_path)
        self.models = {}
        self.scaler = None
        self.feature_columns = []
        self.model_info = {}

        self.load_models()

    def load_models(self):
        """Load all trained models and preprocessing objects"""
        logger.info(f"Loading models for {self.model_name} from {self.model_path}")

        try:
            # Load model info
            info_file = self.model_path / f"{self.model_name}_info.json"
            if info_file.exists():
                with open(info_file, 'r') as f:
                    self.model_info = json.load(f)
                    self.feature_columns = self.model_info.get('feature_columns', [])
                logger.info(f"Loaded model info: {len(self.feature_columns)} features")

            # Load scaler
            scaler_file = self.model_path / f"{self.model_name}_scaler.pkl"
            if scaler_file.exists():
                with open(scaler_file, 'rb') as f:
                    self.scaler = pickle.load(f)
                logger.info("Loaded scaler")

            # Load individual models
            model_types = ['xgb_classifier', 'xgb_regressor', 'lgb_classifier',
                           'random_forest', 'neural_net_classifier', 'neural_net_regressor']

            for model_type in model_types:
                pkl_file = self.model_path / f"{self.model_name}_{model_type}.pkl"
                h5_file = self.model_path / f"{self.model_name}_{model_type}.h5"

                try:
                    if 'neural' in model_type and h5_file.exists():
                        self.models[model_type] = tf.keras.models.load_model(str(h5_file))
                        logger.info(f"Loaded {model_type} (Keras)")
                    elif pkl_file.exists():
                        with open(pkl_file, 'rb') as f:
                            self.models[model_type] = pickle.load(f)
                        logger.info(f"Loaded {model_type} (Pickle)")
                except Exception as e:
                    logger.warning(f"Failed to load {model_type}: {e}")

            logger.info(f"Successfully loaded {len(self.models)} models")

        except Exception as e:
            logger.error(f"Failed to load models: {e}")
            raise

    def load_features_from_file(self, features_file):
        """Load features from JSON file (from Java)"""
        try:
            with open(features_file, 'r') as f:
                features_data = json.load(f)

            logger.info(f"Loaded features from {features_file}")
            return features_data
        except Exception as e:
            logger.error(f"Failed to load features: {e}")
            raise

    def prepare_features(self, features_data):
        """Convert features data to ML input format"""
        try:
            # Extract feature values in correct order
            feature_values = []

            for col in self.feature_columns:
                value = 0.0  # default value

                # Direct feature mapping
                if col in features_data:
                    value = float(features_data[col])

                # Handle nested analysis structures from Java
                elif col == 'tradingRule':
                    value = float(features_data.get('tradingRule', 0))
                elif col == 'algoScore':
                    value = float(features_data.get('algoScore', 0))
                elif col == 'rsi':
                    value = float(features_data.get('rsi', 50))
                elif col == 'macd':
                    value = float(features_data.get('macd', 0))
                elif col == 'volume_ratio':
                    value = float(features_data.get('volumeRatio', 1.0))
                elif col == 'sma_20':
                    value = float(features_data.get('sma_20', features_data.get('currentPrice', 0)))
                elif col == 'ema_50':
                    value = float(features_data.get('ema_50', features_data.get('currentPrice', 0)))
                # Add more feature mappings as needed

                feature_values.append(value)

            # Convert to numpy array
            X = np.array(feature_values).reshape(1, -1)

            # Scale features if scaler available
            if self.scaler is not None:
                X = self.scaler.transform(X)

            logger.info(f"Prepared features shape: {X.shape}")
            return X

        except Exception as e:
            logger.error(f"Failed to prepare features: {e}")
            raise

    def predict(self, features_data):
        """Make ensemble prediction"""
        try:
            if not self.models:
                raise ValueError("No models loaded")

            # Prepare input features
            X = self.prepare_features(features_data)

            # Get predictions from all models
            predictions = {}
            confidence_scores = []

            # Classification models - predict profitability
            classifiers = ['xgb_classifier', 'lgb_classifier', 'random_forest', 'neural_net_classifier']
            classification_votes = []
            classification_probas = []

            for clf_name in classifiers:
                if clf_name in self.models:
                    try:
                        model = self.models[clf_name]

                        if hasattr(model, 'predict_proba'):
                            proba = model.predict_proba(X)[0]
                            if len(proba) > 1:
                                prob_positive = proba[1]
                            else:
                                prob_positive = proba[0]
                        elif 'neural' in clf_name:
                            prob_positive = float(model.predict(X)[0][0])
                        else:
                            prob_positive = float(model.predict(X)[0])

                        classification_probas.append(prob_positive)
                        classification_votes.append(1 if prob_positive > 0.5 else 0)
                        predictions[clf_name] = {
                            'probability': float(prob_positive),
                            'prediction': int(prob_positive > 0.5)
                        }

                    except Exception as e:
                        logger.warning(f"Prediction failed for {clf_name}: {e}")

            # Regression models - predict expected return
            regressors = ['xgb_regressor', 'neural_net_regressor']
            regression_predictions = []

            for reg_name in regressors:
                if reg_name in self.models:
                    try:
                        model = self.models[reg_name]

                        if 'neural' in reg_name:
                            pred = float(model.predict(X)[0][0])
                        else:
                            pred = float(model.predict(X)[0])

                        regression_predictions.append(pred)
                        predictions[reg_name] = {'prediction': pred}

                    except Exception as e:
                        logger.warning(f"Prediction failed for {reg_name}: {e}")

            # Ensemble prediction
            if classification_probas:
                avg_probability = np.mean(classification_probas)
                confidence = float(avg_probability)
                majority_vote = int(np.mean(classification_votes) > 0.5)
            else:
                avg_probability = 0.5
                confidence = 0.0
                majority_vote = 0

            if regression_predictions:
                expected_return = float(np.mean(regression_predictions))
            else:
                expected_return = 0.0

            # Determine signal based on ensemble
            if confidence > 0.7 and expected_return > 1.5:
                signal = "LONG"
            elif confidence < 0.3 and expected_return < -1.5:
                signal = "SHORT"
            else:
                signal = "NO_TRADE"

            # Calculate risk score
            volatility_est = abs(expected_return) / 10.0 if expected_return != 0 else 0.5
            risk_score = min(1.0, max(0.0, 1.0 - confidence + volatility_est))

            # Final prediction response
            result = {
                'success': True,
                'signal': signal,
                'confidence': confidence,
                'expected_return': expected_return,
                'risk_score': risk_score,
                'model_version': self.model_info.get('created_at', 'unknown'),
                'individual_predictions': predictions,
                'ensemble_summary': {
                    'classification_models': len(classification_probas),
                    'regression_models': len(regression_predictions),
                    'avg_probability': float(avg_probability),
                    'majority_vote': majority_vote
                }
            }

            logger.info(f"Prediction: {signal}, Confidence: {confidence:.3f}, Expected Return: {expected_return:.2f}%")

            return result

        except Exception as e:
            logger.error(f"Prediction failed: {e}")
            return {
                'success': False,
                'signal': 'NO_TRADE',
                'confidence': 0.0,
                'expected_return': 0.0,
                'risk_score': 1.0,
                'error': str(e)
            }

def main():
    parser = argparse.ArgumentParser(description='Make ML trading prediction')
    parser.add_argument('--model-name', default='swing_trader', help='Model name')
    parser.add_argument('--model-path', default='/app/models', help='Path to model files')
    parser.add_argument('--features-file', required=True, help='JSON file with features')
    parser.add_argument('--symbol', help='Trading symbol (optional)')

    args = parser.parse_args()

    try:
        predictor = TradingMLPredictor(args.model_name, args.model_path)
        features = predictor.load_features_from_file(args.features_file)

        prediction = predictor.predict(features)

        # Output JSON for Java integration
        print(json.dumps(prediction, indent=2))

        if prediction.get('success', False):
            exit(0)
        else:
            exit(1)

    except Exception as e:
        logger.error(f"Prediction script failed: {e}")
        print(json.dumps({
            'success': False,
            'signal': 'NO_TRADE',
            'confidence': 0.0,
            'expected_return': 0.0,
            'error': str(e)
        }, indent=2))
        exit(1)

if __name__ == "__main__":
    main()
