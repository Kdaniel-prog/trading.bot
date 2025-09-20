#!/usr/bin/env python3
"""
Main entry point for ML trading system
Usage: python main.py [command] [options]
"""

import argparse
import sys
import os
import logging
from pathlib import Path

# Add project root to path
project_root = Path(__file__).parent.parent
sys.path.append(str(project_root))

from config import MLConfig
from train_model import TradingMLTrainer
from predict import TradingMLPredictor
from evaluate_model import ModelEvaluator
from data_loader import DataLoader

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler('ml_trading.log')
    ]
)

logger = logging.getLogger(__name__)

def train_command(args):
    """Train ML model from backtest data"""
    logger.info(f"Starting model training: {args.model_name}")

    try:
        # Initialize trainer
        trainer = TradingMLTrainer(
            model_name=args.model_name,
            data_path=args.data_path,
            model_path=args.model_path
        )

        # Load and prepare training data
        logger.info("Loading training data...")
        data_loader = DataLoader(args.data_path)
        training_data = data_loader.load_training_data()

        if len(training_data) == 0:
            logger.error("No training data found!")
            return False

        logger.info(f"Loaded {len(training_data)} training samples")

        # Split data
        X_train, X_test, y_train, y_test = data_loader.prepare_training_data(training_data)

        # Train model
        logger.info("Training ensemble model...")
        model_info = trainer.train_ensemble_model(X_train, y_train, X_test, y_test)

        # Save model
        logger.info("Saving trained model...")
        trainer.save_model()

        logger.info(f"Training completed successfully!")
        logger.info(f"Model performance: {model_info}")

        return True

    except Exception as e:
        logger.error(f"Training failed: {str(e)}")
        return False

def predict_command(args):
    """Make prediction using trained model"""
    logger.info(f"Making prediction with model: {args.model_name}")

    try:
        # Initialize predictor
        predictor = TradingMLPredictor(
            model_name=args.model_name,
            model_path=args.model_path
        )

        # Load features from file
        if args.features_file:
            logger.info(f"Loading features from: {args.features_file}")
            features = predictor.load_features_from_file(args.features_file)
        else:
            logger.error("No features file provided")
            return False

        # Make prediction
        logger.info("Making prediction...")
        prediction = predictor.predict(features)

        # Output prediction as JSON (for Java integration)
        import json
        print(json.dumps(prediction, indent=2))

        return True

    except Exception as e:
        logger.error(f"Prediction failed: {str(e)}")
        return False

def evaluate_command(args):
    """Evaluate trained model performance"""
    logger.info(f"Evaluating model: {args.model_name}")

    try:
        # Initialize evaluator
        evaluator = ModelEvaluator(
            model_name=args.model_name,
            model_path=args.model_path,
            test_data_path=args.test_data_path
        )

        # Load test data
        logger.info("Loading test data...")
        data_loader = DataLoader(args.test_data_path)
        test_data = data_loader.load_test_data()

        if len(test_data) == 0:
            logger.warning("No test data found, using validation split")
            test_data = data_loader.load_training_data()

        # Evaluate model
        logger.info("Evaluating model performance...")
        evaluation_results = evaluator.evaluate_model(test_data)

        # Output results as JSON
        import json
        print(json.dumps(evaluation_results, indent=2))

        return True

    except Exception as e:
        logger.error(f"Evaluation failed: {str(e)}")
        return False

def info_command(args):
    """Get model information"""
    logger.info(f"Getting info for model: {args.model_name}")

    try:
        model_path = Path(args.model_path) / f"{args.model_name}_info.json"

        if model_path.exists():
            import json
            with open(model_path, 'r') as f:
                model_info = json.load(f)
            print(json.dumps(model_info, indent=2))
        else:
            print(json.dumps({
                "error": f"Model info not found: {model_path}",
                "model_exists": False
            }))

        return True

    except Exception as e:
        logger.error(f"Info command failed: {str(e)}")
        return False

def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(description='ML Trading System')
    subparsers = parser.add_subparsers(dest='command', help='Available commands')

    # Train command
    train_parser = subparsers.add_parser('train', help='Train ML model')
    train_parser.add_argument('--model-name', required=True, help='Name of the model')
    train_parser.add_argument('--data-path', required=True, help='Path to training data')
    train_parser.add_argument('--model-path', required=True, help='Path to save model')

    # Predict command
    predict_parser = subparsers.add_parser('predict', help='Make prediction')
    predict_parser.add_argument('--model-name', required=True, help='Name of the model')
    predict_parser.add_argument('--model-path', required=True, help='Path to model files')
    predict_parser.add_argument('--features-file', required=True, help='JSON file with features')
    predict_parser.add_argument('--symbol', help='Trading symbol')

    # Evaluate command
    evaluate_parser = subparsers.add_parser('evaluate', help='Evaluate model')
    evaluate_parser.add_argument('--model-name', required=True, help='Name of the model')
    evaluate_parser.add_argument('--model-path', required=True, help='Path to model files')
    evaluate_parser.add_argument('--test-data-path', required=True, help='Path to test data')

    # Info command
    info_parser = subparsers.add_parser('info', help='Get model info')
    info_parser.add_argument('--model-name', required=True, help='Name of the model')
    info_parser.add_argument('--model-path', required=True, help='Path to model files')

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        return False

    # Execute command
    success = False
    if args.command == 'train':
        success = train_command(args)
    elif args.command == 'predict':
        success = predict_command(args)
    elif args.command == 'evaluate':
        success = evaluate_command(args)
    elif args.command == 'info':
        success = info_command(args)
    else:
        logger.error(f"Unknown command: {args.command}")
        return False

    if success:
        logger.info(f"Command '{args.command}' completed successfully")
        sys.exit(0)
    else:
        logger.error(f"Command '{args.command}' failed")
        sys.exit(1)

if __name__ == '__main__':
    main()