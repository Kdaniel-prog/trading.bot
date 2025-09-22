#!/usr/bin/env python3
"""
ML Predictor Script
Loads trained model and makes predictions for new trade samples.
"""

import sys
import json
import numpy as np
import tensorflow as tf
from pathlib import Path
import joblib
from tensorflow import keras

# Suppress TF warnings
tf.get_logger().setLevel("ERROR")

BASE_DIR = Path("src/main/resources/data/models")

MODEL_PATH = BASE_DIR / "swing_trading_model.keras"
SCALER_PATH = BASE_DIR / "feature_scaler.pkl"
ENCODER_PATH = BASE_DIR / "label_encoder.pkl"


def load_artifacts():
    """Load model, scaler, and label encoder"""
    model = keras.models.load_model(MODEL_PATH)
    scaler = joblib.load(SCALER_PATH)
    label_encoder = joblib.load(ENCODER_PATH)
    return model, scaler, label_encoder


def prepare_feature_vector(sample: dict, feature_dim: int):
    """Convert input JSON sample into feature vector"""
    features = []

    # Ha "simple" formátum jön
    if "direction" in sample and "pnl_percent" in sample:
        pnl_percent = float(sample.get("pnl_percent", 0.0))
        direction = sample.get("direction", "long").lower()
        score = float(sample.get("score", 0.5))
        entry_price = float(sample.get("entry_price", 1.0))
        exit_price = float(sample.get("exit_price", entry_price))

        price_change = (exit_price - entry_price) / entry_price if entry_price > 0 else 0.0

        features = [
            1.0 if direction == "long" else -1.0,
            score,
            abs(pnl_percent) / 10.0,
            1.0 if pnl_percent > 0 else 0.0,
            price_change,
            1.0 if abs(pnl_percent) > 2.0 else 0.0,
            min(abs(score - 0.5) * 2, 1.0),
            1.0 if pnl_percent > 5.0 else 0.0,
            1.0 if pnl_percent < -5.0 else 0.0,
            0.0  # noise helyett fix 0
        ]
    # Ha "complex" formátum jön
    elif "technicalIndicators" in sample:
        tech = sample.get("technicalIndicators", {})
        current_price = sample.get("currentPrice", tech.get("currentPrice", 1.0))
        if current_price <= 0:
            current_price = 1.0

        features.extend([
            1.0 if tech.get("primaryTrend") == "BULLISH" else (
                -1.0 if tech.get("primaryTrend") == "BEARISH" else 0.0),
            1.0 if tech.get("shortTermTrend") == "BULLISH" else (
                -1.0 if tech.get("shortTermTrend") == "BEARISH" else 0.0),
            1.0 if tech.get("trendAlignment", False) else 0.0,
            float(tech.get("trendStrength", 0.0)),
            float(tech.get("ema20_4h", current_price)) / current_price,
            float(tech.get("ema50_4h", current_price)) / current_price,
            float(tech.get("rsi", 50.0)) / 100.0,
            1.0 if tech.get("macdBullish", False) else 0.0,
            1.0 if tech.get("macdBearish", False) else 0.0,
            1.0 if tech.get("rsiBullishZone", False) else 0.0,
            1.0 if tech.get("rsiBearishZone", False) else 0.0,
            1.0 if tech.get("rsiRising", False) else 0.0,
            float(tech.get("volumeRatio", 1.0)),
            1.0 if tech.get("strongVolume", False) else 0.0,
            min(float(tech.get("volumeRatio", 1.0)) / 5.0, 1.0),
            1.0 if tech.get("volumeBreakout", False) else 0.0,
            1.0 if tech.get("volumeTrendUp", False) else 0.0,
            min(float(tech.get("riskRewardRatio", 0.0)) / 10.0, 1.0),
            float(tech.get("volatilityPercent", 0.0)) / 100.0,
            float(tech.get("distanceFromSupport", 0.0)) / 100.0,
            float(tech.get("distanceFromResistance", 0.0)) / 100.0,
            1.0 if tech.get("higherHighs", False) else 0.0,
            1.0 if tech.get("lowerLows", False) else 0.0,
            1.0 if tech.get("bullishStructure", False) else 0.0,
            1.0 if tech.get("bearishStructure", False) else 0.0
        ])

    # fix dimension
    while len(features) < feature_dim:
        features.append(0.0)
    features = features[:feature_dim]

    return np.array(features, dtype=np.float32).reshape(1, -1)


def main():
    try:
        raw_input = sys.stdin.read()
        sample = json.loads(raw_input)

        model, scaler, label_encoder = load_artifacts()
        feature_dim = model.input_shape[1]

        X = prepare_feature_vector(sample, feature_dim)
        X_scaled = scaler.transform(X)

        preds = model.predict(X_scaled)
        predicted_idx = int(np.argmax(preds, axis=1)[0])
        predicted_class = label_encoder.inverse_transform([predicted_idx])[0]
        confidence = float(np.max(preds))

        result = {
            "predicted_class": predicted_class,
            "confidence": confidence
        }
        print(json.dumps(result))

    except Exception as e:
        error = {"error": str(e)}
        print(json.dumps(error))
        sys.exit(1)


if __name__ == "__main__":
    main()
