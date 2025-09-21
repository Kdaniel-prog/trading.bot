#!/usr/bin/env python3
"""
Generate Training Data from Backtesting Results
Calls Java backtest service and converts results to ML training data
"""

import requests
import json
import pandas as pd
from datetime import datetime, timedelta
from pathlib import Path
import sys
import time

class TrainingDataGenerator:
    def __init__(self, base_url="http://localhost:8080"):
        """
        Initialize training data generator
        """
        self.base_url = base_url
        self.api_base = f"{base_url}/api/backtest"

        # Output directory
        self.output_dir = Path("src/main/resources/data/training")
        self.output_dir.mkdir(parents=True, exist_ok=True)

        print(f"TrainingDataGenerator initialized")
        print(f"API base: {self.api_base}")
        print(f"Output dir: {self.output_dir}")

    def get_available_symbols(self):
        """Get available symbols from backtest service"""
        try:
            response = requests.get(f"{self.api_base}/symbols", timeout=30)
            response.raise_for_status()
            symbols = response.json()
            print(f"Found {len(symbols)} available symbols")
            return symbols
        except Exception as e:
            print(f"Error getting symbols: {e}")
            return []

    def run_backtest(self, symbol, timeframe, start_date, end_date):
        """Run backtest for a symbol"""
        try:
            payload = {
                "symbol": symbol,
                "timeframe": timeframe,
                "startDate": start_date.isoformat(),
                "endDate": end_date.isoformat()
            }

            print(f"Running backtest for {symbol} {timeframe} from {start_date.date()} to {end_date.date()}")

            response = requests.post(
                f"{self.api_base}/run",
                json=payload,
                timeout=120
            )
            response.raise_for_status()

            result = response.json()

            if result.get('success', False):
                print(f"Backtest successful: {result.get('totalTrades', 0)} trades, "
                      f"{result.get('totalReturnPercent', 0):.2f}% return")
                return result
            else:
                print(f"Backtest failed: {result.get('errorMessage', 'Unknown error')}")
                return None

        except Exception as e:
            print(f"Error running backtest for {symbol}: {e}")
            return None

    def convert_backtest_to_training_samples(self, backtest_result):
        """
        Convert backtest result to training samples
        Each trade becomes a training sample with technical indicators + outcome
        """
        samples = []

        if not backtest_result or not backtest_result.get('success'):
            return samples

        trades = backtest_result.get('trades', [])

        for trade in trades:
            try:
                # Create training sample
                sample = {
                    'symbol': trade.get('symbol'),
                    'timestamp': trade.get('entryTime'),
                    'currentPrice': trade.get('entryPrice'),
                    'timeframe': backtest_result.get('timeframe'),

                    # Technical indicators (would need to be added to backtest result)
                    'technicalIndicators': self.extract_technical_indicators(trade),

                    # Actual outcome (label)
                    'actualSignal': trade.get('side'),  # LONG or SHORT
                    'actualReturn': trade.get('pnlPercent'),
                    'actualOutcome': self.classify_outcome(trade.get('pnlPercent', 0)),
                    'holdingTimeHours': trade.get('holdingTimeHours', 0),

                    # Additional context
                    'tradingRule': trade.get('tradingRule'),
                    'score': trade.get('score'),
                    'exitReason': trade.get('exitReason', 'unknown')
                }

                samples.append(sample)

            except Exception as e:
                print(f"Error processing trade: {e}")
                continue

        return samples

    def extract_technical_indicators(self, trade):
        """
        Extract technical indicators from trade
        Note: In real implementation, this would come from the backtest
        For now, we create placeholder structure
        """
        return {
            'primaryTrend': 'NEUTRAL',
            'shortTermTrend': 'NEUTRAL',
            'trendAlignment': False,
            'trendStrength': 0.0,
            'ema20_4h': trade.get('entryPrice', 0) * 0.99,
            'ema50_4h': trade.get('entryPrice', 0) * 0.98,
            'rsi': 50.0,
            'macdBullish': False,
            'macdBearish': False,
            'rsiBullishZone': False,
            'rsiBearishZone': False,
            'rsiRising': False,
            'volumeRatio': 1.0,
            'strongVolume': False,
            'volumeBreakout': False,
            'volumeTrendUp': False,
            'riskRewardRatio': 2.0,
            'volatilityPercent': 5.0,
            'distanceFromSupport': 3.0,
            'distanceFromResistance': 4.0,
            'higherHighs': False,
            'lowerLows': False,
            'bullishStructure': False,
            'bearishStructure': False
        }

    def classify_outcome(self, pnl_percent):
        """
        Classify trade outcome for ML training
        """
        if pnl_percent > 2.0:
            return 'STRONG_WIN'
        elif pnl_percent > 0.5:
            return 'WIN'
        elif pnl_percent > -0.5:
            return 'NEUTRAL'
        elif pnl_percent > -2.0:
            return 'LOSS'
        else:
            return 'STRONG_LOSS'

    def generate_training_dataset(self, symbols_limit=10, months_back=6, timeframe="4h"):
        """
        Generate comprehensive training dataset - FIXED DATES
        """
        # Get available symbols
        all_symbols = self.get_available_symbols()

        if not all_symbols:
            print("No symbols available for training")
            return

        # Limit symbols for reasonable training time
        symbols = all_symbols[:symbols_limit]
        print(f"Using {len(symbols)} symbols for training: {symbols}")

        # FIXED Date range - 2024-es évek használata
        from datetime import datetime, timedelta

        end_date = datetime(2024, 9, 20)  # Fixed end date in 2024
        start_date = end_date - timedelta(days=months_back * 30)

        print(f"FIXED Date range: {start_date.date()} to {end_date.date()}")

        all_training_samples = []
        successful_backtests = 0

        # Run backtests for each symbol
        for i, symbol in enumerate(symbols):
            print(f"\n--- Processing {i + 1}/{len(symbols)}: {symbol} ---")

            # Run backtest with fixed dates
            backtest_result = self.run_backtest(symbol, timeframe, start_date, end_date)

            if backtest_result:
                # Convert to training samples
                samples = self.convert_backtest_to_training_samples(backtest_result)

                if samples:
                    all_training_samples.extend(samples)
                    successful_backtests += 1
                    print(f"Generated {len(samples)} training samples from {symbol}")
                else:
                    print(f"No training samples from {symbol}")

            # Small delay to avoid overwhelming the server
            time.sleep(1)

        # Save training dataset
        if all_training_samples:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            output_file = self.output_dir / f"training_data_{timestamp}.json"

            training_data = {
                'metadata': {
                    'generation_date': datetime.now().isoformat(),
                    'symbols_processed': len(symbols),
                    'successful_backtests': successful_backtests,
                    'total_samples': len(all_training_samples),
                    'timeframe': timeframe,
                    'date_range': {
                        'start': start_date.isoformat(),
                        'end': end_date.isoformat()
                    }
                },
                'samples': all_training_samples
            }

            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(training_data, f, indent=2, ensure_ascii=False)

            print(f"\n=== Training Data Generation Complete ===")
            print(f"Total samples: {len(all_training_samples)}")
            print(f"Successful backtests: {successful_backtests}/{len(symbols)}")
            print(f"Output file: {output_file}")

            # Generate summary statistics
            self.generate_summary(all_training_samples)

        else:
            print("No training samples generated!")

    def generate_summary(self, samples):
        """Generate training data summary"""
        if not samples:
            return

        # Outcome distribution
        outcomes = [s.get('actualOutcome', 'UNKNOWN') for s in samples]
        outcome_counts = {}
        for outcome in outcomes:
            outcome_counts[outcome] = outcome_counts.get(outcome, 0) + 1

        print(f"\nTraining Data Summary:")
        print(f"Outcome distribution:")
        for outcome, count in outcome_counts.items():
            percentage = count / len(samples) * 100
            print(f"  {outcome}: {count} ({percentage:.1f}%)")

        # Return distribution
        returns = [s.get('actualReturn', 0) for s in samples if s.get('actualReturn') is not None]
        if returns:
            avg_return = sum(returns) / len(returns)
            positive_returns = len([r for r in returns if r > 0])
            print(f"Average return: {avg_return:.2f}%")
            print(f"Positive returns: {positive_returns}/{len(returns)} ({positive_returns/len(returns)*100:.1f}%)")

