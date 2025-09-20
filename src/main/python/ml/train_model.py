# ml_predictor.py
import argparse
import json
import numpy as np
import pickle
import sys
from pathlib import Path
import logging
import warnings
warnings.filterwarnings('ignore')

# ML libraries
import tensorflow as tf
import xgboost as xgb
import lightgbm as lgb
from sklearn.ensemble import RandomForestClassifier

# Suppress TensorFlow warnings
tf.get_logger().setLevel('ERROR')

# Set up logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class TradingMLPredictor:
    def __init__(self, model_name="swing_trader", model_path="/app/models"):
        self.model_name = model_name
        self.model_path = Path(model_path)
        self.models = {}
        self.scaler = None
        self.feature_columns = []
        self.signal_mapping = {'LONG': 1, 'SHORT': -1, 'NO_TRADE': 0}
        self.reverse_signal_mapping = {v: k for k, v in self.signal_mapping.items()}
        self.model_info = {}

        self.load_models()

    def load_models(self):
        """Load all trained models and preprocessing objects"""
        try:
            # Load model info
            info_file = self.model_path / f"{self.model_name}_info.json"
            if info_file.exists():
                with open(info_file, 'r') as f:
                    self.model_info = json.load(f)
                    self.feature_columns = self.model_info.get('feature_columns', [])
                    if 'signal_mapping' in self.model_info:
                        self.signal_mapping = self.model_info['signal_mapping']
                        self.reverse_signal_mapping = {v: k for k, v in self.signal_mapping.items()}
                logger.info(f"Loaded model info with {len(self.feature_columns)} features")
            else:
                logger.warning(f"Model info not found: {info_file}")

            # Load scaler
            scaler_file = self.model_path / f"{self.model_name}_scaler.pkl"
            if scaler_file.exists():
                with open(scaler_file, 'rb') as f:
                    self.scaler = pickle.load(f)
                logger.info("Loaded feature scaler")
            else:
                logger.error(f"Scaler not found: {scaler_file}")
                return False

            # Load individual models
            model_files = {
                'xgb_profitability': f"{self.model_name}_xgb_profitability.pkl",
                'xgb_signal': f"{self.model_name}_xgb_signal.pkl",
                'xgb_return': f"{self.model_name}_xgb_return.pkl",
                'lgb_profitability': f"{self.model_name}_lgb_profitability.pkl",
                'random_forest': f"{self.model_name}_random_forest.pkl",
                'neural_profitability': f"{self.model_name}_neural_profitability.h5",
                'neural_return': f"{self.model_name}_neural_return.h5"
            }

            for model_name, filename in model_files.items():
                model_file = self.model_path / filename

                if model_file.exists():
                    try:
                        if filename.endswith('.h5'):
                            # Load TensorFlow model
                            self.models[model_name] = tf.keras.models.load_model(model_file, compile=False)
                        else:
                            # Load pickle model
                            with open(model_file, 'rb') as f:
                                self.models[model_name] = pickle.load(f)

                        logger.info(f"Loaded {model_name}")
                    except Exception as e:
                        logger.warning(f"Failed to load {model_name}: {e}")
                else:
                    logger.warning(f"Model file not found: {model_file}")

            if not self.models:
                logger.error("No models loaded successfully")
                return False

            logger.info(f"Successfully loaded {len(self.models)} models")
            return True

        except Exception as e:
            logger.error(f"Failed to load models: {e}")
            return False

    def prepare_features(self, data):
        """Prepare features from input data"""
        try:
            # Convert to numpy array format expected by models
            if isinstance(data, dict):
                # Extract features in correct order
                features = []
                missing_features = []

                for feature in self.feature_columns:
                    if feature in data:
                        value = data[feature]
                        # Handle potential None/null values
                        if value is None:
                            value = 0.0
                        features.append(float(value))
                    else:
                        features.append(0.0)  # Default value for missing features
                        missing_features.append(feature)

                if missing_features:
                    logger.warning(f"Missing features (using 0.0): {missing_features[:5]}...")

                X = np.array([features])

            else:
                # Assume it's already a proper array/list
                X = np.array(data).reshape(1, -1)

            # Validate feature count
            if X.shape[1] != len(self.feature_columns):
                logger.warning(f"Feature count mismatch: got {X.shape[1]}, expected {len(self.feature_columns)}")

                # Pad or truncate to match expected features
                if X.shape[1] < len(self.feature_columns):
                    padding = np.zeros((X.shape[0], len(self.feature_columns) - X.shape[1]))
                    X = np.hstack([X, padding])
                else:
                    X = X[:, :len(self.feature_columns)]

            # Apply feature scaling
            if self.scaler is not None:
                X_scaled = self.scaler.transform(X)
            else:
                X_scaled = X
                logger.warning("No scaler available, using raw features")

            # Handle any remaining NaN/inf values
            X_scaled = np.nan_to_num(X_scaled, nan=0.0, posinf=1.0, neginf=-1.0)

            return X_scaled

        except Exception as e:
            logger.error(f"Feature preparation failed: {e}")
            return None

    def predict(self, data):
        """Make ensemble prediction from input data"""
        try:
            # Prepare features
            X = self.prepare_features(data)
            if X is None:
                return self.create_error_response("Feature preparation failed")

            # Collect predictions from all models
            predictions = {}
            confidences = {}

            # Binary profitability predictions
            profitability_preds = []
            profitability_names = ['xgb_profitability', 'lgb_profitability', 'random_forest', 'neural_profitability']

            for model_name in profitability_names:
                if model_name in self.models:
                    try:
                        model = self.models[model_name]

                        if 'neural' in model_name:
                            # Neural network prediction
                            pred = model.predict(X, verbose=0)[0][0]
                        elif hasattr(model, 'predict_proba'):
                            # Classifier with probability
                            pred = model.predict_proba(X)[0][1]
                        else:
                            # Simple prediction
                            pred = model.predict(X)[0]

                        profitability_preds.append(float(pred))
                        predictions[model_name] = float(pred)

                    except Exception as e:
                        logger.warning(f"Prediction failed for {model_name}: {e}")

            # Return predictions
            return_preds = []
            return_names = ['xgb_return', 'neural_return']

            for model_name in return_names:
                if model_name in self.models:
                    try:
                        model = self.models[model_name]

                        if 'neural' in model_name:
                            pred = model.predict(X, verbose=0)[0][0]
                        else:
                            pred = model.predict(X)[0]

                        return_preds.append(float(pred))
                        predictions[model_name] = float(pred)

                    except Exception as e:
                        logger.warning(f"Return prediction failed for {model_name}: {e}")

            # Signal classification (if available)
            signal_pred = None
            if 'xgb_signal' in self.models:
                try:
                    signal_raw = self.models['xgb_signal'].predict(X)[0]
                    # Convert from 0,1,2 back to LONG,NO_TRADE,SHORT
                    signal_mapping_inv = {0: 'SHORT', 1: 'NO_TRADE', 2: 'LONG'}
                    signal_pred = signal_mapping_inv.get(signal_raw, 'NO_TRADE')
                    predictions['signal_classification'] = signal_pred
                except Exception as e:
                    logger.warning(f"Signal prediction failed: {e}")

            # Ensemble decision making
            if not profitability_preds and not return_preds:
                return self.create_error_response("No valid predictions obtained")

            # Calculate ensemble confidence
            if profitability_preds:
                avg_profitability = np.mean(profitability_preds)
                profitability_confidence = avg_profitability
            else:
                avg_profitability = 0.5
                profitability_confidence = 0.5

            if return_preds:
                avg_return = np.mean(return_preds)
                return_std = np.std(return_preds) if len(return_preds) > 1 else 0.1
            else:
                avg_return = 0.0
                return_std = 0.1

            # Decision logic for trading signal
            final_signal = 'NO_TRADE'
            final_confidence = 0.5

            # High confidence thresholds
            HIGH_CONFIDENCE_THRESHOLD = 0.7
            MEDIUM_CONFIDENCE_THRESHOLD = 0.6

            if signal_pred and signal_pred != 'NO_TRADE':
                # Use signal classifier if available and confident
                if profitability_confidence > MEDIUM_CONFIDENCE_THRESHOLD:
                    final_signal = signal_pred
                    final_confidence = profitability_confidence
            else:
                # Use return-based decision
                if avg_return > 1.0 and profitability_confidence > HIGH_CONFIDENCE_THRESHOLD:
                    final_signal = 'LONG'
                    final_confidence = min(profitability_confidence, (avg_return / 5.0))  # Cap at reasonable level
                elif avg_return < -1.0 and profitability_confidence > HIGH_CONFIDENCE_THRESHOLD:
                    final_signal = 'SHORT'
                    final_confidence = min(profitability_confidence, (abs(avg_return) / 5.0))
                elif profitability_confidence > 0.8 and abs(avg_return) > 0.5:
                    # Medium confidence trades
                    final_signal = 'LONG' if avg_return > 0 else 'SHORT'
                    final_confidence = profitability_confidence * 0.8

            # Apply additional safety filters
            if final_confidence < 0.55:  # Below reasonable threshold
                final_signal = 'NO_TRADE'
                final_confidence = 0.5

            # Cap confidence to reasonable range
            final_confidence = max(0.5, min(0.95, final_confidence))

            # Create response
            response = {
                'success': True,
                'predicted_signal': final_signal,
                'confidence': float(final_confidence),
                'expected_return': float(avg_return),
                'profitability_score': float(avg_profitability),
                'model_predictions': predictions,
                'ensemble_info': {
                    'profitability_models': len(profitability_preds),
                    'return_models': len(return_preds),
                    'total_models': len(predictions)
                },
                'metadata': {
                    'model_name': self.model_name,
                    'feature_count': len(self.feature_columns),
                    'timestamp': str(np.datetime64('now'))
                }
            }

            logger.info(f"Prediction: {final_signal} (confidence: {final_confidence:.3f}, return: {avg_return:.2f})")
            return response

        except Exception as e:
            logger.error(f"Prediction failed: {e}")
            return self.create_error_response(f"Prediction error: {str(e)}")

    def create_error_response(self, error_msg):
        """Create standardized error response"""
        return {
            'success': False,
            'predicted_signal': 'NO_TRADE',
            'confidence': 0.0,
            'expected_return': 0.0,
            'error': error_msg,
            'metadata': {
                'model_name': self.model_name,
                'timestamp': str(np.datetime64('now'))
            }
        }

    def get_model_info(self):
        """Return information about loaded models"""
        return {
            'model_name': self.model_name,
            'loaded_models': list(self.models.keys()),
            'feature_count': len(self.feature_columns),
            'features': self.feature_columns,
            'has_scaler': self.scaler is not None,
            'model_info': self.model_info
        }

