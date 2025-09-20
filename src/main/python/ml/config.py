# config.py
"""
ML Trading System Configuration
"""

import os
from pathlib import Path

class MLConfig:
    # Model configuration
    MODEL_VERSION = "2.0"
    MODEL_TYPES = {
        'xgb_classifier': 'xgboost',
        'xgb_regressor': 'xgboost',
        'neural_net_classifier': 'tensorflow',
        'neural_net_regressor': 'tensorflow',
        'random_forest': 'sklearn'
    }

    # Neural network architecture
    NEURAL_NET_CONFIG = {
        'layers': [256, 128, 64, 32],
        'dropout': 0.3,
        'batch_size': 64,
        'epochs': 100,
        'early_stopping_patience': 15,
        'learning_rate': 0.001
    }

    # XGBoost parameters
    XGBOOST_CONFIG = {
        'classifier': {
            'n_estimators': 1000,
            'max_depth': 6,
            'learning_rate': 0.01,
            'subsample': 0.8,
            'colsample_bytree': 0.8,
            'random_state': 42
        },
        'regressor': {
            'n_estimators': 1000,
            'max_depth': 8,
            'learning_rate': 0.01,
            'subsample': 0.8,
            'colsample_bytree': 0.8,
            'random_state': 42
        }
    }

    # Random Forest parameters
    RANDOM_FOREST_CONFIG = {
        'n_estimators': 500,
        'max_depth': 10,
        'min_samples_split': 5,
        'min_samples_leaf': 2,
        'random_state': 42
    }

    # Feature engineering
    FEATURE_COLUMNS = [
        'currentPrice', 'volume', 'volumeRatio', 'rsi', 'macd',
        'sma_20', 'ema_50', 'tradingRule', 'algoScore',
        'trendStrength', 'momentumScore', 'riskScore',
        'price_change_1h', 'price_change_4h', 'price_change_1d',
        'volume_change_24h', 'supportDistance', 'resistanceDistance'
    ]

    # Target variables
    TARGET_COLUMNS = {
        'classification': 'isWinning',
        'regression': 'pnlPercent'
    }

    # Prediction thresholds
    PREDICTION_THRESHOLDS = {
        'min_confidence': 0.6,
        'min_expected_return': 2.0,
        'max_risk_score': 0.7
    }

    # Ensemble weights
    ENSEMBLE_WEIGHTS = {
        'xgb_classifier': 0.4,
        'neural_net_classifier': 0.35,
        'random_forest': 0.25,
        'xgb_regressor': 0.6,
        'neural_net_regressor': 0.4
    }

    # Paths
    DEFAULT_DATA_PATH = "/app/data/ml"
    DEFAULT_MODEL_PATH = "/app/models"

    @classmethod
    def get_model_file_path(cls, model_name, model_type, base_path="/app/models"):
        """Get full path for model file"""
        extensions = {
            'xgboost': '.pkl',
            'sklearn': '.pkl',
            'tensorflow': '.h5'
        }
        ext = extensions.get(cls.MODEL_TYPES[model_type], '.pkl')
        return Path(base_path) / f"{model_name}_{model_type}{ext}"
