# train_model.py
import argparse
import json
import pandas as pd
import numpy as np
import pickle
from pathlib import Path
from datetime import datetime
import logging

# ML libraries
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
from sklearn.preprocessing import StandardScaler, LabelEncoder
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier, GradientBoostingRegressor
from sklearn.metrics import classification_report, mean_squared_error, accuracy_score
import xgboost as xgb

# Set up logging
logging.basicConfig(level=logging.INFO)
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
            with open(file, 'r') as f:
                file_data = json.load(f)
                all_data.extend(file_data)

        if not all_data:
            raise ValueError("No training data found")

        df = pd.DataFrame(all_data)
        logger.info(f"Loaded {len(df)} training samples")

        return self.preprocess_data(df)

    def preprocess_data(self, df):
        """Preprocess the training data"""
        # Convert timestamps
        df['timestamp'] = pd.to_datetime(df['timestamp'])
        df['hour_of_day'] = df['timestamp'].dt.hour
        df['day_of_week'] = df['timestamp'].dt.dayofweek

        # Handle missing values
        for col in self.feature_columns:
            if col not in df.columns:
                df[col] = 0.0
            df[col] = df[col].fillna(0.0)

        # Encode categorical variables
        le_signal = LabelEncoder()
        df['side_encoded'] = le_signal.fit_transform(df['side'])

        # Create target variables
        df['is_profitable'] = (df['pnlPercent'] > 0).astype(int)
        df['profit_category'] = pd.cut(df['pnlPercent'],
                                       bins=[-100, -5, -2, 2, 5, 100],
                                       labels=['terrible', 'bad', 'neutral', 'good', 'excellent'])

        # Filter outliers
        df = df[(df['pnlPercent'] >= -20) & (df['pnlPercent'] <= 20)]

        logger.info(f"Preprocessed data shape: {df.shape}")
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
            # Binary classification (profitable vs not)
            model.add(layers.Dense(1, activation='sigmoid'))
            model.compile(optimizer='adam',
                          loss='binary_crossentropy',
                          metrics=['accuracy', 'precision', 'recall'])
        else:
            # Regression (predict return %)
            model.add(layers.Dense(1, activation='linear'))
            model.compile(optimizer='adam',
                          loss='mse',
                          metrics=['mae'])

        return model

    def train_ensemble_model(self, X_train, y_train, X_val, y_val):
        """Train ensemble model combining multiple algorithms"""
        models = {}

        # 1. XGBoost Classifier for profitability
        logger.info("Training XGBoost classifier...")
        xgb_clf = xgb.XGBClassifier(
            n_estimators=200,
            max_depth=6,
            learning_rate=0.1,
            subsample=0.8,
            colsample_bytree=0.8,
            random_state=42
        )
        xgb_clf.fit(X_train, (y_train > 0).astype(int))
        models['xgb_classifier'] = xgb_clf

        # 2. XGBoost Regressor for return prediction
        logger.info("Training XGBoost regressor...")
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

        # 3. Random Forest for feature importance
        logger.info("Training Random Forest...")
        rf = RandomForestClassifier(
            n_estimators=100,
            max_depth=10,
            random_state=42
        )
        rf.fit(X_train, (y_train > 0).astype(int))
        models['random_forest'] = rf

        # 4. Neural Network
        logger.info("Training Neural Network...")
        nn_clf = self.create_neural_network(X_train.shape[1], 'classification')
        nn_reg = self.create_neural_network(X_train.shape[1], 'regression')

        # Early stopping
        early_stopping = keras.callbacks.EarlyStopping(
            monitor='val_loss', patience=10, restore_best_weights=True
        )

        nn_clf.fit(X_train, (y_train > 0).astype(int),
                   validation_data=(X_val, (y_val > 0).astype(int)),
                   epochs=100, batch_size=64, verbose=0,
                   callbacks=[early_stopping])

        nn_reg.fit(X_train, y_train,
                   validation_data=(X_val, y_val),
                   epochs=100, batch_size=64, verbose=0,
                   callbacks=[early_stopping])

        models['neural_net_classifier'] = nn_clf
        models['neural_net_regressor'] = nn_reg

        return models

    def evaluate_models(self, models, X_test, y_test):
        """Evaluate all models and return metrics"""
        results = {}

        for name, model in models.items():
            logger.info(f"Evaluating {name}...")

            if 'classifier' in name:
                y_pred = model.predict(X_test)
                if hasattr(model, 'predict_proba'):
                    y_pred_proba = model.predict_proba(X_test)[:, 1]
                else:
                    y_pred_proba = y_pred.flatten()

                accuracy = accuracy_score((y_test > 0).astype(int),
                                          (y_pred_proba > 0.5).astype(int))

                results[name] = {
                    'accuracy': float(accuracy),
                    'type': 'classification'
                }
            else:
                y_pred = model.predict(X_test)
                mse = mean_squared_error(y_test, y_pred)
                mae = np.mean(np.abs(y_test - y_pred))

                results[name] = {
                    'mse': float(mse),
                    'mae': float(mae),
                    'rmse': float(np.sqrt(mse)),
                    'type': 'regression'
                }

        return results

    def save_models(self, models, scaler, feature_columns):
        """Save all trained models and preprocessing objects"""
        model_info = {
            'model_name': self.model_name,
            'created_at': datetime.now().isoformat(),
            'feature_columns': feature_columns,
            'model_types': list(models.keys())
        }

        # Save individual models
        for name, model in models.items():
            if 'neural' in name:
                model.save(self.model_path / f"{self.model_name}_{name}.h5")
            else:
                with open(self.model_path / f"{self.model_name}_{name}.pkl", 'wb') as f:
                    pickle.dump(model, f)

        # Save scaler
        with open(self.model_path / f"{self.model_name}_scaler.pkl", 'wb') as f:
            pickle.dump(scaler, f)

        # Save model info
        with open(self.model_path / f"{self.model_name}_info.json", 'w') as f:
            json.dump(model_info, f, indent=2)

        logger.info(f"Models saved to {self.model_path}")

    def train(self, data_path):
        """Main training pipeline"""
        try:
            # Load and preprocess data
            df = self.load_training_data(data_path)

            # Prepare features and targets
            X = df[self.feature_columns].values
            y = df['pnlPercent'].values

            # Scale features
            scaler = StandardScaler()
            X_scaled = scaler.fit_transform(X)

            # Split data
            X_train, X_temp, y_train, y_temp = train_test_split(
                X_scaled, y, test_size=0.4, random_state=42, stratify=(y > 0)
            )
            X_val, X_test, y_val, y_test = train_test_split(
                X_temp, y_temp, test_size=0.5, random_state=42, stratify=(y_temp > 0)
            )

            logger.info(f"Training set: {X_train.shape}")
            logger.info(f"Validation set: {X_val.shape}")
            logger.info(f"Test set: {X_test.shape}")

            # Train ensemble models
            models = self.train_ensemble_model(X_train, y_train, X_val, y_val)

            # Evaluate models
            results = self.evaluate_models(models, X_test, y_test)

            # Save everything
            self.save_models(models, scaler, self.feature_columns)

            # Print results
            logger.info("Training completed successfully!")
            logger.info("Model Performance:")
            for name, metrics in results.items():
                logger.info(f"  {name}: {metrics}")

            return results

        except Exception as e:
            logger.error(f"Training failed: {e}")
            raise

def main():
    parser = argparse.ArgumentParser(description='Train trading ML model')
    parser.add_argument('--model-name', default='swing_trader', help='Model name')
    parser.add_argument('--data-path', required=True, help='Path to training data')
    parser.add_argument('--model-path', default='/app/models', help='Path to save models')

    args = parser.parse_args()

    trainer = TradingMLTrainer(args.model_name, args.model_path)
    results = trainer.train(args.data_path)

    print(json.dumps(results, indent=2))

if __name__ == "__main__":
    main()