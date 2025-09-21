#!/usr/bin/env python3
"""
ML Model Training Script - First Run Compatible
Uses backtest results to train the trading prediction model
"""

import sys
import json
import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
from sklearn.preprocessing import StandardScaler, LabelEncoder
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, confusion_matrix
import warnings
import os
from pathlib import Path
from datetime import datetime
import joblib

warnings.filterwarnings('ignore')
tf.get_logger().setLevel('ERROR')


class TradingModelTrainer:
    def __init__(self):
        """
        Trading Model Trainer - uses backtest results for supervised learning
        """
        # Paths - save to resources/data
        self.base_dir = Path("src/main/resources/data")
        self.model_dir = self.base_dir / "models"
        self.training_dir = self.base_dir / "training"

        # Create directories
        self.model_dir.mkdir(parents=True, exist_ok=True)
        self.training_dir.mkdir(parents=True, exist_ok=True)

        # Model files
        self.model_path = self.model_dir / "swing_trading_model.h5"
        self.scaler_path = self.model_dir / "feature_scaler.pkl"
        self.label_encoder_path = self.model_dir / "label_encoder.pkl"
        self.metadata_path = self.model_dir / "model_metadata.json"

        # Training parameters - adaptable
        self.feature_dim = 25  # Will be adjusted based on data
        self.epochs = 50
        self.batch_size = 32
        self.validation_split = 0.2

        print(f"TradingModelTrainer initialized")
        print(f"Models will be saved to: {self.model_dir}")

    def load_training_data(self, training_file_pattern="training_data_*.json"):
        """
        Load training data from backtest results
        """
        training_files = list(self.training_dir.glob(training_file_pattern))

        if not training_files:
            # Also check other common locations
            alt_dirs = [
                Path("src/data/training"),
                Path("data/training"),
                Path(".")
            ]
            for alt_dir in alt_dirs:
                if alt_dir.exists():
                    alt_files = list(alt_dir.glob(training_file_pattern))
                    if alt_files:
                        training_files = alt_files
                        break

        if not training_files:
            raise FileNotFoundError(f"No training files found matching pattern: {training_file_pattern}")

        print(f"Found {len(training_files)} training files")

        all_samples = []

        for file_path in training_files:
            try:
                # Handle UTF-8 BOM from PowerShell
                with open(file_path, 'r', encoding='utf-8-sig') as f:
                    data = json.load(f)

                # Handle different data structures
                samples = []
                if 'samples' in data:
                    samples = data['samples']
                elif isinstance(data, list):
                    samples = data
                else:
                    print(f"Warning: Unrecognized data structure in {file_path}")
                    continue

                print(f"Loaded {len(samples)} samples from {file_path.name}")
                all_samples.extend(samples)

            except Exception as e:
                print(f"Error loading {file_path}: {e}")

        print(f"Total training samples: {len(all_samples)}")
        return all_samples

    def detect_data_format(self, sample):
        """
        Detect if this is complex technical indicators data or simple trade data
        """
        if 'technicalIndicators' in sample:
            return 'complex'
        elif 'direction' in sample and 'pnl_percent' in sample:
            return 'simple'
        else:
            return 'unknown'

    def prepare_features_and_labels(self, samples):
        """
        Convert samples to ML features and labels - handles both formats
        """
        if not samples:
            raise ValueError("No samples provided")

        # Detect data format
        data_format = self.detect_data_format(samples[0])
        print(f"Detected data format: {data_format}")

        if data_format == 'complex':
            return self._prepare_complex_features(samples)
        elif data_format == 'simple':
            return self._prepare_simple_features(samples)
        else:
            raise ValueError("Unknown data format")

    def _prepare_complex_features(self, samples):
        """
        Handle complex technical indicators data (original format)
        """
        features = []
        labels = []
        sample_info = []

        for sample in samples:
            try:
                tech = sample.get('technicalIndicators', {})
                feature_vector = self.extract_feature_vector(tech, sample)
                trade_outcome = sample.get('actualOutcome', 'NO_TRADE')

                if trade_outcome == 'NO_TRADE':
                    continue

                features.append(feature_vector)
                labels.append(trade_outcome)

                sample_info.append({
                    'symbol': sample.get('symbol', 'UNKNOWN'),
                    'timestamp': sample.get('timestamp'),
                    'actualReturn': sample.get('actualReturn', 0.0),
                    'holdingTimeHours': sample.get('holdingTimeHours', 0)
                })

            except Exception as e:
                print(f"Error processing complex sample: {e}")
                continue

        features = np.array(features, dtype=np.float32)
        labels = np.array(labels)

        print(f"Prepared {len(features)} complex samples with {features.shape[1]} features")
        return features, labels, sample_info

    def _prepare_simple_features(self, samples):
        """
        Handle simple trade data (PowerShell generated mock data)
        """
        features = []
        labels = []
        sample_info = []

        for sample in samples:
            try:
                # Extract simple features from trade data
                pnl_percent = float(sample.get('pnl_percent', 0.0))
                direction = sample.get('direction', 'long').lower()
                score = float(sample.get('score', 0.5))
                entry_price = float(sample.get('entry_price', 1.0))
                exit_price = float(sample.get('exit_price', entry_price))

                # Create simple feature vector (10 features for first run)
                price_change = (exit_price - entry_price) / entry_price if entry_price > 0 else 0.0

                feature_vector = [
                    1.0 if direction == 'long' else -1.0,  # Direction
                    score,  # Confidence/Score
                    abs(pnl_percent) / 10.0,  # Normalized absolute PnL
                    1.0 if pnl_percent > 0 else 0.0,  # Profitable
                    price_change,  # Price change ratio
                    1.0 if abs(pnl_percent) > 2.0 else 0.0,  # Significant move
                    min(abs(score - 0.5) * 2, 1.0),  # Score confidence
                    1.0 if pnl_percent > 5.0 else 0.0,  # High profit
                    1.0 if pnl_percent < -5.0 else 0.0,  # High loss
                    np.random.normal(0.0, 0.1)  # Random noise for generalization
                ]

                # Determine label from actual outcome
                if sample.get('successful', pnl_percent > 0):
                    label = 'PROFIT'
                elif pnl_percent < -2.0:
                    label = 'LOSS'
                else:
                    label = 'NEUTRAL'

                features.append(feature_vector)
                labels.append(label)

                sample_info.append({
                    'symbol': sample.get('symbol', 'UNKNOWN'),
                    'timestamp': sample.get('entry_time', ''),
                    'actualReturn': pnl_percent,
                    'direction': direction
                })

            except Exception as e:
                print(f"Error processing simple sample: {e}")
                continue

        # Update feature dimension for simple format
        self.feature_dim = 10

        features = np.array(features, dtype=np.float32)
        labels = np.array(labels)

        print(f"Prepared {len(features)} simple samples with {features.shape[1]} features")
        return features, labels, sample_info

    def extract_feature_vector(self, tech_indicators, sample):
        """
        Extract numerical feature vector from technical indicators (complex format)
        """
        features = []

        current_price = sample.get('currentPrice', tech_indicators.get('currentPrice', 1.0))
        if current_price <= 0:
            current_price = 1.0

        # 1. Trend features (6 features)
        features.extend([
            1.0 if tech_indicators.get('primaryTrend') == 'BULLISH' else (
                -1.0 if tech_indicators.get('primaryTrend') == 'BEARISH' else 0.0),
            1.0 if tech_indicators.get('shortTermTrend') == 'BULLISH' else (
                -1.0 if tech_indicators.get('shortTermTrend') == 'BEARISH' else 0.0),
            1.0 if tech_indicators.get('trendAlignment', False) else 0.0,
            float(tech_indicators.get('trendStrength', 0.0)),
            float(tech_indicators.get('ema20_4h', current_price)) / current_price,
            float(tech_indicators.get('ema50_4h', current_price)) / current_price
        ])

        # 2. Momentum features (6 features)
        rsi = float(tech_indicators.get('rsi', 50.0))
        features.extend([
            rsi / 100.0,
            1.0 if tech_indicators.get('macdBullish', False) else 0.0,
            1.0 if tech_indicators.get('macdBearish', False) else 0.0,
            1.0 if tech_indicators.get('rsiBullishZone', False) else 0.0,
            1.0 if tech_indicators.get('rsiBearishZone', False) else 0.0,
            1.0 if tech_indicators.get('rsiRising', False) else 0.0
        ])

        # 3. Volume features (5 features)
        volume_ratio = float(tech_indicators.get('volumeRatio', 1.0))
        features.extend([
            volume_ratio,
            1.0 if tech_indicators.get('strongVolume', False) else 0.0,
            min(volume_ratio / 5.0, 1.0),
            1.0 if tech_indicators.get('volumeBreakout', False) else 0.0,
            1.0 if tech_indicators.get('volumeTrendUp', False) else 0.0
        ])

        # 4. Risk features (4 features)
        risk_reward = float(tech_indicators.get('riskRewardRatio', 0.0))
        features.extend([
            min(risk_reward / 10.0, 1.0),
            float(tech_indicators.get('volatilityPercent', 0.0)) / 100.0,
            float(tech_indicators.get('distanceFromSupport', 0.0)) / 100.0,
            float(tech_indicators.get('distanceFromResistance', 0.0)) / 100.0
        ])

        # 5. Structure features (4 features)
        features.extend([
            1.0 if tech_indicators.get('higherHighs', False) else 0.0,
            1.0 if tech_indicators.get('lowerLows', False) else 0.0,
            1.0 if tech_indicators.get('bullishStructure', False) else 0.0,
            1.0 if tech_indicators.get('bearishStructure', False) else 0.0
        ])

        # Ensure exactly 25 features for complex format
        while len(features) < 25:
            features.append(0.0)
        features = features[:25]

        # Validate features
        features = [float(f) if not (np.isnan(f) or np.isinf(f)) else 0.0 for f in features]
        return np.array(features, dtype=np.float32)

    def create_model(self):
        """
        Create neural network model - adaptive based on feature dimension
        """
        inputs = keras.Input(shape=(self.feature_dim,), name='technical_features')

        # Adaptive architecture based on feature dimension
        if self.feature_dim <= 10:
            # Simpler model for fewer features
            x = layers.Dense(32, activation='relu', name='dense_1')(inputs)
            x = layers.Dropout(0.3, name='dropout_1')(x)
            x = layers.Dense(16, activation='relu', name='dense_2')(x)
            x = layers.Dropout(0.2, name='dropout_2')(x)
        else:
            # More complex model for more features
            x = layers.Dense(64, activation='relu', name='dense_1')(inputs)
            x = layers.BatchNormalization(name='bn_1')(x)
            x = layers.Dropout(0.3, name='dropout_1')(x)

            x = layers.Dense(32, activation='relu', name='dense_2')(x)
            x = layers.BatchNormalization(name='bn_2')(x)
            x = layers.Dropout(0.2, name='dropout_2')(x)

            x = layers.Dense(16, activation='relu', name='dense_3')(x)
            x = layers.Dropout(0.1, name='dropout_3')(x)

        # Output layer - 3 classes
        outputs = layers.Dense(3, activation='softmax', name='signal_output')(x)

        model = keras.Model(inputs=inputs, outputs=outputs, name='SwingTradingModel')

        model.compile(
            optimizer=keras.optimizers.Adam(learning_rate=0.001),
            loss='categorical_crossentropy',
            metrics=['accuracy']
        )

        return model

    def train_model(self, features, labels):
        """
        Train the model
        """
        # Prepare label encoder
        label_encoder = LabelEncoder()
        encoded_labels = label_encoder.fit_transform(labels)

        # Ensure we have exactly 3 classes
        unique_labels = np.unique(encoded_labels)
        n_classes = len(unique_labels)

        if n_classes < 3:
            print(f"Warning: Only {n_classes} classes found. Adding dummy samples for missing classes.")
            # Add dummy samples for missing classes
            all_classes = ['LOSS', 'NEUTRAL', 'PROFIT']
            for i, class_name in enumerate(all_classes):
                if class_name not in label_encoder.classes_:
                    # Add a dummy feature vector and label
                    dummy_feature = np.mean(features, axis=0)
                    features = np.vstack([features, dummy_feature])
                    labels = np.append(labels, class_name)

            # Re-encode labels
            label_encoder = LabelEncoder()
            encoded_labels = label_encoder.fit_transform(labels)

        categorical_labels = keras.utils.to_categorical(encoded_labels, num_classes=3)

        # Scale features
        scaler = StandardScaler()
        scaled_features = scaler.fit_transform(features)

        # Train/validation split
        X_train, X_val, y_train, y_val = train_test_split(
            scaled_features, categorical_labels,
            test_size=self.validation_split,
            random_state=42,
            stratify=encoded_labels
        )

        print(f"Training samples: {len(X_train)}, Validation samples: {len(X_val)}")
        print(f"Feature dimension: {self.feature_dim}")

        # Create model
        model = self.create_model()
        print("Model architecture:")
        model.summary()

        # Training callbacks
        callbacks = [
            keras.callbacks.EarlyStopping(
                monitor='val_loss', patience=8, restore_best_weights=True
            ),
            keras.callbacks.ReduceLROnPlateau(
                monitor='val_loss', factor=0.5, patience=4, min_lr=1e-6
            )
        ]

        # Train model
        print("Starting training...")
        history = model.fit(
            X_train, y_train,
            validation_data=(X_val, y_val),
            epochs=self.epochs,
            batch_size=min(self.batch_size, len(X_train) // 4),
            callbacks=callbacks,
            verbose=1
        )

        # Evaluate model
        val_loss, val_accuracy = model.evaluate(X_val, y_val, verbose=0)

        print(f"\nValidation Results:")
        print(f"Loss: {val_loss:.4f}")
        print(f"Accuracy: {val_accuracy:.4f}")

        # Classification report
        y_pred = model.predict(X_val)
        y_pred_classes = np.argmax(y_pred, axis=1)
        y_val_classes = np.argmax(y_val, axis=1)

        class_names = label_encoder.classes_
        print(f"\nClassification Report:")
        print(classification_report(y_val_classes, y_pred_classes, target_names=class_names, zero_division=0))

        # Save model and components
        model.save(self.model_path)
        joblib.dump(scaler, self.scaler_path)
        joblib.dump(label_encoder, self.label_encoder_path)

        # Save metadata
        metadata = {
            'model_version': '1.0-first-run-compatible',
            'training_date': datetime.now().isoformat(),
            'feature_dimension': self.feature_dim,
            'num_classes': 3,
            'class_names': class_names.tolist(),
            'training_samples': len(X_train),
            'validation_samples': len(X_val),
            'val_accuracy': float(val_accuracy),
            'epochs_trained': len(history.history['loss'])
        }

        with open(self.metadata_path, 'w') as f:
            json.dump(metadata, f, indent=2)

        print(f"\nModel saved to: {self.model_path}")
        print(f"Scaler saved to: {self.scaler_path}")
        print(f"Label encoder saved to: {self.label_encoder_path}")
        print(f"Metadata saved to: {self.metadata_path}")

        return model, history, scaler, label_encoder


def main():
    """
    Main training function
    """
    if len(sys.argv) < 2:
        print("Usage: python train_model.py <training_data_pattern> [epochs]")
        print("Example: python train_model.py training_data_*.json 50")
        sys.exit(1)

    training_pattern = sys.argv[1]
    epochs = int(sys.argv[2]) if len(sys.argv) > 2 else 50

    print(f"Training ML model with pattern: {training_pattern}")
    print(f"Epochs: {epochs}")

    try:
        # Initialize trainer
        trainer = TradingModelTrainer()
        trainer.epochs = epochs

        # Load training data
        samples = trainer.load_training_data(training_pattern)

        if len(samples) < 50:
            print(f"Warning: Only {len(samples)} training samples. Results may be poor.")

        # Prepare features and labels
        features, labels, sample_info = trainer.prepare_features_and_labels(samples)

        if len(features) == 0:
            print("ERROR: No valid samples found!")
            sys.exit(1)

        print(f"Label distribution: {dict(zip(*np.unique(labels, return_counts=True)))}")

        # Train model
        model, history, scaler, label_encoder = trainer.train_model(features, labels)

        print("Training completed successfully!")

    except Exception as e:
        print(f"Training failed: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()