def main():
    parser = argparse.ArgumentParser(description='ML Trading Signal Predictor')
    parser.add_argument('--model-name', default='swing_trader', help='Model name prefix')
    parser.add_argument('--model-path', default='/app/models', help='Path to model files')
    parser.add_argument('--input-data', help='JSON string with input data')
    parser.add_argument('--input-file', help='Path to JSON file with input data')
    parser.add_argument('--info', action='store_true', help='Show model info only')

    args = parser.parse_args()

    # Initialize predictor
    predictor = TradingMLPredictor(args.model_name, args.model_path)

    if args.info:
        # Return model information
        info = predictor.get_model_info()
        print(json.dumps(info, indent=2))
        return

    # Get input data
    input_data = None

    if args.input_data:
        try:
            input_data = json.loads(args.input_data)
        except json.JSONDecodeError as e:
            print(json.dumps({'success': False, 'error': f'Invalid JSON: {e}'}))
            sys.exit(1)
    elif args.input_file:
        try:
            with open(args.input_file, 'r') as f:
                input_data = json.load(f)
        except Exception as e:
            print(json.dumps({'success': False, 'error': f'File error: {e}'}))
            sys.exit(1)
    else:
        # Read from stdin
        try:
            input_data = json.loads(sys.stdin.read())
        except json.JSONDecodeError as e:
            print(json.dumps({'success': False, 'error': f'Invalid JSON from stdin: {e}'}))
            sys.exit(1)

    if not input_data:
        print(json.dumps({'success': False, 'error': 'No input data provided'}))
        sys.exit(1)

    # Make prediction
    result = predictor.predict(input_data)
    print(json.dumps(result, indent=2))

    sys.exit(0 if result.get('success', False) else 1)

if __name__ == "__main__":
    main()