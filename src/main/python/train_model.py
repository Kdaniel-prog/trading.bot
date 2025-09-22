#!/usr/bin/env python3
"""
Updated ML Model Training Script - Adaptive Class Support
- Cleaner imports, argparse, logging
- Better timestamp parsing and support for top-level 'training_data' key
- Fixed low-variance feature application bug
- Deterministic seeds, safer resampling fallbacks
- Improved CLI and metadata
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import random
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import List, Tuple, Optional

import joblib
import numpy as np
import tensorflow as tf
from sklearn.feature_selection import VarianceThreshold
from sklearn.metrics import classification_report
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder, StandardScaler
from tensorflow import keras
from tensorflow.keras import layers
from tensorflow.keras.callbacks import EarlyStopping, ReduceLROnPlateau

# imbalanced-learn
from imblearn.over_sampling import SMOTE, RandomOverSampler
from imblearn.under_sampling import RandomUnderSampler
from imblearn.pipeline import Pipeline as ImbPipeline

# Set deterministic behavior where feasible
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

# Constants
DEFAULT_FEATURE_DIM = 25
DEFAULT_EPOCHS = 50
DEFAULT_BATCH = 32


def iso_to_datetime(val):
    """Robust ISO or epoch or list-to-datetime conversion."""
    if val is None:
        return None
    try:
        if isinstance(val, (int, float)):
            # unix timestamp (seconds)
            return datetime.utcfromtimestamp(int(val))
        if isinstance(val, list):
            return datetime(*[int(x) for x in val])
        # common ISO variants
        return datetime.fromisoformat(str(val))
    except Exception:
        # try some common formats
        fmts = ["%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"]
        for fmt in fmts:
            try:
                return datetime.strptime(str(val), fmt)
            except Exception:
                continue
    return None


class TradingModelTrainer:
    def __init__(self, base_dir: Path = Path("src/main/resources/data")):
        self.base_dir = base_dir
        self.model_dir = self.base_dir / "models"
        self.training_dir = self.base_dir / "training"

        self.model_dir.mkdir(parents=True, exist_ok=True)
        self.training_dir.mkdir(parents=True, exist_ok=True)

        # artifact paths
        self.model_path = self.model_dir / "swing_trading_model.keras"
        self.scaler_path = self.model_dir / "feature_scaler.pkl"
        self.label_encoder_path = self.model_dir / "label_encoder.pkl"
        self.metadata_path = self.model_dir / "model_metadata.json"

        # training hyperparams (can be adjusted)
        self.feature_dim = DEFAULT_FEATURE_DIM
        self.epochs = DEFAULT_EPOCHS
        self.batch_size = DEFAULT_BATCH
        self.validation_split = 0.2

        # resampling strategy defaults
        self.smote_ratio = 0.5
        self.under_ratio = 0.8

        logging.getLogger().info(f"TradingModelTrainer initialized; models => {self.model_dir}")

    # ---------------------------- I/O & Loading ----------------------------
    def load_training_data(self, training_file_pattern: str = "training_data_*.json") -> List[dict]:
        # try main training dir, then some fallbacks
        training_files = list(self.training_dir.glob(training_file_pattern))
        if not training_files:
            alt_dirs = [Path("src/data/training"), Path("data/training"), Path(".")]
            for alt in alt_dirs:
                files = list(alt.glob(training_file_pattern))
                if files:
                    training_files = files
                    break

        if not training_files:
            raise FileNotFoundError(f"No training files found matching pattern: {training_file_pattern}")

        logging.getLogger().info(f"Found {len(training_files)} training files")
        all_samples: List[dict] = []

        for file_path in sorted(training_files):
            try:
                with open(file_path, "r", encoding="utf-8-sig") as fh:
                    data = json.load(fh)

                # Support multiple payload styles: {"samples": [...]}, {"training_data": [...]}, list of objects
                if isinstance(data, dict):
                    if "samples" in data and isinstance(data["samples"], list):
                        samples = data["samples"]
                    elif "training_data" in data and isinstance(data["training_data"], list):
                        samples = data["training_data"]
                    else:
                        # attempt to find the first list-valued field
                        lists = [v for v in data.values() if isinstance(v, list)]
                        samples = lists[0] if lists else []
                elif isinstance(data, list):
                    samples = data
                else:
                    samples = []

                logging.getLogger().info(f"Loaded {len(samples)} samples from {file_path.name}")
                all_samples.extend(samples)
            except Exception as exc:
                logging.getLogger().warning(f"Error reading {file_path}: {exc}")

        # sort by timestamp where possible (None last)
        def _get_ts(s):
            ts = s.get("timestamp")
            return (iso_to_datetime(ts) is None, iso_to_datetime(ts))

        all_samples = sorted(all_samples, key=_get_ts)
        logging.getLogger().info(f"Total training samples: {len(all_samples)} (sorted by timestamp if present)")
        return all_samples

    # ------------------------- Format detection & prep ----------------------
    def detect_data_format(self, sample: dict) -> str:
        if not isinstance(sample, dict):
            return "unknown"
        if "technicalIndicators" in sample or any(k.startswith("analysis_") for k in sample.keys()):
            return "complex"
        if "direction" in sample or ("pnl_percent" in sample) or ("actualPnl" in sample):
            return "simple"
        return "unknown"

    def prepare_features_and_labels(self, samples: List[dict]) -> Tuple[np.ndarray, np.ndarray, Optional[List[str]]]:
        if not samples:
            raise ValueError("No samples provided")

        data_format = self.detect_data_format(samples[0])
        logging.getLogger().info(f"Detected data format: {data_format}")

        if data_format == "complex":
            return self._prepare_complex_features(samples)
        elif data_format == "simple":
            return self._prepare_simple_features(samples)
        else:
            raise ValueError("Unknown data format; can't prepare features")

    def _prepare_complex_features(self, samples: List[dict]) -> Tuple[np.ndarray, np.ndarray, None]:
        features, labels = [], []
        for sample in samples:
            try:
                tech = sample.get("technicalIndicators", {})
                feature_vector = self.extract_feature_vector(tech, sample)
                trade_outcome = sample.get("actualOutcome") or sample.get("actualOutcome", "NO_TRADE")

                # Skip pure NO_TRADE examples by default (configurable as needed)
                if trade_outcome in (None, "NO_TRADE"):
                    continue

                features.append(feature_vector)
                labels.append(trade_outcome)
            except Exception as exc:
                logging.getLogger().warning(f"Error processing complex sample: {exc}")
                continue

        if not features:
            return np.zeros((0, self.feature_dim), dtype=np.float32), np.array([]), None

        features = np.array(features, dtype=np.float32)
        labels = np.array(labels)

        logging.getLogger().info(f"Prepared {len(features)} complex samples with {features.shape[1]} features")
        return features, labels, None

    def _prepare_simple_features(self, samples: List[dict]) -> Tuple[np.ndarray, np.ndarray, None]:
        features, labels = [], []
        for sample in samples:
            try:
                pnl_percent = float(sample.get("pnl_percent", sample.get("actualPnl", 0.0)))
                direction = str(sample.get("direction", sample.get("predictedDirection", "long"))).lower()
                score = float(sample.get("score", 0.5))
                entry_price = float(sample.get("entry_price", sample.get("price", 1.0)))
                exit_price = float(sample.get("exit_price", entry_price))

                price_change = (exit_price - entry_price) / entry_price if entry_price > 0 else 0.0

                feature_vector = [
                    1.0 if direction == "long" else -1.0,
                    score,
                    abs(pnl_percent) / 10.0,
                    1.0 if pnl_percent > 0 else 0.0,
                    price_change,
                    1.0 if abs(pnl_percent) > 2.0 else 0.0,
                    min(abs(score - 0.5) * 2, 1.0),
                    1.0 if pnl_percent > 5.0 else 0.0,
                    1.0 if pnl_percent < -5.0 else 0.0,
                    float(np.random.normal(0.0, 0.1))
                ]

                # heuristics for label
                if sample.get("successful") is not None:
                    label = "PROFIT" if sample.get("successful") else "LOSS"
                elif pnl_percent > 0:
                    label = "PROFIT"
                elif pnl_percent < -2.0:
                    label = "LOSS"
                else:
                    label = "NEUTRAL"

                features.append(feature_vector)
                labels.append(label)
            except Exception as exc:
                logging.getLogger().warning(f"Error processing simple sample: {exc}")
                continue

        self.feature_dim = len(features[0]) if features else 10
        if not features:
            return np.zeros((0, self.feature_dim), dtype=np.float32), np.array([]), None

        features = np.array(features, dtype=np.float32)
        labels = np.array(labels)
        logging.getLogger().info(f"Prepared {len(features)} simple samples with {features.shape[1]} features")
        return features, labels, None

    def extract_feature_vector(self, tech_indicators: dict, sample: dict) -> np.ndarray:
        features = []
        current_price = sample.get("currentPrice") or tech_indicators.get("current_price") or 1.0
        try:
            current_price = float(current_price)
        except Exception:
            current_price = 1.0
        if current_price <= 0:
            current_price = 1.0

        # Basic trend & EMAs
        features.extend([
            1.0 if tech_indicators.get("primaryTrend") == "BULLISH" else (-1.0 if tech_indicators.get("primaryTrend") == "BEARISH" else 0.0),
            1.0 if tech_indicators.get("shortTermTrend") == "BULLISH" else (-1.0 if tech_indicators.get("shortTermTrend") == "BEARISH" else 0.0),
            1.0 if tech_indicators.get("trendAlignment", False) else 0.0,
            float(tech_indicators.get("trendStrength", 0.0)),
            float(tech_indicators.get("ema20_4h", tech_indicators.get("ema20", current_price))) / current_price,
            float(tech_indicators.get("ema50_4h", tech_indicators.get("ema50", current_price))) / current_price,
        ])

        rsi = float(tech_indicators.get("rsi", tech_indicators.get("analysis_rsi", 50.0)))
        features.extend([
            rsi / 100.0,
            1.0 if tech_indicators.get("macdBullish", tech_indicators.get("analysis_bullish_structure", False)) else 0.0,
            1.0 if tech_indicators.get("macdBearish", False) else 0.0,
            1.0 if tech_indicators.get("rsiBullishZone", False) else 0.0,
            1.0 if tech_indicators.get("rsiBearishZone", False) else 0.0,
            1.0 if tech_indicators.get("rsiRising", False) else 0.0,
        ])

        volume_ratio = float(tech_indicators.get("volumeRatio", tech_indicators.get("volume_ratio", 1.0)))
        features.extend([
            volume_ratio,
            1.0 if tech_indicators.get("strongVolume", False) or tech_indicators.get("high_volume", False) else 0.0,
            min(volume_ratio / 5.0, 1.0),
            1.0 if tech_indicators.get("volumeBreakout", False) else 0.0,
            1.0 if tech_indicators.get("volumeTrendUp", False) else 0.0,
        ])

        risk_reward = float(tech_indicators.get("riskRewardRatio", tech_indicators.get("risk_reward", 0.0)))
        features.extend([
            min(risk_reward / 10.0, 1.0),
            float(tech_indicators.get("volatility_percent", tech_indicators.get("volatilityPercent", 0.0))) / 100.0,
            float(tech_indicators.get("distanceFromSupport", 0.0)) / 100.0,
            float(tech_indicators.get("distanceFromResistance", 0.0)) / 100.0,
        ])

        features.extend([
            1.0 if tech_indicators.get("higherHighs", False) else 0.0,
            1.0 if tech_indicators.get("lowerLows", False) else 0.0,
            1.0 if tech_indicators.get("bullishStructure", tech_indicators.get("analysis_bullish_structure", False)) else 0.0,
            1.0 if tech_indicators.get("bearishStructure", tech_indicators.get("analysis_bearish_structure", False)) else 0.0,
        ])

        # pad/truncate to expected feature dimension
        while len(features) < DEFAULT_FEATURE_DIM:
            features.append(0.0)
        features = features[:DEFAULT_FEATURE_DIM]

        # sanitize
        features = [float(f) if not (np.isnan(f) or np.isinf(f)) else 0.0 for f in features]
        return np.array(features, dtype=np.float32)

    # ------------------------- Model creation & utilities -------------------
    def create_model(self, n_classes: int) -> keras.Model:
        inputs = keras.Input(shape=(self.feature_dim,), name="features")
        if self.feature_dim <= 10:
            x = layers.Dense(64, activation="relu")(inputs)
            x = layers.Dropout(0.3)(x)
            x = layers.Dense(32, activation="relu")(x)
            x = layers.Dropout(0.2)(x)
        else:
            x = layers.Dense(64, activation="relu", name="dense_1")(inputs)
            x = layers.BatchNormalization(name="bn_1")(x)
            x = layers.Dropout(0.3, name="dropout_1")(x)
            x = layers.Dense(32, activation="relu", name="dense_2")(x)
            x = layers.BatchNormalization(name="bn_2")(x)
            x = layers.Dropout(0.2, name="dropout_2")(x)

        outputs = layers.Dense(n_classes, activation="softmax")(x)
        model = keras.Model(inputs=inputs, outputs=outputs)
        model.compile(
            optimizer=keras.optimizers.Adam(learning_rate=1e-3),
            loss="categorical_crossentropy",
            metrics=["accuracy"],
        )
        return model

    def drop_low_variance(self, X: np.ndarray, threshold: float = 1e-5) -> Tuple[np.ndarray, np.ndarray]:
        selector = VarianceThreshold(threshold=threshold)
        try:
            X_reduced = selector.fit_transform(X)
            kept = selector.get_support(indices=True)
            logging.getLogger().info(f"drop_low_variance: kept {X_reduced.shape[1]} features (threshold={threshold})")
            return X_reduced, kept
        except Exception as exc:
            logging.getLogger().warning(f"drop_low_variance failed: {exc}")
            return X, np.arange(X.shape[1])

    def time_series_split(self, X: np.ndarray, y: np.ndarray, train_frac: float = 0.7, val_frac: float = 0.15):
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
        logging.getLogger().info(f"time_series_split -> train:{len(X_train)}, val:{len(X_val)}, test:{len(X_test)}")
        return X_train, X_val, X_test, y_train, y_val, y_test

    def adaptive_resample(self, X_train: np.ndarray, y_train: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
        counts = Counter(y_train)
        logging.getLogger().info(f"adaptive_resample: class counts before: {counts}")
        if not counts:
            return X_train, y_train

        min_count = min(counts.values())
        max_count = max(counts.values())

        if min_count < 4:
            logging.getLogger().info("Using RandomOverSampler due to very small minority class (<4)")
            ros = RandomOverSampler(random_state=SEED)
            X_res, y_res = ros.fit_resample(X_train, y_train)
            logging.getLogger().info(f"After ROS counts: {Counter(y_res)}")
            return X_res, y_res

        try:
            desired_majority = int(max_count * self.under_ratio)
            majority_label = max(counts.items(), key=lambda kv: kv[1])[0]
            under_strategy = {majority_label: desired_majority}
            rus = RandomUnderSampler(sampling_strategy=under_strategy, random_state=SEED)

            k_neigh = min(3, max(1, min_count - 1))
            smote = SMOTE(sampling_strategy="not majority", random_state=SEED, k_neighbors=k_neigh)
            pipeline = ImbPipeline([("under", rus), ("smote", smote)])
            X_res, y_res = pipeline.fit_resample(X_train, y_train)
            logging.getLogger().info(f"adaptive_resample: after pipeline counts: {Counter(y_res)}")
            return X_res, y_res
        except Exception as exc:
            logging.getLogger().warning(f"adaptive_resample pipeline failed: {exc}; falling back to ROS")
            ros = RandomOverSampler(random_state=SEED)
            X_res, y_res = ros.fit_resample(X_train, y_train)
            logging.getLogger().info(f"After fallback ROS counts: {Counter(y_res)}")
            return X_res, y_res

    # ------------------------- Training -----------------------------------
    def train_model(self, features: np.ndarray, labels: np.ndarray):
        if features.size == 0:
            raise ValueError("Empty feature matrix provided")

        logging.getLogger().info("== Raw input diagnostics ==")
        logging.getLogger().info(f"features: shape={features.shape}, dtype={features.dtype}")
        unique, counts = np.unique(labels, return_counts=True)
        logging.getLogger().info(f"label distribution (raw): {dict(zip(unique, counts))}")

        # encode labels
        label_encoder = LabelEncoder()
        encoded_labels = label_encoder.fit_transform(labels)
        n_classes = len(label_encoder.classes_)
        logging.getLogger().info(f"Detected {n_classes} classes: {label_encoder.classes_}")

        # time-series split
        X_train_raw, X_val_raw, X_test_raw, y_train_enc, y_val_enc, y_test_enc = self.time_series_split(
            features, encoded_labels, train_frac=0.7, val_frac=0.15
        )

        logging.getLogger().info(f"Train label dist (before resample): {dict(zip(*np.unique(y_train_enc, return_counts=True)))}")

        # scaler fit on train
        scaler = StandardScaler()
        X_train_scaled = scaler.fit_transform(X_train_raw)
        X_val_scaled = scaler.transform(X_val_raw)
        X_test_scaled = scaler.transform(X_test_raw)

        # drop low-variance features (fit on train)
        X_train_scaled, kept_idx = self.drop_low_variance(X_train_scaled, threshold=1e-6)
        # Apply same column selection to val/test safely
        if len(kept_idx) == X_train_scaled.shape[1]:
            # kept_idx gives indices in original feature space; we need to index val/test accordingly
            try:
                X_val_scaled = X_val_scaled[:, kept_idx]
                X_test_scaled = X_test_scaled[:, kept_idx]
            except Exception:
                logging.getLogger().warning("kept_idx application to val/test failed; skipping")
        else:
            logging.getLogger().info("No low-variance features removed (or mismatch); proceeding without column trimming on val/test")

        # resample only train
        X_resampled, y_resampled = self.adaptive_resample(X_train_scaled, y_train_enc)

        # to categorical
        y_train_cat = keras.utils.to_categorical(y_resampled, num_classes=n_classes)
        y_val_cat = keras.utils.to_categorical(y_val_enc, num_classes=n_classes)

        # build model
        self.feature_dim = X_resampled.shape[1]
        model = self.create_model(n_classes)
        model.summary(print_fn=logging.getLogger().info)

        early_stopping = EarlyStopping(monitor="val_loss", patience=8, restore_best_weights=True)
        reduce_lr = ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=4, min_lr=1e-6)

        history = model.fit(
            X_resampled,
            y_train_cat,
            validation_data=(X_val_scaled, y_val_cat),
            epochs=self.epochs,
            batch_size=self.batch_size,
            callbacks=[early_stopping, reduce_lr],
            verbose=2,
        )

        val_loss, val_accuracy = model.evaluate(X_val_scaled, y_val_cat, verbose=0)
        logging.getLogger().info(f"Validation loss: {val_loss:.4f}, accuracy: {val_accuracy:.4f}")

        y_pred = model.predict(X_val_scaled)
        y_pred_classes = np.argmax(y_pred, axis=1)
        y_val_classes = np.argmax(y_val_cat, axis=1)
        logging.getLogger().info("Classification Report:\n" + classification_report(y_val_classes, y_pred_classes, target_names=label_encoder.classes_, zero_division=0))

        # Save artifacts
        model.save(self.model_path)
        joblib.dump(scaler, self.scaler_path)
        joblib.dump(label_encoder, self.label_encoder_path)

        metadata = {
            "model_version": "1.1-adaptive",
            "training_date": datetime.now().isoformat(),
            "feature_dimension": int(self.feature_dim),
            "num_classes": int(n_classes),
            "class_names": label_encoder.classes_.tolist(),
            "training_samples": int(X_resampled.shape[0]),
            "validation_samples": int(X_val_scaled.shape[0]),
            "val_accuracy": float(val_accuracy),
            "epochs_trained": int(len(history.history.get("loss", [])))
        }
        with open(self.metadata_path, "w") as fh:
            json.dump(metadata, fh, indent=2)

        logging.getLogger().info(f"Model saved to: {self.model_path}")
        return model, history, scaler, label_encoder


# ------------------------- CLI Entrypoint -------------------------------
def parse_args():
    p = argparse.ArgumentParser(prog="train_model.py", description="Train adaptive trading model")
    p.add_argument("pattern", help="training file glob pattern (e.g. training_data_*.json)")
    p.add_argument("--epochs", type=int, default=DEFAULT_EPOCHS)
    p.add_argument("--batch", type=int, default=DEFAULT_BATCH)
    p.add_argument("--data-dir", default="src/main/resources/data")
    p.add_argument("--log-level", default="INFO")
    return p.parse_args()


def main():
    args = parse_args()
    logging.basicConfig(level=args.log_level.upper(), format="%(asctime)s %(levelname)s: %(message)s")

    trainer = TradingModelTrainer(base_dir=Path(args.data_dir))
    trainer.epochs = args.epochs
    trainer.batch_size = args.batch

    try:
        samples = trainer.load_training_data(args.pattern)
        if len(samples) < 50:
            logging.getLogger().warning(f"Only {len(samples)} training samples found — results may be poor")

        features, labels, _ = trainer.prepare_features_and_labels(samples)
        if features.size == 0:
            logging.getLogger().error("No valid features prepared — aborting")
            return 1

        logging.getLogger().info(f"Label distribution: {dict(zip(*np.unique(labels, return_counts=True)))}")
        trainer.train_model(features, labels)
        logging.getLogger().info("Training completed successfully")
        return 0
    except Exception as exc:
        logging.getLogger().exception(f"Training failed: {exc}")
        return 2


if __name__ == "__main__":
    main()
