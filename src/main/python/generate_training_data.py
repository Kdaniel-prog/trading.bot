#!/usr/bin/env python3
"""
JAVÍTOTT Training Data Generator - Look-ahead bias nélkül
A főbb javítások:
1. Valós technikai indikátorok számítása placeholder-ek helyett
2. Look-ahead bias teljes kiküszöbölése
3. Időszinkronizációs problémák megoldása
"""

import requests
import json
import pandas as pd
import numpy as np
from datetime import datetime, timedelta
from pathlib import Path
import sys
import time
import talib
import warnings

warnings.filterwarnings('ignore')


class FixedTrainingDataGenerator:
    def __init__(self, base_url="http://localhost:8080"):
        """
        Initialize FIXED training data generator
        """
        self.base_url = base_url
        self.api_base = f"{base_url}/api/backtest"

        # Output directory
        self.output_dir = Path("src/main/resources/data/training")
        self.output_dir.mkdir(parents=True, exist_ok=True)

        print(f"FIXED TrainingDataGenerator initialized")
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
                timeout=180  # Longer timeout for complex backtests
            )
            response.raise_for_status()

            result = response.json()

            if result.get('success', False):
                trades_count = result.get('totalTrades', 0)
                return_pct = result.get('totalReturnPercent', 0)
                print(f"Backtest successful: {trades_count} trades, {return_pct:.2f}% return")
                return result
            else:
                print(f"Backtest failed: {result.get('errorMessage', 'Unknown error')}")
                return None

        except Exception as e:
            print(f"Error running backtest for {symbol}: {e}")
            return None

    def fetch_historical_klines(self, symbol, timeframe, start_date, end_date):
        """
        KRITIKUS: Valós piaci adatok letöltése a backtest service-ből
        """
        try:
            # Kiterjesztett időintervallum a technikai indikátorokhoz
            extended_start = start_date - timedelta(days=200)

            payload = {
                "symbol": symbol,
                "timeframe": timeframe,
                "startDate": extended_start.isoformat(),
                "endDate": end_date.isoformat()
            }

            response = requests.post(
                f"{self.api_base}/klines",
                json=payload,
                timeout=60
            )

            if response.status_code == 200:
                klines_data = response.json()
                print(f"Fetched {len(klines_data)} {timeframe} klines for {symbol}")
                return klines_data
            else:
                print(f"Failed to fetch klines for {symbol} {timeframe}: {response.status_code}")
                return []

        except Exception as e:
            print(f"Error fetching klines for {symbol}: {e}")
            return []

    def convert_backtest_to_clean_training_samples(self, backtest_result):
        """
        KRITIKUS JAVÍTÁS: Tiszta training sample-ek generálása LOOK-AHEAD BIAS NÉLKÜL
        """
        if not backtest_result or not backtest_result.get('success'):
            return []

        symbol = backtest_result.get('symbol')
        timeframe = backtest_result.get('timeframe')
        trades = backtest_result.get('trades', [])

        if not trades:
            print(f"No trades found for {symbol}")
            return []

        print(f"Processing {len(trades)} trades for {symbol} with CLEAN methodology")

        # KRITIKUS: Betöltjük a TELJES történelmi adatokat minden timeframe-hez
        start_date = datetime.fromisoformat(backtest_result['startDate'].replace('Z', '+00:00')).replace(tzinfo=None)
        end_date = datetime.fromisoformat(backtest_result['endDate'].replace('Z', '+00:00')).replace(tzinfo=None)

        # Fetching multi-timeframe data
        timeframe_data = {}
        for tf in ['1h', '4h', '1d']:
            klines = self.fetch_historical_klines(symbol, tf, start_date, end_date)
            if klines:
                # Convert to DataFrame for easy manipulation
                df = pd.DataFrame(klines, columns=['timestamp', 'open', 'high', 'low', 'close', 'volume'])
                df['timestamp'] = pd.to_datetime(df['timestamp'], unit='ms')
                df = df.set_index('timestamp')
                df = df.astype(float)
                timeframe_data[tf] = df
                print(f"Loaded {len(df)} {tf} candles for {symbol}")

        if not timeframe_data:
            print(f"No historical data available for {symbol}")
            return []

        clean_samples = []

        for trade in trades:
            try:
                # Parse trade entry time
                entry_time_str = trade.get('entryTime', '')
                if not entry_time_str:
                    continue

                # Handle different datetime formats
                if entry_time_str.endswith('Z'):
                    entry_time = datetime.fromisoformat(entry_time_str.replace('Z', '+00:00')).replace(tzinfo=None)
                else:
                    entry_time = datetime.fromisoformat(entry_time_str)

                # KRITIKUS: Technikai indikátorok számítása SZIGORÚAN a trade entry ELŐTTI adatokból
                real_indicators = self.calculate_real_indicators_before_entry(
                    timeframe_data, entry_time)

                if real_indicators is None:
                    print(f"Insufficient data for trade at {entry_time}")
                    continue

                # Create clean training sample
                sample = self.create_clean_sample(trade, backtest_result, real_indicators, entry_time)

                if sample:
                    clean_samples.append(sample)

                    # Debug first few samples
                    if len(clean_samples) <= 3:
                        rsi = real_indicators.get('rsi', 'N/A')
                        ema20 = real_indicators.get('ema20_4h', 'N/A')
                        actual_pnl = trade.get('pnlPercent', 0)
                        print(
                            f"  Sample #{len(clean_samples)}: RSI={rsi:.1f}, EMA20={ema20:.6f}, Actual={actual_pnl:.2f}%")

            except Exception as e:
                print(f"Error processing trade: {e}")
                continue

        print(f"Generated {len(clean_samples)} clean samples for {symbol}")
        return clean_samples

    def calculate_real_indicators_before_entry(self, timeframe_data, entry_time):
        """
        KRITIKUS: Valós technikai indikátorok számítása a trade entry ELŐTTI adatokból
        """
        try:
            indicators = {}

            # 4H timeframe indicators (primary)
            if '4h' in timeframe_data:
                df_4h = timeframe_data['4h']

                # KRITIKUS: Csak az entry_time ELŐTTI adatokat használjuk
                historical_4h = df_4h[df_4h.index < entry_time]

                if len(historical_4h) < 50:
                    print(f"Insufficient 4H data: {len(historical_4h)} candles before {entry_time}")
                    return None

                # VALÓS RSI számítása
                closes = historical_4h['close'].values.astype(float)
                indicators['rsi'] = float(talib.RSI(closes, timeperiod=14)[-1])

                # VALÓS EMA számítások
                indicators['ema20_4h'] = float(talib.EMA(closes, timeperiod=20)[-1])
                indicators['ema50_4h'] = float(talib.EMA(closes, timeperiod=50)[-1])

                # VALÓS MACD
                macd_line, macd_signal, macd_hist = talib.MACD(closes,
                                                               fastperiod=12,
                                                               slowperiod=26,
                                                               signalperiod=9)
                indicators['macdLine'] = float(macd_line[-1])
                indicators['macdSignal'] = float(macd_signal[-1])
                indicators['macdHistogram'] = float(macd_hist[-1])

                # VALÓS ATR (volatilitás)
                highs = historical_4h['high'].values.astype(float)
                lows = historical_4h['low'].values.astype(float)
                atr = talib.ATR(highs, lows, closes, timeperiod=14)
                current_price = closes[-1]
                indicators['atr'] = float(atr[-1])
                indicators['atrPercent'] = float((atr[-1] / current_price) * 100)

                # VALÓS Volume analízis
                volumes = historical_4h['volume'].values.astype(float)
                current_volume = volumes[-1]
                avg_volume = np.mean(volumes[-20:])  # 20-period average
                indicators['volumeRatio'] = float(current_volume / avg_volume) if avg_volume > 0 else 1.0

                # Trend analízis
                ema20 = indicators['ema20_4h']
                ema50 = indicators['ema50_4h']

                if current_price > ema20 > ema50:
                    trend = "BULLISH"
                elif current_price < ema20 < ema50:
                    trend = "BEARISH"
                else:
                    trend = "NEUTRAL"

                indicators['primaryTrend'] = trend
                indicators['currentPrice'] = float(current_price)

                # Bollinger Bands
                bb_upper, bb_middle, bb_lower = talib.BBANDS(closes, timeperiod=20, nbdevup=2, nbdevdn=2)
                indicators['bb_upper'] = float(bb_upper[-1])
                indicators['bb_lower'] = float(bb_lower[-1])
                indicators['bb_position'] = float((current_price - bb_lower[-1]) / (bb_upper[-1] - bb_lower[-1]))

            # Daily timeframe for longer-term context
            if '1d' in timeframe_data:
                df_1d = timeframe_data['1d']
                historical_1d = df_1d[df_1d.index < entry_time]

                if len(historical_1d) >= 200:
                    daily_closes = historical_1d['close'].values.astype(float)
                    indicators['ema200_daily'] = float(talib.EMA(daily_closes, timeperiod=200)[-1])
                else:
                    # Fallback to 4H EMA50
                    indicators['ema200_daily'] = indicators.get('ema50_4h', current_price)

            # Hourly for short-term momentum
            if '1h' in timeframe_data:
                df_1h = timeframe_data['1h']
                historical_1h = df_1h[df_1h.index < entry_time]

                if len(historical_1h) >= 14:
                    hourly_closes = historical_1h['close'].values.astype(float)
                    indicators['rsi_1h'] = float(talib.RSI(hourly_closes, timeperiod=14)[-1])

                    # Short-term momentum
                    indicators['momentum_1h'] = float(talib.MOM(hourly_closes, timeperiod=10)[-1])
                else:
                    indicators['rsi_1h'] = indicators.get('rsi', 50.0)
                    indicators['momentum_1h'] = 0.0

            # Derived indicators
            self.calculate_derived_indicators(indicators)

            # Data quality flag
            indicators['_dataQuality'] = 'REAL_HISTORICAL_CLEAN'
            indicators['_entryTime'] = entry_time.isoformat()

            return indicators

        except Exception as e:
            print(f"Error calculating real indicators: {e}")
            return None

    def calculate_derived_indicators(self, indicators):
        """
        Származtatott indikátorok számítása
        """
        try:
            # RSI zones
            rsi = indicators.get('rsi', 50.0)
            indicators['rsiOversold'] = rsi < 30
            indicators['rsiOverbought'] = rsi > 70
            indicators['rsiBullishZone'] = 30 <= rsi <= 50
            indicators['rsiBearishZone'] = 50 <= rsi <= 70

            # Volume strength
            vol_ratio = indicators.get('volumeRatio', 1.0)
            indicators['strongVolume'] = vol_ratio > 1.3
            indicators['volumeBreakout'] = vol_ratio > 2.0
            indicators['weakVolume'] = vol_ratio < 0.7

            # MACD signals
            macd_hist = indicators.get('macdHistogram', 0.0)
            indicators['macdBullish'] = macd_hist > 0
            indicators['macdBearish'] = macd_hist < 0

            # Trend alignment
            current_price = indicators.get('currentPrice', 0)
            ema20 = indicators.get('ema20_4h', 0)
            if current_price > 0 and ema20 > 0:
                price_ema_diff = abs(current_price - ema20) / current_price
                indicators['trendAlignment'] = price_ema_diff < 0.02  # Price within 2% of EMA20
            else:
                indicators['trendAlignment'] = False

            # Volatility assessment
            atr_pct = indicators.get('atrPercent', 5.0)
            indicators['lowVolatility'] = atr_pct < 3.0
            indicators['normalVolatility'] = 3.0 <= atr_pct <= 8.0
            indicators['highVolatility'] = atr_pct > 8.0

            # Market structure
            trend = indicators.get('primaryTrend', 'NEUTRAL')
            indicators['bullishStructure'] = trend == 'BULLISH'
            indicators['bearishStructure'] = trend == 'BEARISH'
            indicators['neutralStructure'] = trend == 'NEUTRAL'

        except Exception as e:
            print(f"Error calculating derived indicators: {e}")

    def create_clean_sample(self, trade, backtest_result, real_indicators, entry_time):
        """
        Tiszta training sample készítése
        """
        try:
            # Basic trade information
            sample = {
                'symbol': trade.get('symbol'),
                'timeframe': backtest_result.get('timeframe'),
                'timestamp': entry_time.isoformat(),
                'entryPrice': trade.get('entryPrice'),

                # VALÓS technikai indikátorok (trade entry ELŐTTI adatokból)
                'technicalIndicators': real_indicators,

                # ACTUAL outcomes (ezeket már ismerjük a trade befejezése után)
                'actualDirection': trade.get('side'),
                'actualPnlPercent': trade.get('pnlPercent', 0.0),
                'actualOutcome': self.classify_outcome(trade.get('pnlPercent', 0.0)),
                'holdingTimeHours': trade.get('holdingTimeHours', 0),

                # Trade metadata
                'tradingRule': trade.get('tradingRule'),
                'score': trade.get('score', 0.0),
                'exitReason': self.determine_exit_reason(trade),
                'exitPrice': trade.get('exitPrice'),

                # Data quality assurance
                '_sampleQuality': 'CLEAN_NO_LOOKAHEAD',
                '_version': '5.0'
            }

            return sample

        except Exception as e:
            print(f"Error creating clean sample: {e}")
            return None

    def classify_outcome(self, pnl_percent):
        """
        PnL alapján outcome osztályozás
        """
        if pnl_percent > 3.0:
            return 'STRONG_WIN'
        elif pnl_percent > 0.5:
            return 'WIN'
        elif pnl_percent > -0.5:
            return 'NEUTRAL'
        elif pnl_percent > -3.0:
            return 'LOSS'
        else:
            return 'STRONG_LOSS'

    def determine_exit_reason(self, trade):
        """
        Kilépés okának meghatározása
        """
        pnl_pct = trade.get('pnlPercent', 0.0)
        holding_hours = trade.get('holdingTimeHours', 0)

        if holding_hours > 240:  # 10 days
            return 'MAX_TIME'
        elif pnl_pct > 6.0:
            return 'TAKE_PROFIT'
        elif pnl_pct < -3.0:
            return 'STOP_LOSS'
        else:
            return 'SIGNAL_CHANGE'

    def generate_clean_training_dataset(self, symbols_limit=10, months_back=6, timeframe="4h"):
        """
        JAVÍTOTT: Tiszta training dataset generálása - LOOK-AHEAD BIAS NÉLKÜL
        """
        all_symbols = self.get_available_symbols()

        if not all_symbols:
            print("No symbols available for training")
            return

        symbols = all_symbols[:symbols_limit]
        print(f"Using {len(symbols)} symbols for CLEAN training: {symbols}")

        # FIXED date range
        end_date = datetime(2024, 9, 20)
        start_date = end_date - timedelta(days=months_back * 30)
        print(f"Date range: {start_date.date()} to {end_date.date()}")

        all_clean_samples = []
        successful_backtests = 0

        for i, symbol in enumerate(symbols):
            print(f"\n--- Processing {i + 1}/{len(symbols)}: {symbol} ---")

            backtest_result = self.run_backtest(symbol, timeframe, start_date, end_date)

            if backtest_result:
                clean_samples = self.convert_backtest_to_clean_training_samples(backtest_result)

                if clean_samples:
                    all_clean_samples.extend(clean_samples)
                    successful_backtests += 1
                    print(f"Generated {len(clean_samples)} CLEAN samples from {symbol}")
                else:
                    print(f"No clean samples from {symbol}")

            time.sleep(1)  # Rate limiting

        # Save clean training dataset
        if all_clean_samples:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            output_file = self.output_dir / f"clean_training_data_{timestamp}.json"

            training_data = {
                'metadata': {
                    'generation_date': datetime.now().isoformat(),
                    'methodology': 'CLEAN_NO_LOOKAHEAD_BIAS',
                    'symbols_processed': len(symbols),
                    'successful_backtests': successful_backtests,
                    'total_samples': len(all_clean_samples),
                    'timeframe': timeframe,
                    'date_range': {
                        'start': start_date.isoformat(),
                        'end': end_date.isoformat()
                    },
                    'data_quality': 'REAL_HISTORICAL_INDICATORS',
                    'version': '5.0_CLEAN'
                },
                'samples': all_clean_samples
            }

            with open(output_file, 'w', encoding='utf-8') as f:
                json.dump(training_data, f, indent=2, ensure_ascii=False)

            print(f"\n=== CLEAN Training Data Generation Complete ===")
            print(f"Total samples: {len(all_clean_samples)}")
            print(f"Successful backtests: {successful_backtests}/{len(symbols)}")
            print(f"Output file: {output_file}")

            # Generate quality summary
            self.generate_clean_summary(all_clean_samples)

        else:
            print("No clean training samples generated!")

    def generate_clean_summary(self, samples):
        """
        Tiszta training data összefoglaló
        """
        if not samples:
            return

        print(f"\nCLEAN Training Data Summary:")
        print(f"Total samples: {len(samples)}")

        # Outcome distribution
        outcomes = [s.get('actualOutcome', 'UNKNOWN') for s in samples]
        outcome_counts = {}
        for outcome in outcomes:
            outcome_counts[outcome] = outcome_counts.get(outcome, 0) + 1

        print(f"Outcome distribution:")
        for outcome, count in sorted(outcome_counts.items()):
            percentage = count / len(samples) * 100
            print(f"  {outcome}: {count} ({percentage:.1f}%)")

        # PnL statistics
        pnls = [s.get('actualPnlPercent', 0) for s in samples if s.get('actualPnlPercent') is not None]
        if pnls:
            avg_pnl = sum(pnls) / len(pnls)
            positive_pnls = len([p for p in pnls if p > 0])
            print(f"Average PnL: {avg_pnl:.2f}%")
            print(f"Positive PnL: {positive_pnls}/{len(pnls)} ({positive_pnls / len(pnls) * 100:.1f}%)")

        # Technical indicators quality check
        valid_indicators = 0
        for sample in samples:
            indicators = sample.get('technicalIndicators', {})
            if indicators.get('_dataQuality') == 'REAL_HISTORICAL_CLEAN':
                valid_indicators += 1

        print(f"Real indicators: {valid_indicators}/{len(samples)} ({valid_indicators / len(samples) * 100:.1f}%)")

        # RSI distribution (sanity check)
        rsis = []
        for sample in samples:
            rsi = sample.get('technicalIndicators', {}).get('rsi')
            if rsi is not None and 0 <= rsi <= 100:
                rsis.append(rsi)

        if rsis:
            avg_rsi = sum(rsis) / len(rsis)
            oversold = len([r for r in rsis if r < 30])
            overbought = len([r for r in rsis if r > 70])
            print(f"RSI stats: Avg={avg_rsi:.1f}, Oversold={oversold}, Overbought={overbought}")


