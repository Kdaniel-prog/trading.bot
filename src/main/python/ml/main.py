# main.py - Main entry point for Python ML operations
import argparse
import sys
import json
import logging
from pathlib import Path

from train_model import TradingMLTrainer
from predict import TradingMLPredictor
from evaluate_model import ModelEvaluator, ModelInfoProvider
from data_loader import FeatherDataProcessor
from config import Config

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

def train_command(args):
    """Train a new model"""
    Config.create_directories()

    trainer = TradingMLTrainer(args.model_name, str(Config.MODEL_DIR))
    results = trainer.train(args.data_path)

    print("Training Results:")
    print(json.dumps(results, indent=2))
    return results

def predict_command(args):
    """Make a prediction"""
    predictor = TradingMLPredictor(str(Config.MODEL_DIR))
    result = predictor.predict(args.features_file, args.model_name)

    print("Prediction:")
    print(json.dumps(result, indent=2))
    return result

def evaluate_command(args):
    """Evaluate a model"""
    evaluator = ModelEvaluator(str(Config.MODEL_DIR))
    results = evaluator.evaluate_model(args.model_name, args.test_data_path)

    print("Evaluation Results:")
    print(json.dumps(results, indent=2))
    return results

def info_command(args):
    """Get model information"""
    info_provider = ModelInfoProvider(str(Config.MODEL_DIR))
    info = info_provider.get_model_info(args.model_name)

    print("Model Information:")
    print(json.dumps(info, indent=2))
    return info

def prepare_data_command(args):
    """Prepare training data from feather files"""
    Config.create_directories()

    processor = FeatherDataProcessor(str(Config.FEATHER_DATA_DIR))

    if args.symbols == ["auto"]:
        symbols = processor.get_available_symbols()[:50]  # Top 50 symbols
        logger.info(f"Auto-selected {len(symbols)} symbols")
    else:
        symbols = args.symbols

    df = processor.prepare_training_dataset(
        symbols=symbols,
        timeframes=args.timeframes,
        lookback_days=args.lookback_days
    )

    # Save prepared data
    output_file = Config.DATA_DIR / f"prepared_training_data_{args.output_suffix}.feather"
    df.to_feather(output_file)

    logger.info(f"Prepared training data saved to: {output_file}")
    logger.info(f"Dataset shape: {df.shape}")

    return str(output_file)