def main():
    """
    Main function
    """
    if len(sys.argv) < 2:
        print("Usage: python generate_training_data.py <command> [options]")
        print("Commands:")
        print("  generate [symbols_limit] [months_back] [timeframe]")
        print("  test - run quick test")
        print("")
        print("Examples:")
        print("  python generate_training_data.py generate 20 6 4h")
        print("  python generate_training_data.py test")
        sys.exit(1)

    command = sys.argv[1]
    generator = TrainingDataGenerator()

    if command == "generate":
        symbols_limit = int(sys.argv[2]) if len(sys.argv) > 2 else 10
        months_back = int(sys.argv[3]) if len(sys.argv) > 3 else 6
        timeframe = sys.argv[4] if len(sys.argv) > 4 else "4h"

        print(f"Generating training data:")
        print(f"  Symbols limit: {symbols_limit}")
        print(f"  Months back: {months_back}")
        print(f"  Timeframe: {timeframe}")

        generator.generate_training_dataset(symbols_limit, months_back, timeframe)

    elif command == "test":
        print("Running quick test...")

        # Test API connection
        symbols = generator.get_available_symbols()
        if symbols:
            print(f"API connection successful - {len(symbols)} symbols available")

            # Test single backtest
            test_symbol = symbols[0] if symbols else "BTC_USDT"
            end_date = datetime.now()
            start_date = end_date - timedelta(days=30)  # 1 month test

            result = generator.run_backtest(test_symbol, "4h", start_date, end_date)
            if result:
                samples = generator.convert_backtest_to_training_samples(result)
                print(f"Test successful - generated {len(samples)} training samples")
            else:
                print("Test backtest failed")
        else:
            print("API connection failed")

    else:
        print(f"Unknown command: {command}")
        sys.exit(1)

if __name__ == "__main__":
    main()