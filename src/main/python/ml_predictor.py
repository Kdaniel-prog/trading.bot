#!/usr/bin/env python3
"""
Enhanced ML Predictor Script for SwingAlgo Integration
Loads trained model and makes predictions for SwingAlgo technical analysis data.
"""

import sys
import json
import numpy as np
import tensorflow as tf
from pathlib import Path
import joblib
from tensorflow import keras
import logging

# Suppress TF warnings
tf.get_logger().setLevel("ERROR")
logging.basicConfig(level=logging.ERROR)

BASE_DIR = Path("src/main/resources/data/models")

MODEL_PATH = BASE_DIR / "swing_trading_model.keras"
SCALER_PATH = BASE_DIR / "feature_scaler.pkl"
ENCODER_PATH = BASE_DIR / "label_encoder.pkl"

# Alternative paths for different setups
ALT_MODEL_PATH = BASE_DIR / "swingalgo_model.pkl"
ALT_SCALER_PATH = BASE_DIR / "swingalgo_scaler.pkl"


def check_model_files():
    """Check which model files are available"""
    keras_available = MODEL_PATH.exists() and SCALER_PATH.exists() and ENCODER_PATH.exists()
    alt_available = ALT_MODEL_PATH.exists() and ALT_SCALER_PATH.exists()

    return keras_available, alt_available


def load_keras_artifacts():
    """Load Keras model, scaler, and label encoder"""
    model = keras.models.load_model(MODEL_PATH)
    scaler = joblib.load(SCALER_PATH)
    label_encoder = joblib.load(ENCODER_PATH)
    return model, scaler, label_encoder, "keras"


def load_sklearn_artifacts():
    """Load sklearn model and scaler"""
    model = joblib.load(ALT_MODEL_PATH)
    scaler = joblib.load(ALT_SCALER_PATH)
    return model, scaler, None, "sklearn"