def main():
    parser = argparse.ArgumentParser(description='Trading ML Framework')
    subparsers = parser.add_subparsers(dest='command', help='Available commands')

    # Train command
    train_parser = subparsers.add_parser('train', help='Train a new model')
    train_parser.add_argument('--model-name', default='swing_trader', help='Model name')
    train_parser.add_argument('--data-path', required=True, help='Path to training data')
    train_parser.set_defaults(func=train_command)

    # Predict command
    predict_parser = subparsers.add_parser('predict', help='Make predictions')
    predict_parser.add_argument('--features-file', required=True, help='Features JSON file')
    predict_parser.add_argument('--model-name', default='swing_trader', help='Model name')
    predict_parser.set_defaults(func=predict_command)

    # Evaluate command
    eval_parser = subparsers.add_parser('evaluate', help='Evaluate model')
    eval_parser.add_argument('--model-name', required=True, help='Model name')
    eval_parser.add_argument('--test-data-path', required=True, help='Test data path')
    eval_parser.set_defaults(func=evaluate_command)

    # Info command
    info_parser = subparsers.add_parser('info', help='Get model info')
    info_parser.add_argument('--model-name', required=True, help='Model name')
    info_parser.set_defaults(func=info_command)

    # Prepare data command
    prep_parser = subparsers.add_parser('prepare-data', help='Prepare training data from feather files')
    prep_parser.add_argument('--symbols', nargs='+', default=['auto'], help='Symbol list or "auto"')
    prep_parser.add_argument('--timeframes', nargs='+', default=['1h', '4h', '1d'], help='Timeframes')
    prep_parser.add_argument('--lookback-days', type=int, default=365, help='Days of historical data')
    prep_parser.add_argument('--output-suffix', default='latest', help='Output file suffix')
    prep_parser.set_defaults(func=prepare_data_command)

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        return

    try:
        result = args.func(args)
        logger.info("Command completed successfully")
        return result
    except Exception as e:
        logger.error(f"Command failed: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()

# docker_utils.py - Docker and deployment utilities
import subprocess
import json
import logging
from pathlib import Path

logger = logging.getLogger(__name__)

class DockerManager:
    def __init__(self, image_name="trading-ml", tag="latest"):
        self.image_name = image_name
        self.tag = tag
        self.full_image = f"{image_name}:{tag}"

    def build_image(self, dockerfile_path="Dockerfile"):
        """Build Docker image"""
        cmd = ["docker", "build", "-t", self.full_image, "-f", dockerfile_path, "."]

        try:
            result = subprocess.run(cmd, check=True, capture_output=True, text=True)
            logger.info(f"Successfully built image: {self.full_image}")
            return True
        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to build image: {e.stderr}")
            return False

    def run_training(self, data_path, model_name="swing_trader"):
        """Run training in Docker container"""
        cmd = [
            "docker", "run", "--rm",
            "-v", f"{data_path}:/app/data",
            "-v", f"{Path.cwd()}/models:/app/models",
            self.full_image,
            "python", "main.py", "train",
            "--model-name", model_name,
            "--data-path", "/app/data"
        ]

        try:
            result = subprocess.run(cmd, check=True, capture_output=True, text=True)
            logger.info("Training completed successfully in Docker")
            return json.loads(result.stdout)
        except subprocess.CalledProcessError as e:
            logger.error(f"Docker training failed: {e.stderr}")
            return None

"""
PROJEKT STRUKTÚRA DOKUMENTÁCIÓ:

Java Side (Spring Boot):
========================

src/main/java/kd/trading/bot/
├── model/
│   ├── Position.java                    # Nyitott pozíciók
│   ├── CoinAnalysis.java               # SwingAlgo elemzés eredmény
│   ├── HistoricalCandle.java           # OHLCV adatok
│   ├── backtest/
│   │   ├── BacktestTrade.java          # Lezárt kereskedések
│   │   ├── BacktestResult.java         # Backtest összesítő
│   │   ├── BacktestRuleAnalysis.java   # Rule teljesítmény elemzés
│   │   └── BacktestConfiguration.java  # Backtest beállítások
│   └── ml/
│       ├── MLFeatures.java             # ML feature-ök
│       ├── MLPrediction.java           # ML jóslatok
│       └── MLTrainingData.java         # Training adatok
├── service/
│   ├── backtest/
│   │   ├── BacktestService.java        # Fő backtest logika
│   │   └── FeatherDataLoader.java      # Feather file betöltő
│   ├── ml/
│   │   └── PythonMLService.java        # Python ML integráció
│   └── ratingProcess/algorithm/
│       └── SwingAlgoService.java       # Meglévő algo
├── web/
│   ├── BacktestController.java         # Backtest REST API
│   └── MLController.java               # ML REST API
└── config/
    └── backtest/
        └── BacktestConfigProperties.java

Python Side (ML Framework):
===========================

python/
├── ml/
│   ├── __init__.py
│   ├── main.py                         # Fő entry point
│   ├── config.py                       # Konfigurációk
│   ├── train_model.py                  # Model tanítás
│   ├── predict.py                      # Predikciók
│   ├── evaluate_model.py               # Model értékelés
│   ├── data_loader.py                  # Feather data processzálás
│   ├── docker_utils.py                 # Docker utilities
│   └── requirements.txt                # Python dependencies
├── models/                             # Tanított modellek
│   ├── swing_trader_xgb_classifier.pkl
│   ├── swing_trader_xgb_regressor.pkl
│   ├── swing_trader_neural_net_classifier.h5
│   ├── swing_trader_neural_net_regressor.h5
│   ├── swing_trader_random_forest.pkl
│   ├── swing_trader_scaler.pkl
│   └── swing_trader_info.json
└── data/
    ├── ml/                             # Training adatok (JSON)
    │   ├── training_data_20241201_120000.json
    │   └── test_data_20241201_120000.json
                       # Raw piaci adatok

    ├── 1000CAT_USDT-1d.feather
    └── 1000CAT_USDT-4h.feather
    └──1000CAT_USDT-30m.feather


Data Flow (Adatfolyam):
======================

1. BACKTESTING PHASE:
   Java: BacktestController -> BacktestService 
   -> FeatherDataLoader (betölti a feather fájlokat)
   -> SwingAlgoService (elemzi minden candlestick-et)
   -> BacktestTrade-ek generálása
   -> MLTrainingData export

2. ML TRAINING PHASE:
   Java: MLController.trainModel()
   -> PythonMLService.exportTrainingData()
   -> Python: train_model.py
   -> XGBoost + Neural Network ensemble tanítás
   -> Model mentés (pickle/h5)

3. LIVE TRADING PHASE:
   Java: SwingAlgoService.analyze()
   -> MLController.getPrediction()
   -> PythonMLService.getPrediction()
   -> Python: predict.py
   -> Ensemble prediction
   -> Trading döntés

API Endpoints:
==============

Backtest API:
- GET /api/backtest/symbols
- GET /api/backtest/symbols/{symbol}/timeframes
- POST /api/backtest/run
- POST /api/backtest/run/multi
- GET /api/backtest/quick/{symbol}

ML API:
- POST /api/ml/train
- POST /api/ml/predict
- GET /api/ml/model/{modelName}/info
- POST /api/ml/evaluate/{modelName}
- POST /api/ml/train/quick

Használati példa:
================

1. Backtest futtatás:
POST /api/backtest/run
{
  "symbol": "BTCUSDT",
  "timeframe": "4h", 
  "startDate": "2024-01-01T00:00:00",
  "endDate": "2024-06-01T00:00:00"
}

2. ML model tanítás:
POST /api/ml/train
{
  "symbols": ["BTCUSDT", "ETHUSDT", "ADAUSDT"],
  "timeframe": "4h",
  "startDate": "2024-01-01T00:00:00", 
  "endDate": "2024-11-01T00:00:00",
  "modelName": "swing_trader_v2"
}

3. ML predikció:
POST /api/ml/predict
{
  "analysis": {
    "symbol": "BTCUSDT",
    "signal": "LONG",
    "score": 0.75,
    "tradingRule": 3
  },
  "currentPrice": 45000,
  "volume": 1250000,
  "rsi": 65,
  "macd": 125.5
}

Deployment:
===========

Docker Compose setup:
```yaml
version: '3.8'
services:
  trading-bot:
    build: .
    volumes:
      - ./data:/app/data
      - ./models:/app/models
    ports:
      - "8080:8080"
  
  ml-service:
    build: ./python
    volumes:
      - ./data:/app/data  
      - ./models:/app/models
```

Environment Variables:
- ML_PYTHON_EXECUTABLE=python3
- ML_DATA_PATH=/app/data/ml
- ML_MODEL_PATH=/app/models
- FEATHER_DATA_PATH=/app/data/feather

Ez a framework lehetővé teszi:
- Gyors backtesting különböző paraméterekkel
- Automatikus ML training a backtest eredményekből
- Real-time ML predikciók élő kereskedéshez
- Ensemble modellek (XGBoost + Neural Networks)
- Multi-timeframe elemzés
- Feature engineering piaci adatokból
- Model teljesítmény monitoring

A rendszer moduláris felépítésű, könnyen bővíthető új algoritmusokkal 
és ML modellek, és production-ready a Docker containerizációval.
"""