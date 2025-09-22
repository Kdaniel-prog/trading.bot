#!/usr/bin/env python3
"""
ML Model Training Script - Adaptive Class Support
Uses backtest results to train the trading prediction model
"""

import sys
import json
import numpy as np
import tensorflow as tf
from tensorflow.keras.callbacks import EarlyStopping, ReduceLROnPlateau
from tensorflow import keras
from tensorflow.keras import layers
from sklearn.preprocessing import StandardScaler, LabelEncoder
from sklearn.utils.class_weight import compute_class_weight
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report
import warnings
from pathlib import Path
from datetime import datetime
import joblib
from imblearn.over_sampling import SMOTE, RandomOverSampler
from collections import Counter
from sklearn.feature_selection import VarianceThreshold
from imblearn.over_sampling import RandomOverSampler
from imblearn.under_sampling import RandomUnderSampler
from imblearn.pipeline import Pipeline as ImbPipeline


# imbalanced-learn
from imblearn.over_sampling import SMOTE
from imblearn.under_sampling import RandomUnderSampler
from imblearn.pipeline import Pipeline

warnings.filterwarnings('ignore')
tf.get_logger().setLevel('ERROR')


class TradingModelTrainer:
    def __init__(self):
        self.base_dir = Path("src/main/resources/data")
        self.model_dir = self.base_dir / "models"
        self.training_dir = self.base_dir / "training"

        self.model_dir.mkdir(parents=True, exist_ok=True)
        self.training_dir.mkdir(parents=True, exist_ok=True)

        # prefer the newer Keras native format, but keep backward compat if needed
        self.model_path = self.model_dir / "swing_trading_model.keras"
        self.scaler_path = self.model_dir / "feature_scaler.pkl"
        self.label_encoder_path = self.model_dir / "label_encoder.pkl"
        self.metadata_path = self.model_dir / "model_metadata.json"

        self.feature_dim = 25
        self.epochs = 50
        self.batch_size = 32
        self.validation_split = 0.2

        # resampling strategy defaults (tweakable)
        self.smote_ratio = 0.5         # SMOTE: bring minority classes up to 50% of majority (relative)
        self.under_ratio = 0.8         # undersample: reduce majority classes to 80% of minority

        print(f"TradingModelTrainer initialized")
        print(f"Models will be saved to: {self.model_dir}")

    def load_training_data(self, training_file_pattern="training_data_*.json"):
        training_files = list(self.training_dir.glob(training_file_pattern))

        if not training_files:
            alt_dirs = [Path("src/data/training"), Path("data/training"), Path(".")]
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
                with open(file_path, 'r', encoding='utf-8-sig') as f:
                    data = json.load(f)

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

        # Próbáljuk kronologikusan rendezni, ha van timestamp (támogatva: list [Y,M,D,H,M] vagy iso string)
        def _get_ts(s):
            ts = s.get('timestamp')
            if not ts:
                return None
            try:
                if isinstance(ts, list):
                    # [Y,M,D,H,M] -> python datetime
                    return datetime(*ts)
                else:
                    return datetime.fromisoformat(str(ts))
            except Exception:
                return None

        # sortálás None-okkal a végére
        all_samples = sorted(all_samples, key=lambda s: (_get_ts(s) is None, _get_ts(s)))
        print(f"Total training samples: {len(all_samples)} (sorted by timestamp if present)")
        return all_samples

    def detect_data_format(self, sample):
        if 'technicalIndicators' in sample:
            return 'complex'
        elif 'direction' in sample and 'pnl_percent' in sample:
            return 'simple'
        else:
            return 'unknown'

    def prepare_features_and_labels(self, samples):
        if not samples:
            raise ValueError("No samples provided")

        data_format = self.detect_data_format(samples[0])
        print(f"Detected data format: {data_format}")

        if data_format == 'complex':
            return self._prepare_complex_features(samples)
        elif data_format == 'simple':
            return self._prepare_simple_features(samples)
        else:
            raise ValueError("Unknown data format")

    def _prepare_complex_features(self, samples):
        features, labels = [], []

        for sample in samples:
            try:
                tech = sample.get('technicalIndicators', {})
                feature_vector = self.extract_feature_vector(tech, sample)
                trade_outcome = sample.get('actualOutcome', 'NO_TRADE')

                if trade_outcome == 'NO_TRADE':
                    continue

                features.append(feature_vector)
                labels.append(trade_outcome)
            except Exception as e:
                print(f"Error processing complex sample: {e}")
                continue

        features = np.array(features, dtype=np.float32)
        labels = np.array(labels)

        print(f"Prepared {len(features)} complex samples with {features.shape[1]} features")
        return features, labels, None

    def _prepare_simple_features(self, samples):
        features, labels = [], []

        for sample in samples:
            try:
                pnl_percent = float(sample.get('pnl_percent', 0.0))
                direction = sample.get('direction', 'long').lower()
                score = float(sample.get('score', 0.5))
                entry_price = float(sample.get('entry_price', 1.0))
                exit_price = float(sample.get('exit_price', entry_price))

                price_change = (exit_price - entry_price) / entry_price if entry_price > 0 else 0.0

                feature_vector = [
                    1.0 if direction == 'long' else -1.0,
                    score,
                    abs(pnl_percent) / 10.0,
                    1.0 if pnl_percent > 0 else 0.0,
                    price_change,
                    1.0 if abs(pnl_percent) > 2.0 else 0.0,
                    min(abs(score - 0.5) * 2, 1.0),
                    1.0 if pnl_percent > 5.0 else 0.0,
                    1.0 if pnl_percent < -5.0 else 0.0,
                    np.random.normal(0.0, 0.1)
                ]

                if sample.get('successful', pnl_percent > 0):
                    label = 'PROFIT'
                elif pnl_percent < -2.0:
                    label = 'LOSS'
                else:
                    label = 'NEUTRAL'

                features.append(feature_vector)
                labels.append(label)
            except Exception as e:
                print(f"Error processing simple sample: {e}")
                continue

        self.feature_dim = 10
        features = np.array(features, dtype=np.float32)
        labels = np.array(labels)

        print(f"Prepared {len(features)} simple samples with {features.shape[1]} features")
        return features, labels, None

    def extract_feature_vector(self, tech_indicators, sample):
        features = []
        current_price = sample.get('currentPrice', tech_indicators.get('currentPrice', 1.0))
        if current_price <= 0:
            current_price = 1.0

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

        rsi = float(tech_indicators.get('rsi', 50.0))
        features.extend([
            rsi / 100.0,
            1.0 if tech_indicators.get('macdBullish', False) else 0.0,
            1.0 if tech_indicators.get('macdBearish', False) else 0.0,
            1.0 if tech_indicators.get('rsiBullishZone', False) else 0.0,
            1.0 if tech_indicators.get('rsiBearishZone', False) else 0.0,
            1.0 if tech_indicators.get('rsiRising', False) else 0.0
        ])

        volume_ratio = float(tech_indicators.get('volumeRatio', 1.0))
        features.extend([
            volume_ratio,
            1.0 if tech_indicators.get('strongVolume', False) else 0.0,
            min(volume_ratio / 5.0, 1.0),
            1.0 if tech_indicators.get('volumeBreakout', False) else 0.0,
            1.0 if tech_indicators.get('volumeTrendUp', False) else 0.0
        ])

        risk_reward = float(tech_indicators.get('riskRewardRatio', 0.0))
        features.extend([
            min(risk_reward / 10.0, 1.0),
            float(tech_indicators.get('volatilityPercent', 0.0)) / 100.0,
            float(tech_indicators.get('distanceFromSupport', 0.0)) / 100.0,
            float(tech_indicators.get('distanceFromResistance', 0.0)) / 100.0
        ])

        features.extend([
            1.0 if tech_indicators.get('higherHighs', False) else 0.0,
            1.0 if tech_indicators.get('lowerLows', False) else 0.0,
            1.0 if tech_indicators.get('bullishStructure', False) else 0.0,
            1.0 if tech_indicators.get('bearishStructure', False) else 0.0
        ])

        while len(features) < 25:
            features.append(0.0)
        features = features[:25]

        features = [float(f) if not (np.isnan(f) or np.isinf(f)) else 0.0 for f in features]
        return np.array(features, dtype=np.float32)

    def create_model(self, n_classes):
        inputs = keras.Input(shape=(self.feature_dim,), name='features')

        if self.feature_dim <= 10:
            x = layers.Dense(64, activation='relu')(inputs)
            x = layers.Dropout(0.3)(x)
            x = layers.Dense(32, activation='relu')(x)
            x = layers.Dropout(0.2)(x)
        else:
            # More complex model for more features
            x = layers.Dense(64, activation='relu', name='dense_1')(inputs)
            x = layers.BatchNormalization(name='bn_1')(x)
            x = layers.Dropout(0.3, name='dropout_1')(x)

            x = layers.Dense(32, activation='relu', name='dense_2')(x)
            x = layers.BatchNormalization(name='bn_2')(x)
            x = layers.Dropout(0.2, name='dropout_2')(x)

        outputs = layers.Dense(n_classes, activation='softmax')(x)
        model = keras.Model(inputs=inputs, outputs=outputs)

        model.compile(
            optimizer=keras.optimizers.Adam(learning_rate=0.001),
            loss='categorical_crossentropy',
            metrics=['accuracy']
        )
        return model

    # --------------------------------------------------------------------
    # Segédfüggvények: low-variance drop, time-series split, adaptív resampling
    # --------------------------------------------------------------------
    def drop_low_variance(self, X, threshold=1e-5):
        selector = VarianceThreshold(threshold=threshold)
        try:
            X_reduced = selector.fit_transform(X)
            kept = selector.get_support(indices=True)
            print(f"drop_low_variance: kept {X_reduced.shape[1]} features (threshold={threshold})")
            return X_reduced, kept
        except Exception as e:
            print(f"drop_low_variance failed: {e}")
            return X, np.arange(X.shape[1])

    def time_series_split(self, X, y, train_frac=0.7, val_frac=0.15):
        n = len(X)
        if n < 3:
            raise ValueError("Not enough samples for time_series_split")
        train_end = int(n * train_frac)
        val_end = train_end + int(n * val_frac)
        X_train = X[:train_end]
        y_train = y[:train_end]
        X_val = X[train_end:val_end]
        y_val = y[train_end:val_end]
        X_test = X[val_end:]
        y_test = y[val_end:]
        print(f"time_series_split -> train:{len(X_train)}, val:{len(X_val)}, test:{len(X_test)}")
        return X_train, X_val, X_test, y_train, y_val, y_test

    def adaptive_resample(self, X_train, y_train, desired_majority_ratio=1.0):
        # Counter
        counts = Counter(y_train)
        print("adaptive_resample: class counts before:", counts)
        min_count = min(counts.values())
        max_count = max(counts.values())

        # Ha túl kevés minta a legkisebb osztályban, ne SMOTE-olj, csak ROS
        if min_count < 4:
            print("adaptive_resample: small classes detected (min_count < 4). Using RandomOverSampler.")
            ros = RandomOverSampler(random_state=42)
            X_res, y_res = ros.fit_resample(X_train, y_train)
            print("After ROS counts:", Counter(y_res))
            return X_res, y_res

        # Egy egyszerű pipeline: először undersample a többséget kicsit, majd SMOTE a kisebbekre
        try:
            # Reduce majority to desired ratio (e.g. reduce majority to under_ratio * max_count)
            desired_majority = int(max_count * self.under_ratio)
            # create sampling dict for under
            majority_label = max(counts.items(), key=lambda kv: kv[1])[0]
            under_strategy = {majority_label: desired_majority}
            rus = RandomUnderSampler(sampling_strategy=under_strategy, random_state=42)

            # SMOTE: k_neighbors kisebb legyen, mint a min_count-1
            k_neigh = min(3, max(1, min_count - 1))

            smote = SMOTE(sampling_strategy='not majority', random_state=42, k_neighbors=k_neigh)
            pipeline = ImbPipeline([('under', rus), ('smote', smote)])
            X_res, y_res = pipeline.fit_resample(X_train, y_train)
            print("adaptive_resample: after pipeline counts:", Counter(y_res))
            return X_res, y_res
        except Exception as e:
            print(f"adaptive_resample pipeline failed: {e}. Falling back to ROS.")
            ros = RandomOverSampler(random_state=42)
            X_res, y_res = ros.fit_resample(X_train, y_train)
            print("After fallback ROS counts:", Counter(y_res))
            return X_res, y_res

    # --------------------------------------------------------------------
    # Javított train_model (helyettesíti a korábbit)
    # --------------------------------------------------------------------
    def train_model(self, features, labels):
        # debug block
        print("=== DEBUG: RAW INPUT CHECK ===")
        print("FEATURE SHAPE, DTYPE:", features.shape, features.dtype)
        print("LABELS SAMPLE:", labels[:30])
        unique, counts = np.unique(labels, return_counts=True)
        print("LABEL DISTRIBUTION (raw):", dict(zip(unique, counts)))
        nan_counts = np.isnan(features).sum(axis=0)
        inf_counts = np.isinf(features).sum(axis=0)
        print("NaN per feature:", nan_counts)
        print("Inf per feature:", inf_counts)
        print("Per-feature min/max:", np.nanmin(features, axis=0), np.nanmax(features, axis=0))
        print("================================\n")

        # Encode labels
        label_encoder = LabelEncoder()
        encoded_labels = label_encoder.fit_transform(labels)
        n_classes = len(label_encoder.classes_)
        print(f"Detected {n_classes} unique classes: {label_encoder.classes_}")

        # --- Time-series split (no shuffle) ---
        X_train_raw, X_val_raw, X_test_raw, y_train_enc, y_val_enc, y_test_enc = \
            self.time_series_split(features, encoded_labels, train_frac=0.7, val_frac=0.15)

        print("Train label dist (before resample):", dict(zip(*np.unique(y_train_enc, return_counts=True))))
        print("Val label dist:", dict(zip(*np.unique(y_val_enc, return_counts=True))))
        print("Test label dist:", dict(zip(*np.unique(y_test_enc, return_counts=True))))

        # --- Fit scaler on TRAIN only, transform both val/test ---
        scaler = StandardScaler()
        X_train_scaled = scaler.fit_transform(X_train_raw)
        X_val_scaled = scaler.transform(X_val_raw)
        X_test_scaled = scaler.transform(X_test_raw)

        # --- Drop low-variance features (fit on train, apply to others) ---
        X_train_scaled, kept_idx = self.drop_low_variance(X_train_scaled, threshold=1e-6)
        if X_val_scaled.shape[1] >= len(kept_idx):
            X_val_scaled = X_val_scaled[:, kept_idx]
            X_test_scaled = X_test_scaled[:, kept_idx]
        else:
            # fallback if shapes mismatch
            print("Warning: kept_idx mismatch; skipping drop_low_variance application to val/test.")

        # --- Resample only the training set (adaptive) ---
        X_resampled, y_resampled = self.adaptive_resample(X_train_scaled, y_train_enc)

        # --- Convert labels to categorical for training/validation ---
        y_train_cat = keras.utils.to_categorical(y_resampled, num_classes=n_classes)
        y_val_cat = keras.utils.to_categorical(y_val_enc, num_classes=n_classes)

        # --- Create and train model ---
        self.feature_dim = X_resampled.shape[1]
        model = self.create_model(n_classes)
        model.summary()

        early_stopping = EarlyStopping(monitor="val_loss", patience=8, restore_best_weights=True)
        reduce_lr = ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=4, min_lr=1e-6)

        history = model.fit(
            X_resampled, y_train_cat,
            validation_data=(X_val_scaled, y_val_cat),
            epochs=self.epochs,
            batch_size=self.batch_size,
            callbacks=[early_stopping, reduce_lr],
            verbose=2
        )

        val_loss, val_accuracy = model.evaluate(X_val_scaled, y_val_cat, verbose=0)
        print(f"\nValidation Results:\nLoss: {val_loss:.4f}\nAccuracy: {val_accuracy:.4f}")

        y_pred = model.predict(X_val_scaled)
        y_pred_classes = np.argmax(y_pred, axis=1)
        y_val_classes = np.argmax(y_val_cat, axis=1)

        print("\nClassification Report:")
        print(
            classification_report(y_val_classes, y_pred_classes, target_names=label_encoder.classes_, zero_division=0))

        # Save artifacts
        model.save(self.model_path)
        joblib.dump(scaler, self.scaler_path)
        joblib.dump(label_encoder, self.label_encoder_path)

        metadata = {
            'model_version': '1.1-adaptive',
            'training_date': datetime.now().isoformat(),
            'feature_dimension': self.feature_dim,
            'num_classes': n_classes,
            'class_names': label_encoder.classes_.tolist(),
            'training_samples': int(X_resampled.shape[0]),
            'validation_samples': int(X_val_scaled.shape[0]),
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
    if len(sys.argv) < 2:
        print("Usage: python train_model.py <training_data_pattern> [epochs]")
        sys.exit(1)

    training_pattern = sys.argv[1]
    epochs = int(sys.argv[2]) if len(sys.argv) > 2 else 50

    print(f"Training ML model with pattern: {training_pattern}\nEpochs: {epochs}")

    try:
        trainer = TradingModelTrainer()
        trainer.epochs = epochs
        samples = trainer.load_training_data(training_pattern)

        if len(samples) < 50:
            print(f"Warning: Only {len(samples)} training samples. Results may be poor.")

        features, labels, _ = trainer.prepare_features_and_labels(samples)
        if len(features) == 0:
            print("ERROR: No valid samples found!")
            sys.exit(1)

        print(f"Label distribution: {dict(zip(*np.unique(labels, return_counts=True)))}")
        trainer.train_model(features, labels)
        print("Training completed successfully!")

    except Exception as e:
        print(f"Training failed: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()
