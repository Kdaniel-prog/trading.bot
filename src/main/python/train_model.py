"""
ML Model Training Module
"""

import json
import pickle
import sys
import logging
import pandas as pd
from pathlib import Path
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler, OneHotEncoder, LabelEncoder
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, mean_squared_error, r2_score
import xgboost as xgb
from tensorflow import keras
import glob
from config import MLConfig

logger = logging.getLogger(__name__)

class TradingModelTrainer:
    def __init__(self, model_name="swing_trader"):
        """
        Trading Model Trainer - uses backtest results for supervised learning
        """
        if Path("src/main/resources/data").exists():
            self.base_dir = Path("src/main/resources/data")
        elif Path("../resources/data").exists():
            self.base_dir = Path("../resources/data")
        elif Path("../../resources/data").exists():
            self.base_dir = Path("../../resources/data")
        else:
            self.base_dir = Path("src/main/resources/data")

        self.model_dir = self.base_dir / "models"
        self.training_dir = self.base_dir / "training"

        self.model_dir.mkdir(parents=True, exist_ok=True)
        self.training_dir.mkdir(parents=True, exist_ok=True)

        self.models = {}
        self.scaler = StandardScaler()
        self.model_name = model_name

        logger.info(f"Initialized ML Trainer for model: {model_name}")

    def create_neural_network(self, input_shape, task='classification'):
        """Create neural network model"""
        model = keras.Sequential()
        model.add(keras.layers.Dense(
            MLConfig.NEURAL_NET_CONFIG['layers'][0],
            input_shape=(input_shape,),
            activation='relu'
        ))
        model.add(keras.layers.Dropout(MLConfig.NEURAL_NET_CONFIG['dropout']))

        for units in MLConfig.NEURAL_NET_CONFIG['layers'][1:]:
            model.add(keras.layers.Dense(units, activation='relu'))
            model.add(keras.layers.Dropout(MLConfig.NEURAL_NET_CONFIG['dropout']))

        if task == 'classification':
            model.add(keras.layers.Dense(1, activation='sigmoid'))
            model.compile(
                optimizer=keras.optimizers.Adam(MLConfig.NEURAL_NET_CONFIG['learning_rate']),
                loss='binary_crossentropy',
                metrics=['accuracy']
            )
        else:
            model.add(keras.layers.Dense(1, activation='linear'))
            model.compile(
                optimizer=keras.optimizers.Adam(MLConfig.NEURAL_NET_CONFIG['learning_rate']),
                loss='mse',
                metrics=['mae']
            )

        return model

    def train_ensemble_model(self, X_train, y_train, X_test, y_test):
        """Train ensemble of models"""
        logger.info("Training ensemble models...")

        X_train_scaled = self.scaler.fit_transform(X_train)
        X_test_scaled = self.scaler.transform(X_test)

        y_train_class = (y_train > 0).astype(int)
        y_test_class = (y_test > 0).astype(int)

        performance = {}

        # XGBoost Classifier
        logger.info("Training XGBoost Classifier...")
        xgb_clf = xgb.XGBClassifier(**MLConfig.XGBOOST_CONFIG['classifier'])
        xgb_clf.fit(X_train_scaled, y_train_class)
        y_pred_clf = xgb_clf.predict(X_test_scaled)

        performance['xgb_classifier'] = {
            'accuracy': accuracy_score(y_test_class, y_pred_clf),
            'precision': precision_score(y_test_class, y_pred_clf, zero_division=0),
            'recall': recall_score(y_test_class, y_pred_clf, zero_division=0),
            'f1': f1_score(y_test_class, y_pred_clf, zero_division=0)
        }
        self.models['xgb_classifier'] = xgb_clf

        # Neural Net Classifier
        logger.info("Training Neural Network Classifier...")
        nn_clf = self.create_neural_network(X_train_scaled.shape[1], 'classification')
        early_stopping = keras.callbacks.EarlyStopping(
            patience=MLConfig.NEURAL_NET_CONFIG['early_stopping_patience'],
            restore_best_weights=True
        )
        nn_clf.fit(
            X_train_scaled, y_train_class,
            epochs=MLConfig.NEURAL_NET_CONFIG['epochs'],
            batch_size=MLConfig.NEURAL_NET_CONFIG['batch_size'],
            validation_data=(X_test_scaled, y_test_class),
            callbacks=[early_stopping],
            verbose=0
        )
        y_pred_nn = (nn_clf.predict(X_test_scaled) > 0.5).astype(int).flatten()

        performance['neural_net_classifier'] = {
            'accuracy': accuracy_score(y_test_class, y_pred_nn),
            'precision': precision_score(y_test_class, y_pred_nn, zero_division=0),
            'recall': recall_score(y_test_class, y_pred_nn, zero_division=0),
            'f1': f1_score(y_test_class, y_pred_nn, zero_division=0)
        }
        self.models['neural_net_classifier'] = nn_clf

        logger.info("Ensemble training completed")
        return performance

    def train_model(self, features, labels):
        """Wrapper to train ensemble model"""
        X_train, X_test, y_train, y_test = train_test_split(
            features, labels, test_size=0.2, random_state=42
        )
        results = self.train_ensemble_model(X_train, y_train, X_test, y_test)
        return results

    def save_models(self):
        """Save trained models and scaler"""
        for name, model in self.models.items():
            if isinstance(model, keras.Model):
                # csak .keras formátum
                path = self.model_dir / f"{name}.keras"
                model.save(path)
            else:
                # pickle az XGBoost, scaler, stb.
                path = self.model_dir / f"{name}.pkl"
                with open(path, "wb") as f:
                    pickle.dump(model, f)
            logger.info(f"Saved model: {path}")

        # külön elmentjük a scalert is
        scaler_path = self.model_dir / "scaler.pkl"
        with open(scaler_path, "wb") as f:
            pickle.dump(self.scaler, f)
        logger.info(f"Saved scaler: {scaler_path}")

    # TODO: implement your data loading + feature prep here
    def load_training_data(self, training_pattern):
        # Ha wildcard van a fájlnévben → globbal kikeressük
        if "*" in training_pattern or "?" in training_pattern:
            matches = glob.glob(str(self.training_dir / training_pattern))
            if not matches:
                raise FileNotFoundError(f"No training files match pattern: {training_pattern}")
            # Legújabb fájl kiválasztása
            path = max(matches, key=lambda f: Path(f).stat().st_mtime)
        else:
            path = Path(training_pattern)
            if not path.exists():
                path = self.training_dir / Path(training_pattern).name

        if not Path(path).exists():
            raise FileNotFoundError(f"Training file not found: {path}")

        with open(path, "r") as f:
            data = json.load(f)
        return data["samples"]

    def prepare_features_and_labels(self, samples):
        df = pd.DataFrame(samples)

        # bontsuk ki a technicalIndicators mezőt külön oszlopokra
        indicators = pd.json_normalize(df["technicalIndicators"])
        df = pd.concat([df.drop(columns=["technicalIndicators"]), indicators], axis=1)

        # --- Target: actualOutcome (klasszifikáció) ---
        le = LabelEncoder()
        labels = le.fit_transform(df["actualOutcome"].astype(str))
        self.label_encoder = le  # később prediction dekódoláshoz

        # --- Feature készítés ---
        drop_cols = [
            "symbol", "timestamp", "timeframe",
            "actualSignal", "actualReturn", "actualOutcome", "exitReason"
        ]
        features_df = df.drop(columns=[c for c in drop_cols if c in df.columns])

        # Különválasztjuk a numerikus és a kategóriás oszlopokat
        categorical_cols = features_df.select_dtypes(include=["object", "bool"]).columns
        numeric_cols = features_df.select_dtypes(exclude=["object", "bool"]).columns

        # One-hot encode a kategóriás oszlopokra
        if len(categorical_cols) > 0:
            ohe = OneHotEncoder(sparse_output=False, handle_unknown="ignore")
            cat_encoded = ohe.fit_transform(features_df[categorical_cols].astype(str))
            cat_feature_names = ohe.get_feature_names_out(categorical_cols)
            cat_df = pd.DataFrame(cat_encoded, columns=cat_feature_names, index=features_df.index)
        else:
            cat_df = pd.DataFrame(index=features_df.index)

        # Numerikus oszlopok
        num_df = features_df[numeric_cols]

        # Összefűzés
        final_features = pd.concat([num_df, cat_df], axis=1)

        # numpy array-ként visszaadjuk
        features = final_features.values
        return features, labels, df

def main():
    print("=== DEBUG: Starting main() ===")

    if len(sys.argv) < 2:
        print("Usage: python train_model.py <training_data_file> [epochs]")
        sys.exit(1)

    training_pattern = sys.argv[1]
    epochs = int(sys.argv[2]) if len(sys.argv) > 2 else 50

    print(f"DEBUG: training_pattern = {training_pattern}")
    print(f"DEBUG: epochs = {epochs}")

    try:
        print("DEBUG: Initializing trainer...")
        trainer = TradingModelTrainer()
        trainer.epochs = epochs

        print("DEBUG: Loading training data...")
        samples = trainer.load_training_data(training_pattern)
        print(f"DEBUG: Loaded {len(samples)} samples")

        print("DEBUG: Preparing features and labels...")
        features, labels, _ = trainer.prepare_features_and_labels(samples)
        print(f"DEBUG: Prepared {len(features)} features")

        print("DEBUG: Starting model training...")
        results = trainer.train_model(features, labels)
        print("DEBUG: Model training completed")

        print("DEBUG: Saving models...")
        trainer.save_models()

        print("Training completed successfully!")
        print("Performance:", results)

    except Exception as e:
        print(f"DEBUG: Exception caught: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