def extract_technical_features(sample: dict, feature_dim: int):
    """Extract technical analysis features from SwingAlgo data"""
    features = []

    # Get technical indicators
    tech = sample.get("technicalIndicators", {})
    current_price = float(sample.get("currentPrice", tech.get("currentPrice", 1.0)))

    if current_price <= 0:
        current_price = 1.0

    # === CORE TECHNICAL FEATURES ===

    # Trend Features
    primary_trend = tech.get("primaryTrend", "NEUTRAL")
    short_term_trend = tech.get("shortTermTrend", "NEUTRAL")

    features.extend([
        1.0 if primary_trend == "BULLISH" else (-1.0 if primary_trend == "BEARISH" else 0.0),
        1.0 if short_term_trend == "BULLISH" else (-1.0 if short_term_trend == "BEARISH" else 0.0),
        1.0 if tech.get("trendAlignment", False) else 0.0,
        float(tech.get("trendStrength", 0.0)) / 10.0,  # Normalize 0-10 to 0-1
    ])

    # EMA Features
    ema20_4h = float(tech.get("ema20_4h", current_price))
    ema50_4h = float(tech.get("ema50_4h", current_price))
    ema200_daily = float(tech.get("ema200_daily", current_price))

    features.extend([
        ema20_4h / current_price,  # Price relative to EMAs
        ema50_4h / current_price,
        ema200_daily / current_price,
        ema20_4h / ema50_4h if ema50_4h > 0 else 1.0,  # EMA alignment
    ])

    # RSI Features
    rsi = float(tech.get("rsi", 50.0))
    features.extend([
        rsi / 100.0,  # Normalized RSI
        1.0 if rsi < 30 else 0.0,  # Oversold
        1.0 if rsi > 70 else 0.0,  # Overbought
        1.0 if tech.get("rsiRising", False) else 0.0,
        1.0 if tech.get("rsiBullishZone", False) else 0.0,
        1.0 if tech.get("rsiBearishZone", False) else 0.0,
    ])

    # MACD Features
    features.extend([
        1.0 if tech.get("macdBullish", False) else 0.0,
        1.0 if tech.get("macdBearish", False) else 0.0,
        float(tech.get("macdLine", 0.0)),
        float(tech.get("macdSignal", 0.0)),
        float(tech.get("macdHistogram", 0.0)),
    ])

    # Volume Features
    volume_ratio = float(tech.get("volumeRatio", 1.0))
    features.extend([
        min(volume_ratio / 3.0, 1.0),  # Normalized volume ratio
        1.0 if tech.get("strongVolume", False) else 0.0,
        1.0 if tech.get("volumeBreakout", False) else 0.0,
        1.0 if tech.get("volumeTrendUp", False) else 0.0,
        1.0 if volume_ratio > 1.5 else 0.0,  # High volume flag
    ])

    # Risk/Volatility Features
    risk_reward = float(tech.get("riskRewardRatio", 0.0))
    volatility = float(tech.get("volatilityPercent", 0.0))

    features.extend([
        min(risk_reward / 5.0, 1.0),  # Normalized risk/reward
        min(volatility / 20.0, 1.0),  # Normalized volatility
        float(tech.get("distanceFromSupport", 0.0)) / 100.0,
        float(tech.get("distanceFromResistance", 0.0)) / 100.0,
        float(tech.get("atr", 0.0)) / current_price if current_price > 0 else 0.0,
    ])

    # Market Structure Features
    features.extend([
        1.0 if tech.get("higherHighs", False) else 0.0,
        1.0 if tech.get("lowerLows", False) else 0.0,
        1.0 if tech.get("higherLows", False) else 0.0,
        1.0 if tech.get("lowerHighs", False) else 0.0,
        1.0 if tech.get("bullishStructure", False) else 0.0,
        1.0 if tech.get("bearishStructure", False) else 0.0,
        1.0 if tech.get("consolidation", False) else 0.0,
    ])

    # === DERIVED FEATURES ===

    # Price position features
    features.extend([
        1.0 if current_price > ema20_4h > ema50_4h else 0.0,  # Bullish alignment
        1.0 if current_price < ema20_4h < ema50_4h else 0.0,  # Bearish alignment
        1.0 if 30 < rsi < 70 else 0.0,  # RSI in tradeable zone
        1.0 if volume_ratio > 1.0 and (primary_trend == "BULLISH" or short_term_trend == "BULLISH") else 0.0,
        1.0 if volume_ratio > 1.0 and (primary_trend == "BEARISH" or short_term_trend == "BEARISH") else 0.0,
    ])

    # Momentum confluence features
    bullish_signals = sum([
        1 if primary_trend == "BULLISH" else 0,
        1 if short_term_trend == "BULLISH" else 0,
        1 if tech.get("macdBullish", False) else 0,
        1 if rsi > 50 else 0,
        1 if tech.get("rsiRising", False) else 0,
        1 if current_price > ema20_4h else 0,
        1 if tech.get("bullishStructure", False) else 0,
    ])

    bearish_signals = sum([
        1 if primary_trend == "BEARISH" else 0,
        1 if short_term_trend == "BEARISH" else 0,
        1 if tech.get("macdBearish", False) else 0,
        1 if rsi < 50 else 0,
        1 if not tech.get("rsiRising", True) else 0,
        1 if current_price < ema20_4h else 0,
        1 if tech.get("bearishStructure", False) else 0,
    ])

    features.extend([
        bullish_signals / 7.0,  # Normalized bullish confluence
        bearish_signals / 7.0,  # Normalized bearish confluence
        abs(bullish_signals - bearish_signals) / 7.0,  # Signal divergence
    ])

    # Ensure we have the right number of features
    while len(features) < feature_dim:
        features.append(0.0)

    # Truncate if too many features
    features = features[:feature_dim]

    return np.array(features, dtype=np.float32).reshape(1, -1)


def prepare_simple_features(sample: dict, feature_dim: int):
    """Handle simple trading data format (for backwards compatibility)"""
    features = []

    if "direction" in sample and "pnl_percent" in sample:
        pnl_percent = float(sample.get("pnl_percent", 0.0))
        direction = sample.get("direction", "long").lower()
        score = float(sample.get("score", 0.5))
        entry_price = float(sample.get("entry_price", 1.0))
        exit_price = float(sample.get("exit_price", entry_price))

        price_change = (exit_price - entry_price) / entry_price if entry_price > 0 else 0.0

        features = [
            1.0 if direction == "long" else -1.0,
            score / 10.0,  # Normalize score
            abs(pnl_percent) / 20.0,  # Normalize PnL
            1.0 if pnl_percent > 0 else 0.0,
            price_change,
            1.0 if abs(pnl_percent) > 2.0 else 0.0,
            min(abs(score - 0.5) * 2, 1.0),
            1.0 if pnl_percent > 5.0 else 0.0,
            1.0 if pnl_percent < -5.0 else 0.0,
            0.0  # padding
        ]

    # Pad or truncate to correct dimension
    while len(features) < feature_dim:
        features.append(0.0)
    features = features[:feature_dim]

    return np.array(features, dtype=np.float32).reshape(1, -1)