def main():
    """
    Main function for CLEAN training data generation
    """
    if len(sys.argv) < 2:
        print("Usage: python fixed_generate_training_data.py <command> [options]")
        print("Commands:")
        print("  clean_generate [symbols_limit] [months_back] [timeframe]")
        print("  test - run quick test")
        print("")
        print("Examples:")
        print("  python fixed_generate_training_data.py clean_generate 20 6 4h")
        print("  python fixed_generate_training_data.py test")
        sys.exit(1)

    command = sys.argv[1]
    generator = FixedTrainingDataGenerator()

    if command == "clean_generate":
        symbols_limit = int(sys.argv[2]) if len(sys.argv) > 2 else 10
        months_back = int(sys.argv[3]) if len(sys.argv) > 3 else 6
        timeframe = sys.argv[4] if len(sys.argv) > 4 else "4h"

        print(f"Generating CLEAN training data:")
        print(f"  Symbols limit: {symbols_limit}")
        print(f"  Months back: {months_back}")
        print(f"  Timeframe: {timeframe}")

        generator.generate_clean_training_dataset(symbols_limit, months_back, timeframe)

    elif command == "test":
        print("Running CLEAN training data test...")

        symbols = generator.get_available_symbols()
        if symbols:
            print(f"API connection successful - {len(symbols)} symbols available")

            # Test single backtest
            test_symbol = symbols[0] if symbols else "BTC_USDT"
            end_date = datetime(2024, 8, 1)
            start_date = end_date - timedelta(days=30)

            result = generator.run_backtest(test_symbol, "4h", start_date, end_date)
            if result:
                samples = generator.convert_backtest_to_clean_training_samples(result)
                print(f"Test successful - generated {len(samples)} CLEAN training samples")
            else:
                print("Test backtest failed")
        else:
            print("API connection failed")

    else:
        print(f"Unknown command: {command}")
        sys.exit(1)


if __name__ == "__main__":
    main()