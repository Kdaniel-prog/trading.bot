"""
Directional Trading Model - Long/Short/Hold prediction
Supports Binance Futures trading with directional signals
"""

import json
import pickle
import sys
from pathlib import Path
import glob
import logging
import pandas as pd
import numpy as np
from sklearn.preprocessing import StandardScaler, OneHotEncoder, LabelEncoder
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, classification_report, confusion_matrix
from sklearn.model_selection import train_test_split
import xgboost as xgb
from tensorflow import keras
from datetime import datetime
import warnings
warnings.filterwarnings('ignore')

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

class DirectionalTradingModelTrainer:
    def __init__(self, keep_latest_models=5, prediction_mode="directional"):
        """
        Directional trader that predicts LONG/SHORT/HOLD

        prediction_mode:
        - "directional": 3-class (LONG/SHORT/HOLD)
        - "multi_output": Separate models for direction and confidence
        """
        # Path setup
        script_dir = Path(__file__).parent
        project_root = script_dir.parent.parent

        possible_data_paths = [
            Path("E:/work/trading.bot/src/data"),
            project_root / "src" / "data",
            script_dir.parent / "data"
        ]

        self.base_dir = None
        for data_path in possible_data_paths:
            if data_path.exists():
                self.base_dir = data_path
                break

        if self.base_dir is None:
            self.base_dir = Path("E:/work/trading.bot/src/data")

        self.model_dir = self.base_dir / "models"
        self.training_dir = self.base_dir / "training"
        self.model_dir.mkdir(parents=True, exist_ok=True)
        self.training_dir.mkdir(parents=True, exist_ok=True)

        self.models = {}
        self.scaler = StandardScaler()
        self.label_encoder = None
        self.feature_encoder = None
        self.keep_latest_models = keep_latest_models
        self.prediction_mode = prediction_mode

        logger.info(f"Using data directory: {self.base_dir}")
        logger.info(f"Prediction mode: {prediction_mode}")

    def load_training_data(self, pattern="training_data_*.json"):
        """Load training data"""
        possible_patterns = [
            str(self.training_dir / pattern),
            str(self.base_dir / pattern),
            pattern
        ]

        files = []
        for pat in possible_patterns:
            found_files = glob.glob(pat)
            if found_files:
                files = found_files
                break

        if not files:
            raise FileNotFoundError(f"No training files found with pattern {pattern}")

        latest_file = max(files, key=lambda f: Path(f).stat().st_mtime)
        logger.info(f"Loading training data from: {latest_file}")

        with open(latest_file, "r") as f:
            data = json.load(f)

        samples = data["samples"]
        logger.info(f"Loaded {len(samples)} training samples")

        return samples

    def create_directional_labels(self, df):
        """
        Create directional labels from training data

        Expected data structure in samples:
        - actualOutcome: PROFIT/LOSS/NEUTRAL
        - actualSignal: BUY/SELL/HOLD (if available)
        - actualReturn: numeric return percentage
        """
        logger.info("=== CREATING DIRECTIONAL LABELS ===")

        # Method 1: Use actualSignal if available
        if "actualSignal" in df.columns and df["actualSignal"].notna().any():
            logger.info("Using actualSignal for direction")
            signal_col = df["actualSignal"]

            # Map signals to directions
            direction_map = {
                'BUY': 1, 'LONG': 1, 'UP': 1,
                'SELL': 2, 'SHORT': 2, 'DOWN': 2,
                'HOLD': 0, 'WAIT': 0, 'NEUTRAL': 0, 'NO_SIGNAL': 0
            }

            # Map using string matching
            directions = signal_col.astype(str).str.upper().map(direction_map)

            # Fill unmapped with HOLD
            directions = directions.fillna(0)

        # Method 2: Use actualReturn for direction
        elif "actualReturn" in df.columns and df["actualReturn"].notna().any():
            logger.info("Using actualReturn for direction")
            returns = df["actualReturn"].astype(float)

            # Define thresholds for meaningful moves
            long_threshold = 0.5   # 0.5% gain for LONG
            short_threshold = -0.5 # 0.5% loss for SHORT

            directions = np.where(returns > long_threshold, 1,    # LONG
                         np.where(returns < short_threshold, 2,   # SHORT
                                  0))                             # HOLD

        # Method 3: Use actualOutcome with synthetic direction
        elif "actualOutcome" in df.columns:
            logger.info("Using actualOutcome with synthetic direction")
            outcome_col = df["actualOutcome"].astype(str).str.upper()

            # If we have profit/loss but no direction, we need to guess
            # This is not ideal but works for initial training

            directions = []
            for i, outcome in enumerate(outcome_col):
                if outcome in ['PROFIT', 'WIN', 'POSITIVE']:
                    # Randomly assign LONG or SHORT for profitable trades
                    # In practice, this should come from your actual trading logic
                    directions.append(np.random.choice([1, 2]))
                else:
                    directions.append(0)  # HOLD for losses/neutral

        else:
            raise ValueError("No suitable columns found for direction labeling!")

        directions = np.array(directions, dtype=int)

        # Log distribution
        direction_counts = pd.Series(directions).value_counts().sort_index()
        direction_labels = {0: 'HOLD', 1: 'LONG', 2: 'SHORT'}

        logger.info("Direction distribution:")
        for direction, count in direction_counts.items():
            label = direction_labels.get(direction, f"Unknown_{direction}")
            percentage = count / len(directions) * 100
            logger.info(f"  {label}: {count} ({percentage:.1f}%)")

        # Check for reasonable distribution
        if len(direction_counts) < 2:
            logger.warning("Only one direction class found - this will cause training issues!")

        total_action = direction_counts.get(1, 0) + direction_counts.get(2, 0)
        hold_ratio = direction_counts.get(0, 0) / len(directions)

        if hold_ratio > 0.8:
            logger.warning(f"Too many HOLD signals ({hold_ratio:.1%}) - consider adjusting thresholds")

        return directions

    def debug_and_prepare_data(self, samples):
        """Debug data and prepare features/labels for directional trading"""
        logger.info("=== DATA PREPARATION FOR DIRECTIONAL TRADING ===")

        df = pd.DataFrame(samples)
        logger.info(f"Original samples: {len(df)}")
        logger.info(f"Columns: {df.columns.tolist()}")

        # Expand technical indicators
        if "technicalIndicators" in df.columns:
            indicators = pd.json_normalize(df["technicalIndicators"])
            df = pd.concat([df.drop(columns=["technicalIndicators"]), indicators], axis=1)
            logger.info(f"After expanding indicators: {len(df.columns)} columns")

        # Create directional labels
        directions = self.create_directional_labels(df)

        # Prepare features
        drop_cols = [
            "symbol", "timestamp", "timeframe", "actualSignal",
            "actualReturn", "actualOutcome", "exitReason"
        ]
        features_df = df.drop(columns=[c for c in drop_cols if c in df.columns])

        # Handle categorical columns
        categorical_cols = features_df.select_dtypes(include=["object", "bool"]).columns.tolist()
        numeric_cols = features_df.select_dtypes(exclude=["object", "bool"]).columns.tolist()

        logger.info(f"Categorical columns ({len(categorical_cols)}): {categorical_cols}")
        logger.info(f"Numeric columns ({len(numeric_cols)}): {numeric_cols}")

        # One-hot encode categorical variables
        if categorical_cols:
            if self.feature_encoder is None:
                self.feature_encoder = OneHotEncoder(sparse_output=False, handle_unknown="ignore")
                cat_encoded = self.feature_encoder.fit_transform(features_df[categorical_cols].astype(str))
            else:
                cat_encoded = self.feature_encoder.transform(features_df[categorical_cols].astype(str))

            cat_feature_names = self.feature_encoder.get_feature_names_out(categorical_cols)
            cat_df = pd.DataFrame(cat_encoded, columns=cat_feature_names, index=features_df.index)
        else:
            cat_df = pd.DataFrame(index=features_df.index)

        # Combine features
        num_df = features_df[numeric_cols]
        final_features = pd.concat([num_df, cat_df], axis=1)

        # Clean data
        nan_count = final_features.isna().sum().sum()
        if nan_count > 0:
            logger.warning(f"Found {nan_count} NaN values, filling with 0")
            final_features = final_features.fillna(0)

        numeric_features = final_features.select_dtypes(include=[np.number])
        inf_mask = np.isinf(numeric_features)
        if inf_mask.any().any():
            logger.warning("Found infinite values, replacing with 0")
            final_features = final_features.replace([np.inf, -np.inf], 0)

        features = final_features.values

        logger.info(f"Final feature matrix shape: {features.shape}")
        logger.info(f"Feature stats: min={features.min():.3f}, max={features.max():.3f}")

        return features, directions, final_features.columns.tolist()

    def create_directional_neural_network(self, input_dim, n_classes=3):
        """Create neural network for directional prediction"""

        model = keras.Sequential([
            keras.layers.Dense(256, input_shape=(input_dim,), activation='relu',
                             kernel_regularizer=keras.regularizers.l2(0.001)),
            keras.layers.BatchNormalization(),
            keras.layers.Dropout(0.4),

            keras.layers.Dense(128, activation='relu',
                             kernel_regularizer=keras.regularizers.l2(0.001)),
            keras.layers.BatchNormalization(),
            keras.layers.Dropout(0.3),

            keras.layers.Dense(64, activation='relu'),
            keras.layers.Dropout(0.2),

            keras.layers.Dense(32, activation='relu'),
            keras.layers.Dropout(0.1),

            # Output layer for 3 classes (HOLD/LONG/SHORT)
            keras.layers.Dense(n_classes, activation='softmax')
        ])

        # Use categorical crossentropy for multi-class
        optimizer = keras.optimizers.Adam(learning_rate=0.0005, clipnorm=1.0)

        model.compile(
            optimizer=optimizer,
            loss='sparse_categorical_crossentropy',  # For integer labels
            metrics=['accuracy']
        )

        return model

    def calculate_class_weights(self, labels):
        """Calculate class weights for imbalanced data"""
        unique_labels, counts = np.unique(labels, return_counts=True)
        total_samples = len(labels)

        class_weights = {}
        for label, count in zip(unique_labels, counts):
            weight = total_samples / (len(unique_labels) * count)
            class_weights[int(label)] = weight

        logger.info(f"Class weights: {class_weights}")
        return class_weights

    def train_directional_models(self, features, directions, epochs=100):
        """Train models for directional prediction"""
        logger.info("=== DIRECTIONAL MODEL TRAINING ===")

        # Split data
        X_train, X_val, y_train, y_val = train_test_split(
            features, directions, test_size=0.2, random_state=42,
            stratify=directions if len(np.unique(directions)) > 1 else None
        )

        logger.info(f"Training set: {X_train.shape}")
        logger.info(f"Validation set: {X_val.shape}")

        # Log class distribution
        train_dist = pd.Series(y_train).value_counts().sort_index()
        val_dist = pd.Series(y_val).value_counts().sort_index()
        logger.info(f"Train distribution: {dict(train_dist)}")
        logger.info(f"Val distribution: {dict(val_dist)}")

        # Scale features
        if hasattr(self, '_scaler_fitted'):
            X_train_scaled = self.scaler.transform(X_train)
            X_val_scaled = self.scaler.transform(X_val)
        else:
            X_train_scaled = self.scaler.fit_transform(X_train)
            X_val_scaled = self.scaler.transform(X_val)
            self._scaler_fitted = True

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

        # Train XGBoost for multi-class
        logger.info("Training XGBoost for directional prediction...")
        xgb_clf = xgb.XGBClassifier(
            n_estimators=300,
            max_depth=6,
            learning_rate=0.05,
            subsample=0.8,
            colsample_bytree=0.8,
            random_state=42,
            objective='multi:softprob',  # Multi-class
            num_class=3  # HOLD/LONG/SHORT
        )

        xgb_clf.fit(X_train_scaled, y_train)
        self.models[f"xgb_directional_{timestamp}"] = xgb_clf

        # Evaluate XGBoost
        xgb_pred = xgb_clf.predict(X_val_scaled)
        xgb_acc = accuracy_score(y_val, xgb_pred)

        logger.info(f"XGBoost validation accuracy: {xgb_acc:.4f}")
        logger.info("XGBoost Classification Report:")
        logger.info(f"\n{classification_report(y_val, xgb_pred, target_names=['HOLD', 'LONG', 'SHORT'])}")

        # Train Neural Network
        logger.info("Training Neural Network for directional prediction...")
        nn_clf = self.create_directional_neural_network(X_train_scaled.shape[1], n_classes=3)

        # Class weights for imbalanced data
        class_weights = self.calculate_class_weights(y_train)

        # Callbacks
        callbacks = [
            keras.callbacks.EarlyStopping(
                patience=15, restore_best_weights=True, monitor='val_loss'
            ),
            keras.callbacks.ReduceLROnPlateau(
                factor=0.5, patience=7, min_lr=1e-7, monitor='val_loss'
            )
        ]

        history = nn_clf.fit(
            X_train_scaled, y_train,
            epochs=epochs,
            batch_size=32,
            validation_data=(X_val_scaled, y_val),
            callbacks=callbacks,
            class_weight=class_weights,
            verbose=1
        )

        self.models[f"neural_net_directional_{timestamp}"] = nn_clf

        # Evaluate Neural Network
        nn_pred_proba = nn_clf.predict(X_val_scaled, verbose=0)
        nn_pred = np.argmax(nn_pred_proba, axis=1)
        nn_acc = accuracy_score(y_val, nn_pred)

        logger.info(f"Neural Network validation accuracy: {nn_acc:.4f}")
        logger.info("Neural Network Classification Report:")
        logger.info(f"\n{classification_report(y_val, nn_pred, target_names=['HOLD', 'LONG', 'SHORT'])}")

        # Confusion matrices
        logger.info("XGBoost Confusion Matrix:")
        logger.info(f"\n{confusion_matrix(y_val, xgb_pred)}")
        logger.info("Neural Network Confusion Matrix:")
        logger.info(f"\n{confusion_matrix(y_val, nn_pred)}")

        # Results
        results = {
            'xgboost': {
                'accuracy': xgb_acc,
                'predictions': xgb_pred,
                'confusion_matrix': confusion_matrix(y_val, xgb_pred).tolist()
            },
            'neural_net': {
                'accuracy': nn_acc,
                'predictions': nn_pred,
                'probabilities': nn_pred_proba,
                'confusion_matrix': confusion_matrix(y_val, nn_pred).tolist()
            },
            'validation_labels': y_val
        }

        return results

    def save_directional_models(self):
        """Save directional models"""
        logger.info("Saving directional models...")

        for name, model in self.models.items():
            if "xgb" in name:
                # Save XGBoost
                model_path = self.model_dir / f"{name}.pkl"
                with open(model_path, "wb") as f:
                    pickle.dump(model, f)

                # Current version
                current_path = self.model_dir / "xgb_directional.pkl"
                with open(current_path, "wb") as f:
                    pickle.dump(model, f)

            elif "neural_net" in name:
                # Save Neural Network
                model_path = self.model_dir / f"{name}.keras"
                model.save(model_path)

                # Current version
                current_path = self.model_dir / "neural_net_directional.keras"
                model.save(current_path)

        # Save preprocessing components
        with open(self.model_dir / "directional_scaler.pkl", "wb") as f:
            pickle.dump(self.scaler, f)

        if self.feature_encoder:
            with open(self.model_dir / "directional_feature_encoder.pkl", "wb") as f:
                pickle.dump(self.feature_encoder, f)

        # Save class mapping info
        class_info = {
            'class_mapping': {0: 'HOLD', 1: 'LONG', 2: 'SHORT'},
            'prediction_mode': self.prediction_mode,
            'model_version': datetime.now().isoformat()
        }

        with open(self.model_dir / "directional_model_info.json", "w") as f:
            json.dump(class_info, f, indent=2)

        logger.info("Directional models saved successfully")