def predict_with_keras_model(model, scaler, label_encoder, X):
    """Make prediction using Keras model"""
    try:
        X_scaled = scaler.transform(X)
        predictions = model.predict(X_scaled, verbose=0)

        predicted_idx = int(np.argmax(predictions, axis=1)[0])
        predicted_class = label_encoder.inverse_transform([predicted_idx])[0]
        confidence = float(np.max(predictions))

        # Get all class probabilities for debugging
        class_probabilities = {}
        for i, prob in enumerate(predictions[0]):
            class_name = label_encoder.inverse_transform([i])[0]
            class_probabilities[class_name] = float(prob)

        return {
            "predicted_class": predicted_class,
            "confidence": confidence,
            "class_probabilities": class_probabilities,
            "model_type": "keras"
        }

    except Exception as e:
        return {"error": f"Keras prediction failed: {str(e)}"}


def predict_with_sklearn_model(model, scaler, X):
    """Make prediction using sklearn model"""
    try:
        X_scaled = scaler.transform(X)

        # Get prediction and probabilities
        prediction = model.predict(X_scaled)[0]

        if hasattr(model, 'predict_proba'):
            probabilities = model.predict_proba(X_scaled)[0]
            confidence = float(max(probabilities))

            # Map probabilities to classes
            classes = model.classes_
            class_probabilities = dict(zip(classes, [float(p) for p in probabilities]))
        else:
            confidence = 0.8  # Default confidence for models without probability
            class_probabilities = {prediction: confidence}

        return {
            "predicted_class": str(prediction),
            "confidence": confidence,
            "class_probabilities": class_probabilities,
            "model_type": "sklearn"
        }

    except Exception as e:
        return {"error": f"Sklearn prediction failed: {str(e)}"}


def validate_input(sample):
    """Validate input data format"""
    if not isinstance(sample, dict):
        return False, "Input must be a JSON object"

    # Check for technical indicators format (preferred)
    if "technicalIndicators" in sample:
        tech = sample.get("technicalIndicators", {})
        if not isinstance(tech, dict):
            return False, "technicalIndicators must be an object"
        return True, "Technical format validated"

    # Check for simple format (fallback)
    if "direction" in sample or "pnl_percent" in sample:
        return True, "Simple format validated"

    return False, "Unknown input format - need technicalIndicators or simple trading data"


def main():
    try:
        # Read input from stdin
        raw_input = sys.stdin.read().strip()
        if not raw_input:
            print(json.dumps({"error": "No input data received"}))
            sys.exit(1)

        # Parse JSON
        try:
            sample = json.loads(raw_input)
        except json.JSONDecodeError as e:
            print(json.dumps({"error": f"Invalid JSON: {str(e)}"}))
            sys.exit(1)

        # Validate input
        is_valid, validation_msg = validate_input(sample)
        if not is_valid:
            print(json.dumps({"error": validation_msg}))
            sys.exit(1)

        # Check available models
        keras_available, sklearn_available = check_model_files()

        if not keras_available and not sklearn_available:
            print(json.dumps({
                "error": "No trained models found",
                "keras_checked": str(MODEL_PATH),
                "sklearn_checked": str(ALT_MODEL_PATH)
            }))
            sys.exit(1)

        # Load model artifacts
        if keras_available:
            model, scaler, label_encoder, model_type = load_keras_artifacts()
        else:
            model, scaler, label_encoder, model_type = load_sklearn_artifacts()

        # Determine feature dimension from model
        if model_type == "keras":
            feature_dim = model.input_shape[1]
        else:
            # For sklearn, try to get feature count from scaler
            if hasattr(scaler, 'n_features_in_'):
                feature_dim = scaler.n_features_in_
            else:
                feature_dim = 40  # Default fallback

        # Prepare features based on input format
        if "technicalIndicators" in sample:
            X = extract_technical_features(sample, feature_dim)
        else:
            X = prepare_simple_features(sample, feature_dim)

        # Make prediction
        if model_type == "keras":
            result = predict_with_keras_model(model, scaler, label_encoder, X)
        else:
            result = predict_with_sklearn_model(model, scaler, X)

        # Add metadata
        result["input_format"] = "technical" if "technicalIndicators" in sample else "simple"
        result["feature_count"] = X.shape[1]
        result["validation_message"] = validation_msg

        print(json.dumps(result))

    except Exception as e:
        error_result = {
            "error": f"Prediction failed: {str(e)}",
            "error_type": type(e).__name__
        }
        print(json.dumps(error_result))
        sys.exit(1)


if __name__ == "__main__":
    main()