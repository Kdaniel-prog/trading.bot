# evaluate_model.py
import argparse
import json
import numpy as np
import pandas as pd
from pathlib import Path
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, roc_auc_score
from sklearn.metrics import mean_squared_error, mean_absolute_error, r2_score
import logging
import warnings
warnings.filterwarnings('ignore')

from predict import TradingMLPredictor

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class ModelEvaluator:
    def __init__(self, model_name="swing_trader", model_path="/app/models", test_data_path="/app/data/ml"):
        self.model_name = model_name
        self.model_path = Path(model_path)
        self.test_data_path = Path(test_data_path)

        # Initialize predictor
        self.predictor = TradingMLPredictor(model_name, model_path)

    def load_test_data(self):
        """Load test data from JSON files"""
        logger.info(f"Loading test data from {self.test_data_path}")

        # Look for test data files
        test_files = list(self.test_data_path.glob("test_data_*.json"))
        if not test_files:
            # Fall back to training data files for evaluation
            test_files = list(self.test_data_path.glob("training_data_*.json"))

        all_data = []
        for file in test_files:
            try:
                with open(file, 'r') as f:
                    file_data = json.load(f)
                    all_data.extend(file_data)
                logger.info(f"Loaded {len(file_data)} samples from {file.name}")
            except Exception as e:
                logger.warning(f"Failed to load {file}: {e}")

        if not all_data:
            raise ValueError("No test data found")

        df = pd.DataFrame(all_data)

        # Convert and clean data
        df['pnlPercent'] = pd.to_numeric(df['pnlPercent'], errors='coerce').fillna(0.0)
        df['is_profitable'] = (df['pnlPercent'] > 0).astype(int)

        # Remove extreme outliers
        q99 = df['pnlPercent'].quantile(0.99)
        q01 = df['pnlPercent'].quantile(0.01)
        df = df[(df['pnlPercent'] >= q01) & (df['pnlPercent'] <= q99)]

        logger.info(f"Loaded test data: {len(df)} samples")
        logger.info(f"Profitable trades: {df['is_profitable'].sum()} ({df['is_profitable'].mean()*100:.1f}%)")

        return df

    def evaluate_model(self, test_data=None):
        """Evaluate model performance on test data"""
        try:
            if test_data is None:
                test_data = self.load_test_data()

            logger.info(f"Evaluating model on {len(test_data)} samples")

            predictions = []
            actual_profits = []
            actual_binary = []
            prediction_probabilities = []
            prediction_returns = []

            # Make predictions for each sample
            for idx, row in test_data.iterrows():
                try:
                    # Prepare features (simplified - you may need to adjust based on your feature structure)
                    features_data = {
                        'tradingRule': row.get('tradingRule', 0),
                        'algoScore': row.get('algoScore', 0),
                        'rsi': row.get('rsi', 50),
                        'macd': row.get('macd', 0),
                        'currentPrice': row.get('entryPrice', 0),
                        'volume': row.get('volume', 0),
                        'volumeRatio': row.get('volumeRatio', 1.0),
                        'sma_20': row.get('sma_20', row.get('entryPrice', 0)),
                        'ema_50': row.get('ema_50', row.get('entryPrice', 0))
                    }

                    prediction = self.predictor.predict(features_data)

                    if prediction.get('success', False):
                        predictions.append(1 if prediction['signal'] in ['LONG', 'SHORT'] else 0)
                        prediction_probabilities.append(prediction['confidence'])
                        prediction_returns.append(prediction['expected_return'])
                    else:
                        predictions.append(0)
                        prediction_probabilities.append(0.5)
                        prediction_returns.append(0.0)

                    actual_profits.append(row['pnlPercent'])
                    actual_binary.append(row['is_profitable'])

                except Exception as e:
                    logger.warning(f"Prediction failed for row {idx}: {e}")
                    predictions.append(0)
                    prediction_probabilities.append(0.5)
                    prediction_returns.append(0.0)
                    actual_profits.append(row['pnlPercent'])
                    actual_binary.append(row['is_profitable'])

            # Convert to numpy arrays
            predictions = np.array(predictions)
            actual_binary = np.array(actual_binary)
            actual_profits = np.array(actual_profits)
            prediction_probabilities = np.array(prediction_probabilities)
            prediction_returns = np.array(prediction_returns)

            # Classification Metrics
            classification_metrics = {}
            if len(np.unique(actual_binary)) > 1 and len(np.unique(predictions)) > 1:
                classification_metrics = {
                    'accuracy': float(accuracy_score(actual_binary, predictions)),
                    'precision': float(precision_score(actual_binary, predictions, zero_division=0)),
                    'recall': float(recall_score(actual_binary, predictions, zero_division=0)),
                    'f1_score': float(f1_score(actual_binary, predictions, zero_division=0)),
                }

                try:
                    classification_metrics['auc_score'] = float(roc_auc_score(actual_binary, prediction_probabilities))
                except:
                    classification_metrics['auc_score'] = 0.5

            # Regression Metrics (for return prediction)
            regression_metrics = {
                'mse': float(mean_squared_error(actual_profits, prediction_returns)),
                'mae': float(mean_absolute_error(actual_profits, prediction_returns)),
                'rmse': float(np.sqrt(mean_squared_error(actual_profits, prediction_returns))),
            }

            try:
                regression_metrics['r2_score'] = float(r2_score(actual_profits, prediction_returns))
            except:
                regression_metrics['r2_score'] = 0.0

            # Directional accuracy
            direction_correct = (actual_profits > 0) == (prediction_returns > 0)
            regression_metrics['direction_accuracy'] = float(np.mean(direction_correct))

            # Trading Metrics
            predicted_trades_mask = predictions == 1
            if np.sum(predicted_trades_mask) > 0:
                predicted_trade_returns = actual_profits[predicted_trades_mask]

                trading_metrics = {
                    'total_predicted_trades': int(np.sum(predicted_trades_mask)),
                    'predicted_win_rate': float(np.mean(predicted_trade_returns > 0)),
                    'avg_predicted_return': float(np.mean(predicted_trade_returns)),
                    'total_predicted_profit': float(np.sum(predicted_trade_returns)),
                    'best_predicted_trade': float(np.max(predicted_trade_returns)),
                    'worst_predicted_trade': float(np.min(predicted_trade_returns)),
                    'profitable_predictions': int(np.sum(predicted_trade_returns > 0)),
                    'unprofitable_predictions': int(np.sum(predicted_trade_returns <= 0))
                }

                # Sharpe-like ratio for predicted trades
                if len(predicted_trade_returns) > 1 and np.std(predicted_trade_returns) > 0:
                    trading_metrics['sharpe_ratio'] = float(np.mean(predicted_trade_returns) / np.std(predicted_trade_returns))
                else:
                    trading_metrics['sharpe_ratio'] = 0.0
            else:
                trading_metrics = {
                    'total_predicted_trades': 0,
                    'predicted_win_rate': 0.0,
                    'avg_predicted_return': 0.0,
                    'total_predicted_profit': 0.0,
                    'sharpe_ratio': 0.0
                }

            # Overall Performance Summary
            baseline_accuracy = max(np.mean(actual_binary), 1 - np.mean(actual_binary))
            baseline_return = np.mean(actual_profits)

            summary = {
                'test_samples': len(test_data),
                'baseline_accuracy': float(baseline_accuracy),
                'baseline_avg_return': float(baseline_return),
                'model_improvement': {
                    'accuracy_vs_baseline': float(classification_metrics.get('accuracy', 0) - baseline_accuracy),
                    'return_vs_baseline': float(trading_metrics.get('avg_predicted_return', 0) - baseline_return)
                }
            }

            # Final Results
            evaluation_results = {
                'success': True,
                'model_name': self.model_name,
                'evaluation_timestamp': pd.Timestamp.now().isoformat(),
                'classification_metrics': classification_metrics,
                'regression_metrics': regression_metrics,
                'trading_metrics': trading_metrics,
                'summary': summary,
                'model_info': self.predictor.model_info
            }

            logger.info("="*50)
            logger.info("MODEL EVALUATION COMPLETED")
            logger.info(f"Test samples: {len(test_data)}")
            if classification_metrics:
                logger.info(f"Classification Accuracy: {classification_metrics.get('accuracy', 0):.3f}")
                logger.info(f"AUC Score: {classification_metrics.get('auc_score', 0):.3f}")
            logger.info(f"Direction Accuracy: {regression_metrics['direction_accuracy']:.3f}")
            logger.info(f"Predicted Trades: {trading_metrics['total_predicted_trades']}")
            logger.info(f"Predicted Win Rate: {trading_metrics['predicted_win_rate']:.3f}")
            logger.info(f"Average Predicted Return: {trading_metrics['avg_predicted_return']:.2f}%")
            logger.info("="*50)

            return evaluation_results

        except Exception as e:
            logger.error(f"Evaluation failed: {e}")
            return {
                'success': False,
                'error': str(e),
                'model_name': self.model_name
            }

def main():
    parser = argparse.ArgumentParser(description='Evaluate ML trading model')
    parser.add_argument('--model-name', default='swing_trader', help='Model name')
    parser.add_argument('--model-path', default='/app/models', help='Path to model files')
    parser.add_argument('--test-data-path', default='/app/data/ml', help='Path to test data')

    args = parser.parse_args()

    try:
        evaluator = ModelEvaluator(args.model_name, args.model_path, args.test_data_path)
        results = evaluator.evaluate_model()

        # Output JSON for Java integration
        print(json.dumps(results, indent=2))

        if results.get('success', False):
            exit(0)
        else:
            exit(1)

    except Exception as e:
        logger.error(f"Evaluation script failed: {e}")
        print(json.dumps({
            'success': False,
            'error': str(e)
        }, indent=2))
        exit(1)

if __name__ == "__main__":
    main()