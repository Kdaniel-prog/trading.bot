#!/usr/bin/env python3
"""
ML Model Training Script
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
import matplotlib.pyplot as plt
import seaborn as sns

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

        # Training parameters
        self.feature_dim = 25
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
            # Also check Python data directory
            python_data_dir = Path("src/main/python/data/ml")
            training_files = list(python_data_dir.glob(training_file_pattern))

        if not training_files:
            raise FileNotFoundError(f"No training files found matching pattern: {training_file_pattern}")

        print(f"Found {len(training_files)} training files")

        all_samples = []

        for file_path in training_files:
            try:
                with open(file_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)

                samples = data.get('samples', [])
                print(f"Loaded {len(samples)} samples from {file_path.name}")
                all_samples.extend(samples)

            except Exception as e:
                print(f"Error loading {file_path}: {e}")

        print(f"Total training samples: {len(all_samples)}")
        return all_samples

    def prepare_features_and_labels(self, samples):
        """
        Convert backtest samples to ML features and labels
        """
        features = []
        labels = []
        sample_info = []

        for sample in samples:
            try:
                # Extract technical indicators
                tech = sample.get('technicalIndicators', {})

                # Create feature vector (same as in ml_predictor.py)
                feature_vector = self.extract_feature_vector(tech, sample)

                # Get actual trade outcome as label
                trade_outcome = sample.get('actualOutcome', 'NO_TRADE')

                # Skip if no clear outcome
                if trade_outcome == 'NO_TRADE':
                    continue

                features.append(feature_vector)
                labels.append(trade_outcome)

                # Keep sample info for analysis
                sample_info.append({
                    'symbol': sample.get('symbol', 'UNKNOWN'),
                    'timestamp': sample.get('timestamp'),
                    'actualReturn': sample.get('actualReturn', 0.0),
                    'holdingTimeHours': sample.get('holdingTimeHours', 0)
                })

            except Exception as e:
                print(f"Error processing sample: {e}")
                continue

        if len(features) == 0:
            raise ValueError("No valid training samples found")

        features = np.array(features, dtype=np.float32)
        labels = np.array(labels)

        print(f"Prepared {len(features)} samples with {features.shape[1]} features")
        print(f"Label distribution: {dict(zip(*np.unique(labels, return_counts=True)))}")

        return features, labels, sample_info

    def extract_feature_vector(self, tech_indicators, sample):
        """
        Extract numerical feature vector from technical indicators
        Same logic as ml_predictor.py for consistency
        """
        features = []

        current_price = sample.get('currentPrice', 1.0)

        # 1. Trend features (6 features)
        features.extend([
            1.0 if tech_indicators.get('primaryTrend') == 'BULLISH' else (-1.0 if tech_indicators.get('primaryTrend') == 'BEARISH' else 0.0),
            1.0 if tech_indicators.get('shortTermTrend') == 'BULLISH' else (-1.0 if tech_indicators.get('shortTermTrend') == 'BEARISH' else 0.0),
            1.0 if tech_indicators.get('trendAlignment', False) else 0.0,
            float(tech_indicators.get('trendStrength', 0.0)),
            float(tech_indicators.get('ema20_4h', current_price)) / current_price,
            float(tech_indicators.get('ema50_4h', current_price)) / current_price
        ])

        # 2. Momentum features (6 features)
        rsi = float(tech_indicators.get('rsi', 50.0))
        features.extend([
            rsi / 100.0,  # Normalized RSI
            1.0 if tech_indicators.get('macdBullish', False) else 0.0,
            1.0 if tech_indicators.get('macdBearish', False) else 0.0,
            1.0 if tech_indicators.get('rsiBullishZone', False) else 0.0,
            1.0 if tech_indicators.get('rsiBearishZone', False) else 0.0,
            1.0 if tech_indicators.get('rsiRising', False) else 0.0
        ])

        # 3. Volume features (5 features)
        features.extend([
            float(tech_indicators.get('volumeRatio', 1.0)),
            1.0 if tech_indicators.get('strongVolume', False) else 0.0,
            float(tech_indicators.get('volumeRatio', 1.0)) / 5.0,  # Normalized volume ratio
            1.0 if tech_indicators.get('volumeBreakout', False) else 0.0,
            1.0 if tech_indicators.get('volumeTrendUp', False) else 0.0
        ])

        # 4. Risk features (4 features)
        features.extend([
            float(tech_indicators.get('riskRewardRatio', 0.0)) / 10.0,  # Normalized RR
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

        # Pad or trim to exact feature dimension
        while len(features) < self.feature_dim:
            features.append(0.0)
        features = features[:self.feature_dim]

        return np.array(features, dtype=np.float32)

    def create_model(self):
        """
        Create neural network model
        """
        # Input layer
        inputs = keras.Input(shape=(self.feature_dim,), name='technical_features')

        # Feature extraction layers
        x = layers.Dense(64, activation='relu', name='dense_1')(inputs)
        x = layers.BatchNormalization(name='bn_1')(x)
        x = layers.Dropout(0.3, name='dropout_1')(x)

        x = layers.Dense(32, activation='relu', name='dense_2')(x)
        x = layers.BatchNormalization(name='bn_2')(x)
        x = layers.Dropout(0.2, name='dropout_2')(x)

        x = layers.Dense(16, activation='relu', name='dense_3')(x)
        x = layers.Dropout(0.1, name='dropout_3')(x)

        # Output layer - 3 classes (LONG, SHORT, NO_TRADE)
        outputs = layers.Dense(3, activation='softmax', name='signal_output')(x)

        # Create model
        model = keras.Model(inputs=inputs, outputs=outputs, name='SwingTradingModel')

        # Compile
        model.compile(
            optimizer=keras.optimizers.Adam(learning_rate=0.001),
            loss='categorical_crossentropy',
            metrics=['accuracy', 'precision', 'recall']
        )

        return model

    def train_model(self, features, labels):
        """
        Train the model
        """
        # Prepare label encoder
        label_encoder = LabelEncoder()
        encoded_labels = label_encoder.fit_transform(labels)

        # Convert to categorical
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

        # Create model
        model = self.create_model()
        print("Model architecture:")
        model.summary()

        # Training callbacks
        callbacks = [
            keras.callbacks.EarlyStopping(
                monitor='val_loss', patience=10, restore_best_weights=True
            ),
            keras.callbacks.ReduceLROnPlateau(
                monitor='val_loss', factor=0.5, patience=5, min_lr=1e-6
            )
        ]

        # Train model
        print("Starting training...")
        history = model.fit(
            X_train, y_train,
            validation_data=(X_val, y_val),
            epochs=self.epochs,
            batch_size=self.batch_size,
            callbacks=callbacks,
            verbose=1
        )

        # Evaluate model
        val_loss, val_accuracy, val_precision, val_recall = model.evaluate(X_val, y_val, verbose=0)

        print(f"\nValidation Results:")
        print(f"Loss: {val_loss:.4f}")
        print(f"Accuracy: {val_accuracy:.4f}")
        print(f"Precision: {val_precision:.4f}")
        print(f"Recall: {val_recall:.4f}")

        # Detailed classification report
        y_pred = model.predict(X_val)
        y_pred_classes = np.argmax(y_pred, axis=1)
        y_val_classes = np.argmax(y_val, axis=1)

        class_names = label_encoder.classes_
        print(f"\nClassification Report:")
        print(classification_report(y_val_classes, y_pred_classes, target_names=class_names))

        # Save model and components
        model.save(self.model_path)
        joblib.dump(scaler, self.scaler_path)
        joblib.dump(label_encoder, self.label_encoder_path)

        # Save metadata
        metadata = {
            'model_version': '1.0',
            'training_date': datetime.now().isoformat(),
            'feature_dimension': self.feature_dim,
            'num_classes': 3,
            'class_names': class_names.tolist(),
            'training_samples': len(X_train),
            'validation_samples': len(X_val),
            'val_accuracy': float(val_accuracy),
            'val_precision': float(val_precision),
            'val_recall': float(val_recall),
            'epochs_trained': len(history.history['loss'])
        }

        with open(self.metadata_path, 'w') as f:
            json.dump(metadata, f, indent=2)

        print(f"\nModel saved to: {self.model_path}")
        print(f"Scaler saved to: {self.scaler_path}")
        print(f"Label encoder saved to: {self.label_encoder_path}")
        print(f"Metadata saved to: {self.metadata_path}")

        return model, history, scaler, label_encoder

    def plot_training_history(self, history):
        """
        Plot training history
        """
        try:
            fig, axes = plt.subplots(2, 2, figsize=(12, 8))

            # Loss
            axes[0,0].plot(history.history['loss'], label='Training Loss')
            axes[0,0].plot(history.history['val_loss'], label='Validation Loss')
            axes[0,0].set_title('Model Loss')
            axes[0,0].set_xlabel('Epoch')
            axes[0,0].set_ylabel('Loss')
            axes[0,0].legend()

            # Accuracy
            axes[0,1].plot(history.history['accuracy'], label='Training Accuracy')
            axes[0,1].plot(history.history['val_accuracy'], label='Validation Accuracy')
            axes[0,1].set_title('Model Accuracy')
            axes[0,1].set_xlabel('Epoch')
            axes[0,1].set_ylabel('Accuracy')
            axes[0,1].legend()

            # Precision
            axes[1,0].plot(history.history['precision'], label='Training Precision')
            axes[1,0].plot(history.history['val_precision'], label='Validation Precision')
            axes[1,0].set_title('Model Precision')
            axes[1,0].set_xlabel('Epoch')
            axes[1,0].set_ylabel('Precision')
            axes[1,0].legend()

            # Recall
            axes[1,1].plot(history.history['recall'], label='Training Recall')
            axes[1,1].plot(history.history['val_recall'], label='Validation Recall')
            axes[1,1].set_title('Model Recall')
            axes[1,1].set_xlabel('Epoch')
            axes[1,1].set_ylabel('Recall')
            axes[1,1].legend()

            plt.tight_layout()

            # Save plot
            plot_path = self.training_dir / f"training_history_{datetime.now().strftime('%Y%m%d_%H%M%S')}.png"
            plt.savefig(plot_path, dpi=300, bbox_inches='tight')
            plt.show()

            print(f"Training history plot saved to: {plot_path}")

        except Exception as e:
            print(f"Could not create training plots: {e}")

def main():
    """
    Main training function
    """
    if len(sys.argv) < 2:
        print("Usage: python train_model.py <training_data_pattern> [epochs]")
        print("Example: python train_model.py training_data_*.json 100")
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

        if len(samples) < 100:
            print(f"Warning: Only {len(samples)} training samples. Consider generating more data.")

        # Prepare features and labels
        features, labels, sample_info = trainer.prepare_features_and_labels(samples)

        # Train model
        model, history, scaler, label_encoder = trainer.train_model(features, labels)

        # Plot training history
        trainer.plot_training_history(history)

        print("Training completed successfully!")

    except Exception as e:
        print(f"Training failed: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()