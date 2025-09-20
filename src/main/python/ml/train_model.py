# train_model.py
import argparse
import json
import pandas as pd
import numpy as np
import pickle
from pathlib import Path
from datetime import datetime
import logging
import warnings
warnings.filterwarnings('ignore')

# ML libraries
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
from sklearn.preprocessing import StandardScaler, LabelEncoder
from sklearn.model_selection import train_test_split, StratifiedKFold
from sklearn.ensemble import RandomForestClassifier, GradientBoostingRegressor
from sklearn.metrics import classification_report, mean_squared_error, accuracy_score, roc_auc_score
import xgboost as xgb
import lightgbm as lgb

# Set up logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class TradingMLTrainer:
    def __init__(self, model_name="swing_trader", model_path="/app/models"):
        self.model_name = model_name
        self.model_path = Path(model_path)
        self.model_path.mkdir(parents=True, exist_ok=True)

        self.feature_columns = [
            'tradingRule', 'algoScore', 'rsi', 'macd', 'macd_signal', 'macd_histogram',
            'bollinger_upper', 'bollinger_lower', 'bollinger_percent',
            'sma_20', 'sma_50', 'ema_12', 'ema_26', 'ema_50', 'ema_200',
            'price_change_1h', 'price_change_4h', 'price_change_1d',
            'volatility_1h', 'volatility_4h', 'volatility_1d',
            'volume_ratio', 'atr', 'adx', 'market_trend', 'market_volatility',
            'hour_of_day', 'day_of_week'
        ]

    def load_training_data(self, data_path):
        """Load and prepare training data from JSON files"""
        logger.info(f"Loading training data from {data_path}")

        data_files = list(Path(data_path).glob("training_data_*.json"))
        all_data = []

        for file in data_files:
            try:
                with open(file, 'r') as f:
                    file_data = json.load(f)
                    all_data.extend(file_data)
                logger.info(f"Loaded {len(file_data)} samples from {file.name}")
            except Exception as e:
                logger.warning(f"Failed to load {file}: {e}")
                continue

        if not all_data:
            raise ValueError("No training data found")

        df = pd.DataFrame(all_data)
        logger.info(f"Total loaded samples: {len(df)}")

        return self.preprocess_data(df)

    def preprocess_data(self, df):
        """Preprocess the training data"""
        logger.info("Preprocessing training data...")

        # Convert timestamps
        df['timestamp'] = pd.to_datetime(df['timestamp'], errors='coerce')
        df['hour_of_day'] = df['timestamp'].dt.hour
        df['day_of_week'] = df['timestamp'].dt.dayofweek

        # Handle missing values for feature columns
        for col in self.feature_columns:
            if col not in df.columns:
                df[col] = 0.0
            df[col] = pd.to_numeric(df[col], errors='coerce').fillna(0.0)

        # Encode categorical variables if they exist
        if 'side' in df.columns:
            le_signal = LabelEncoder()
            df['side_encoded'] = le_signal.fit_transform(df['side'].astype(str))

        # Create target variables
        df['pnlPercent'] = pd.to_numeric(df['pnlPercent'], errors='coerce').fillna(0.0)
        df['is_profitable'] = (df['pnlPercent'] > 0).astype(int)

        # Create profit categories for additional analysis
        df['profit_category'] = pd.cut(df['pnlPercent'],
                                       bins=[-100, -5, -2, 2, 5, 100],
                                       labels=['terrible', 'bad', 'neutral', 'good', 'excellent'])

        # Filter extreme outliers
        q99 = df['pnlPercent'].quantile(0.99)
        q01 = df['pnlPercent'].quantile(0.01)
        df = df[(df['pnlPercent'] >= q01) & (df['pnlPercent'] <= q99)]

        # Remove rows with too many missing features
        df = df.dropna(subset=['pnlPercent', 'tradingRule', 'algoScore'])

        logger.info(f"Preprocessed data shape: {df.shape}")
        logger.info(f"Profitable trades: {df['is_profitable'].sum()} ({df['is_profitable'].mean()*100:.1f}%)")
        logger.info(f"Average PnL: {df['pnlPercent'].mean():.2f}%")

        return df

    def create_neural_network(self, input_dim, task='classification'):
        """Create a neural network for trading prediction"""
        model = keras.Sequential([
            layers.Dense(256, activation='relu', input_shape=(input_dim,)),
            layers.BatchNormalization(),
            layers.Dropout(0.3),

            layers.Dense(128, activation='relu'),
            layers.BatchNormalization(),
            layers.Dropout(0.3),

            layers.Dense(64, activation='relu'),
            layers.BatchNormalization(),
            layers.Dropout(0.2),

            layers.Dense(32, activation='relu'),
            layers.Dropout(0.2),
        ])

        if task == 'classification':
            model.add(layers.Dense(1, activation='sigmoid'))
            model.compile(
                optimizer=keras.optimizers.Adam(learning_rate=0.001),
                loss='binary_crossentropy',
                metrics=['accuracy', 'precision', 'recall']
            )
        else:
            model.add(layers.Dense(1, activation='linear'))
            model.compile(
                optimizer=keras.optimizers.Adam(learning_rate=0.001),
                loss='mse',
                metrics=['mae']
            )

        return model

    def train_ensemble_model(self, X_train, y_train, X_val, y_val):
        """Train ensemble model combining multiple algorithms"""
        models = {}

        # 1. XGBoost Classifier for profitability prediction
        logger.info("Training XGBoost classifier...")
        try:
            xgb_clf = xgb.XGBClassifier(
                n_estimators=200,
                max_depth=6,
                learning_rate=0.1,
                subsample=0.8,
                colsample_bytree=0.8,
                random_state=42,
                eval_metric='logloss'
            )
            xgb_clf.fit(X_train, (y_train > 0).astype(int))
            models['xgb_classifier'] = xgb_clf
            logger.info("XGBoost classifier trained successfully")
        except Exception as e:
            logger.error(f"XGBoost classifier training failed: {e}")

        # 2. XGBoost Regressor for return prediction
        logger.info("Training XGBoost regressor...")
        try:
            xgb_reg = xgb.XGBRegressor(
                n_estimators=200,
                max_depth=6,
                learning_rate=0.1,
                subsample=0.8,
                colsample_bytree=0.8,
                random_state=42
            )
            xgb_reg.fit(X_train, y_train)
            models['xgb_regressor'] = xgb_reg
            logger.info("XGBoost regressor trained successfully")
        except Exception as e:
            logger.error(f"XGBoost regressor training failed: {e}")

        # 3. LightGBM for additional ensemble diversity
        logger.info("Training LightGBM classifier...")
        try:
            lgb_clf = lgb.LGBMClassifier(
                n_estimators=150,
                max_depth=6,
                learning_rate=0.1,
                subsample=0.8,
                colsample_bytree=0.8,
                random_state=42,
                verbose=-1
            )
            lgb_clf.fit(X_train, (y_train > 0).astype(int))
            models['lgb_classifier'] = lgb_clf
            logger.info("LightGBM classifier trained successfully")
        except Exception as e:
            logger.error(f"LightGBM classifier training failed: {e}")

        # 4. Random Forest for feature importance and robustness
        logger.info("Training Random Forest...")
        try:
            rf = RandomForestClassifier(
                n_estimators=100,
                max_depth=10,
                min_samples_split=5,
                min_samples_leaf=2,
                random_state=42,
                n_jobs=-1
            )
            rf.fit(X_train, (y_train > 0).astype(int))
            models['random_forest'] = rf
            logger.info("Random Forest trained successfully")
        except Exception as e:
            logger.error(f"Random Forest training failed: {e}")

        # 5. Neural Networks
        logger.info("Training Neural Networks...")
        try:
            # Classification network
            nn_clf = self.create_neural_network(X_train.shape[1], 'classification')

            # Callbacks
            early_stopping = keras.callbacks.EarlyStopping(
                monitor='val_loss', patience=15, restore_best_weights=True, verbose=0
            )
            reduce_lr = keras.callbacks.ReduceLROnPlateau(
                monitor='val_loss', factor=0.5, patience=8, min_lr=1e-6, verbose=0
            )

            # Train classifier
            nn_clf.fit(X_train, (y_train > 0).astype(int),
                       validation_data=(X_val, (y_val > 0).astype(int)),
                       epochs=100, batch_size=64, verbose=0,
                       callbacks=[early_stopping, reduce_lr])
            models['neural_net_classifier'] = nn_clf

            # Regression network
            nn_reg = self.create_neural_network(X_train.shape[1], 'regression')
            nn_reg.fit(X_train, y_train,
                       validation_data=(X_val, y_val),
                       epochs=100, batch_size=64, verbose=0,
                       callbacks=[early_stopping, reduce_lr])
            models['neural_net_regressor'] = nn_reg

            logger.info("Neural Networks trained successfully")
        except Exception as e:
            logger.error(f"Neural Network training failed: {e}")

        logger.info(f"Successfully trained {len(models)} models")
        return models

    def evaluate_models(self, models, X_test, y_test):
        """Evaluate all models and return comprehensive metrics"""
        results = {}
        y_binary = (y_test > 0).astype(int)

        for name, model in models.items():
            logger.info(f"Evaluating {name}...")

            try:
                if 'classifier' in name:
                    # Classification metrics
                    if hasattr(model, 'predict_proba'):
                        y_pred_proba = model.predict_proba(X_test)
                        if y_pred_proba.shape[1] > 1:
                            y_pred_proba = y_pred_proba[:, 1]
                        else:
                            y_pred_proba = y_pred_proba.flatten()
                    else:
                        y_pred_proba = model.predict(X_test).flatten()

                    y_pred = (y_pred_proba > 0.5).astype(int)

                    accuracy = accuracy_score(y_binary, y_pred)
                    try:
                        auc_score = roc_auc_score(y_binary, y_pred_proba)
                    except:
                        auc_score = 0.5

                    # Trading metrics
                    predicted_trades = X_test[y_pred == 1]
                    actual_profits = y_test[y_pred == 1]

                    if len(actual_profits) > 0:
                        avg_profit = actual_profits.mean()
                        win_rate = (actual_profits > 0).mean()
                        total_profit = actual_profits.sum()
                    else:
                        avg_profit = win_rate = total_profit = 0

                    results[name] = {
                        'accuracy': float(accuracy),
                        'auc_score': float(auc_score),
                        'avg_predicted_profit': float(avg_profit),
                        'predicted_win_rate': float(win_rate),
                        'total_predicted_profit': float(total_profit),
                        'total_predictions': int(np.sum(y_pred)),
                        'type': 'classification'
                    }

                else:  # Regression models
                    y_pred = model.predict(X_test)
                    if hasattr(y_pred, 'flatten'):
                        y_pred = y_pred.flatten()

                    mse = mean_squared_error(y_test, y_pred)
                    mae = np.mean(np.abs(y_test - y_pred))

                    # Directional accuracy
                    direction_accuracy = np.mean((y_test > 0) == (y_pred > 0))

                    # Correlation
                    correlation = np.corrcoef(y_test, y_pred)[0, 1] if len(y_test) > 1 else 0

                    results[name] = {
                        'mse': float(mse),
                        'mae': float(mae),
                        'rmse': float(np.sqrt(mse)),
                        'direction_accuracy': float(direction_accuracy),
                        'correlation': float(correlation),
                        'type': 'regression'
                    }

            except Exception as e:
                logger.error(f"Evaluation failed for {name}: {e}")
                results[name] = {'error': str(e)}

        return results

    def save_models(self, models, scaler, feature_columns, evaluation_results):
        """Save all trained models and preprocessing objects"""
        model_info = {
            'model_name': self.model_name,
            'created_at': datetime.now().isoformat(),
            'feature_columns': feature_columns,
            'model_types': list(models.keys()),
            'evaluation_results': evaluation_results,
            'training_config': {
                'ensemble_models': len(models),
                'feature_count': len(feature_columns),
                'framework': 'sklearn + tensorflow + xgboost + lightgbm'
            }
        }

        # Save individual models
        for name, model in models.items():
            model_file = self.model_path / f"{self.model_name}_{name}"

            try:
                if 'neural' in name:
                    model.save(f"{model_file}.h5")
                else:
                    with open(f"{model_file}.pkl", 'wb') as f:
                        pickle.dump(model, f)

                logger.info(f"Saved {name} to {model_file}")
            except Exception as e:
                logger.error(f"Failed to save {name}: {e}")

        # Save scaler
        scaler_file = self.model_path / f"{self.model_name}_scaler.pkl"
        try:
            with open(scaler_file, 'wb') as f:
                pickle.dump(scaler, f)
            logger.info(f"Saved scaler to {scaler_file}")
        except Exception as e:
            logger.error(f"Failed to save scaler: {e}")

        # Save model info
        info_file = self.model_path / f"{self.model_name}_info.json"
        try:
            with open(info_file, 'w') as f:
                json.dump(model_info, f, indent=2)
            logger.info(f"Saved model info to {info_file}")
        except Exception as e:
            logger.error(f"Failed to save model info: {e}")

        logger.info(f"All models saved to {self.model_path}")
        return model_info

    def train(self, data_path):
        """Main training pipeline"""
        try:
            start_time = datetime.now()

            # Load and preprocess data
            df = self.load_training_data(data_path)

            if len(df) < 100:
                raise ValueError(f"Insufficient training data: {len(df)} samples")

            # Prepare features and targets
            available_features = [col for col in self.feature_columns if col in df.columns]
            logger.info(f"Using {len(available_features)} features: {available_features}")

            X = df[available_features].values
            y = df['pnlPercent'].values

            # Check for valid data
            if np.isnan(X).all() or np.isnan(y).all():
                raise ValueError("All features or targets are NaN")

            # Scale features
            scaler = StandardScaler()
            X_scaled = scaler.fit_transform(X)

            # Split data strategically
            X_train, X_temp, y_train, y_temp = train_test_split(
                X_scaled, y, test_size=0.4, random_state=42,
                stratify=(y > 0) if len(np.unique(y > 0)) > 1 else None
            )
            X_val, X_test, y_val, y_test = train_test_split(
                X_temp, y_temp, test_size=0.5, random_state=42,
                stratify=(y_temp > 0) if len(np.unique(y_temp > 0)) > 1 else None
            )

            logger.info(f"Data split - Train: {X_train.shape[0]}, Val: {X_val.shape[0]}, Test: {X_test.shape[0]}")

            # Train ensemble models
            models = self.train_ensemble_model(X_train, y_train, X_val, y_val)

            if not models:
                raise ValueError("No models were successfully trained")

            # Evaluate models
            results = self.evaluate_models(models, X_test, y_test)

            # Save everything
            model_info = self.save_models(models, scaler, available_features, results)

            training_time = (datetime.now() - start_time).total_seconds()

            # Final results
            final_results = {
                'success': True,
                'training_time_seconds': training_time,
                'models_trained': len(models),
                'training_samples': len(df),
                'test_samples': len(y_test),
                'feature_count': len(available_features),
                'model_performance': results,
                'data_summary': {
                    'total_trades': len(df),
                    'profitable_trades': int((df['pnlPercent'] > 0).sum()),
                    'win_rate': float((df['pnlPercent'] > 0).mean()),
                    'avg_return': float(df['pnlPercent'].mean()),
                    'std_return': float(df['pnlPercent'].std())
                }
            }

            logger.info("="*50)
            logger.info("TRAINING COMPLETED SUCCESSFULLY!")
            logger.info(f"Training time: {training_time:.1f}s")
            logger.info(f"Models trained: {len(models)}")
            logger.info(f"Training samples: {len(df)}")

            # Print model performance summary
            for name, metrics in results.items():
                if 'error' not in metrics:
                    if metrics['type'] == 'classification':
                        logger.info(f"{name}: Accuracy={metrics.get('accuracy', 0):.3f}, AUC={metrics.get('auc_score', 0):.3f}")
                    else:
                        logger.info(f"{name}: RMSE={metrics.get('rmse', 0):.3f}, Direction Acc={metrics.get('direction_accuracy', 0):.3f}")

            logger.info("="*50)

            return final_results

        except Exception as e:
            logger.error(f"Training failed: {e}")
            return {
                'success': False,
                'error': str(e),
                'training_time_seconds': 0,
                'models_trained': 0
            }

def main():
    parser = argparse.ArgumentParser(description='Train trading ML model')
    parser.add_argument('--model-name', default='swing_trader', help='Model name')
    parser.add_argument('--data-path', required=True, help='Path to training data')
    parser.add_argument('--model-path', default='/app/models', help='Path to save models')

    args = parser.parse_args()

    try:
        trainer = TradingMLTrainer(args.model_name, args.model_path)
        results = trainer.train(args.data_path)

        print(json.dumps(results, indent=2))

        if results.get('success', False):
            exit(0)
        else:
            exit(1)

    except Exception as e:
        logger.error(f"Training script failed: {e}")
        print(json.dumps({"success": False, "error": str(e)}, indent=2))
        exit(1)

if __name__ == "__main__":
    main()