def main():
    logger.info("=== DIRECTIONAL TRADING MODEL TRAINING ===")

    # Javított argumentum kezelés
    if len(sys.argv) < 2:
        print("Használat:")
        print("  python directional_model.py <fájl_minta> [epoch_szám]")
        print("  python directional_model.py 50  # 50 epoch alapértelmezett fájl mintával")
        print("  python directional_model.py 'training_data_*.json' 100")
        print()

        # Ha csak egy szám van megadva, azt epoch-ként kezeljük
        # és alapértelmezett fájl mintát használunk
        if len(sys.argv) == 2:
            try:
                epochs = int(sys.argv[1])
                training_pattern = "training_data_*.json"  # alapértelmezett
                print(f"Epoch szám megadva: {epochs}")
                print(f"Alapértelmezett fájl minta: {training_pattern}")
            except ValueError:
                print("Hibás argumentum!")
                sys.exit(1)
        else:
            # Ha nincs argumentum, alapértékek
            training_pattern = "training_data_*.json"
            epochs = 100
            print(f"Alapértelmezett beállítások: minta='{training_pattern}', epochs={epochs}")
    else:
        # Intelligens argumentum felismerés
        first_arg = sys.argv[1]

        # Ha az első argumentum szám, akkor epoch
        try:
            epochs = int(first_arg)
            training_pattern = "training_data_*.json"  # alapértelmezett minta
            print(f"Epoch szám: {epochs}, Alapértelmezett minta: {training_pattern}")
        except ValueError:
            # Ha nem szám, akkor fájl minta
            training_pattern = first_arg
            epochs = int(sys.argv[2]) if len(sys.argv) > 2 else 100
            print(f"Fájl minta: {training_pattern}, Epochs: {epochs}")

    try:
        # Initialize directional trainer
        trainer = DirectionalTradingModelTrainer(
            keep_latest_models=5,
            prediction_mode="directional"
        )

        # Ellenőrizzük hogy vannak-e training fájlok
        try:
            samples = trainer.load_training_data(training_pattern)
        except FileNotFoundError as e:
            logger.error(f"Nincs training adat: {e}")

            # Segítség a felhasználónak
            print("\n=== HIBAELHÁRÍTÁS ===")
            print("1. Ellenőrizd hogy léteznek-e training fájlok:")
            print(f"   Könyvtár: {trainer.training_dir}")
            print(f"   Minta: {training_pattern}")
            print()
            print("2. Ha nincsenek fájlok, generálj training adatot:")
            print("   - Futtasd a PowerShell script-et:")
            print("     .\\src\\scripts\\Train-Pipeline.ps1 -SymbolsLimit 50 -MonthsBack 6")
            print("   - Vagy generálj manuálisan training adatot")
            print()
            print("3. Fájl keresési helyek:")
            for path in trainer.base_dir.parent.glob("**/training_data_*.json"):
                print(f"   Találat: {path}")

            sys.exit(1)

        # Load and process data
        features, directions, feature_names = trainer.debug_and_prepare_data(samples)

        # Train directional models
        results = trainer.train_directional_models(features, directions, epochs)

        # Save everything
        trainer.save_directional_models()

        logger.info("=== DIRECTIONAL TRAINING COMPLETED ===")
        logger.info(f"XGBoost Accuracy: {results['xgboost']['accuracy']:.4f}")
        logger.info(f"Neural Net Accuracy: {results['neural_net']['accuracy']:.4f}")

        return results

    except Exception as e:
        logger.error(f"Directional training failed: {e}")
        import traceback
        traceback.print_exc()

        print("\n=== TOVÁBBI HIBAELHÁRÍTÁS ===")
        print("1. Ellenőrizd a TensorFlow telepítést:")
        print("   pip install tensorflow")
        print()
        print("2. Ellenőrizd a függőségeket:")
        print("   pip install -r requirements.txt")
        print()
        print("3. Ellenőrizd az adatok formátumát:")
        print("   Nézd meg a training_data_*.json fájlokat")

        sys.exit(1)
if __name__ == "__main__":
    main()