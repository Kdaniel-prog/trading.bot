# data_loader.py - Load feather data for Python training
import pandas as pd
import numpy as np
from pathlib import Path
import logging
from typing import List, Dict, Optional, Tuple
from datetime import datetime, timedelta

logger = logging.getLogger(__name__)

class FeatherDataProcessor:
    def __init__(self, feather_data_path: str = "/app/data/feather"):
        self.data_path = Path(feather_data_path)
        self.supported_timeframes = ["30m", "1h", "4h", "1d"]

    def get_available_symbols(self) -> List[str]:
        """Get list of available USDT trading pairs"""
        symbols = set()
        for timeframe in self.supported_timeframes:
            tf_path = self.data_path / timeframe
            if tf_path.exists():
                for file in tf_path.glob("*USDT.feather"):
                    symbol = file.stem
                    symbols.add(symbol)
        return sorted(list(symbols))

    def load_symbol_data(self, symbol: str, timeframe: str,
                         start_date: Optional[datetime] = None,
                         end_date: Optional[datetime] = None) -> pd.DataFrame:
        """Load historical data for a symbol and timeframe"""
        file_path = self.data_path / timeframe / f"{symbol}.feather"

        if not file_path.exists():
            raise FileNotFoundError(f"Data file not found: {file_path}")

        df = pd.read_feather(file_path)

        # Ensure timestamp column exists and is datetime
        if 'timestamp' not in df.columns and 'Unnamed: 0' in df.columns:
            df = df.rename(columns={'Unnamed: 0': 'timestamp'})

        if 'timestamp' in df.columns:
            df['timestamp'] = pd.to_datetime(df['timestamp'])
            df = df.sort_values('timestamp')

        # Filter by date range if provided
        if start_date or end_date:
            if start_date:
                df = df[df['timestamp'] >= start_date]
            if end_date:
                df = df[df['timestamp'] <= end_date]

        return df.reset_index(drop=True)

    def prepare_training_dataset(self, symbols: List[str],
                                 timeframes: List[str] = ["1h", "4h", "1d"],
                                 lookback_days: int = 365) -> pd.DataFrame:
        """Prepare comprehensive dataset for ML training"""
        end_date = datetime.now()
        start_date = end_date - timedelta(days=lookback_days)

        all_data = []

        for symbol in symbols:
            logger.info(f"Processing {symbol}...")

            try:
                # Load data for all timeframes
                symbol_data = {}
                for tf in timeframes:
                    df = self.load_symbol_data(symbol, tf, start_date, end_date)
                    symbol_data[tf] = df

                # Use base timeframe (1h) as primary
                base_df = symbol_data.get("1h")
                if base_df is None or len(base_df) < 100:
                    logger.warning(f"Insufficient data for {symbol}")
                    continue

                # Add technical indicators
                base_df = self.add_technical_indicators(base_df, symbol)

                # Add multi-timeframe features
                base_df = self.add_multi_timeframe_features(base_df, symbol_data)

                # Add market context
                base_df = self.add_market_context(base_df)

                all_data.append(base_df)

            except Exception as e:
                logger.error(f"Error processing {symbol}: {e}")
                continue

        if not all_data:
            raise ValueError("No data could be loaded")

        # Combine all symbols
        combined_df = pd.concat(all_data, ignore_index=True)
        logger.info(f"Prepared dataset with {len(combined_df)} samples from {len(symbols)} symbols")

        return combined_df

    def add_technical_indicators(self, df: pd.DataFrame, symbol: str) -> pd.DataFrame:
        """Add technical indicators to the dataframe"""
        df = df.copy()
        df['symbol'] = symbol

        # Price-based indicators
        df['rsi'] = self.calculate_rsi(df['close'])
        df['macd'], df['macd_signal'], df['macd_histogram'] = self.calculate_macd(df['close'])

        # Moving averages
        df['sma_20'] = df['close'].rolling(20).mean()
        df['sma_50'] = df['close'].rolling(50).mean()
        df['ema_12'] = df['close'].ewm(span=12).mean()
        df['ema_26'] = df['close'].ewm(span=26).mean()
        df['ema_50'] = df['close'].ewm(span=50).mean()
        df['ema_200'] = df['close'].ewm(span=200).mean()

        # Bollinger Bands
        bb_period = 20
        bb_std = 2
        bb_middle = df['close'].rolling(bb_period).mean()
        bb_std_dev = df['close'].rolling(bb_period).std()
        df['bollinger_upper'] = bb_middle + (bb_std_dev * bb_std)
        df['bollinger_lower'] = bb_middle - (bb_std_dev * bb_std)
        df['bollinger_percent'] = (df['close'] - df['bollinger_lower']) / (df['bollinger_upper'] - df['bollinger_lower'])

        # Volatility
        df['atr'] = self.calculate_atr(df[['high', 'low', 'close']])
        df['price_change_1h'] = df['close'].pct_change()
        df['volatility_1h'] = df['price_change_1h'].rolling(24).std()

        # Volume indicators
        df['volume_sma_20'] = df['volume'].rolling(20).mean()
        df['volume_ratio'] = df['volume'] / df['volume_sma_20']
        df['vwap'] = (df['close'] * df['volume']).cumsum() / df['volume'].cumsum()

        # ADX
        df['adx'] = self.calculate_adx(df[['high', 'low', 'close']])

        return df

    def calculate_rsi(self, prices: pd.Series, period: int = 14) -> pd.Series:
        """Calculate Relative Strength Index"""
        delta = prices.diff()
        gain = (delta.where(delta > 0, 0)).rolling(window=period).mean()
        loss = (-delta.where(delta < 0, 0)).rolling(window=period).mean()
        rs = gain / loss
        rsi = 100 - (100 / (1 + rs))
        return rsi

    def calculate_macd(self, prices: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9) -> Tuple[pd.Series, pd.Series, pd.Series]:
        """Calculate MACD indicator"""
        ema_fast = prices.ewm(span=fast).mean()
        ema_slow = prices.ewm(span=slow).mean()
        macd = ema_fast - ema_slow
        macd_signal = macd.ewm(span=signal).mean()
        macd_histogram = macd - macd_signal
        return macd, macd_signal, macd_histogram

    def calculate_atr(self, hlc: pd.DataFrame, period: int = 14) -> pd.Series:
        """Calculate Average True Range"""
        high_low = hlc['high'] - hlc['low']
        high_close = np.abs(hlc['high'] - hlc['close'].shift())
        low_close = np.abs(hlc['low'] - hlc['close'].shift())
        true_range = np.maximum(high_low, np.maximum(high_close, low_close))
        return true_range.rolling(period).mean()

    def calculate_adx(self, hlc: pd.DataFrame, period: int = 14) -> pd.Series:
        """Calculate Average Directional Index (simplified)"""
        high_diff = hlc['high'].diff()
        low_diff = hlc['low'].diff()

        plus_dm = high_diff.where((high_diff > low_diff) & (high_diff > 0), 0)
        minus_dm = (-low_diff).where((low_diff > high_diff) & (low_diff < 0), 0)

        plus_dm_smooth = plus_dm.rolling(period).mean()
        minus_dm_smooth = minus_dm.rolling(period).mean()
        atr = self.calculate_atr(hlc, period)

        plus_di = 100 * (plus_dm_smooth / atr)
        minus_di = 100 * (minus_dm_smooth / atr)

        adx = 100 * np.abs(plus_di - minus_di) / (plus_di + minus_di)
        return adx.rolling(period).mean()

    def add_multi_timeframe_features(self, base_df: pd.DataFrame, symbol_data: Dict[str, pd.DataFrame]) -> pd.DataFrame:
        """Add features from higher timeframes"""
        df = base_df.copy()

        # Add features from 4h and 1d timeframes
        for tf in ["4h", "1d"]:
            if tf not in symbol_data:
                continue

            tf_df = symbol_data[tf]
            if len(tf_df) == 0:
                continue

            # Resample base timeframe to match higher timeframe
            # This is a simplified approach - in production you'd want more sophisticated alignment
            tf_features = tf_df[['timestamp', 'close']].copy()
            tf_features[f'price_change_{tf}'] = tf_features['close'].pct_change()
            tf_features[f'volatility_{tf}'] = tf_features[f'price_change_{tf}'].rolling(20).std()

            # Merge with base dataframe (forward fill for alignment)
            df = pd.merge_asof(df.sort_values('timestamp'),
                               tf_features.sort_values('timestamp'),
                               on='timestamp',
                               suffixes=('', f'_{tf}'))

        return df

    def add_market_context(self, df: pd.DataFrame) -> pd.DataFrame:
        """Add market context features"""
        df = df.copy()

        # Time-based features
        df['hour_of_day'] = df['timestamp'].dt.hour
        df['day_of_week'] = df['timestamp'].dt.dayofweek
        df['is_market_hours'] = df['hour_of_day'].between(6, 22)  # Rough market hours

        # Market trend (simplified)
        df['market_trend'] = np.tanh(df['close'].pct_change(168))  # Weekly trend, normalized
        df['market_volatility'] = df['close'].pct_change().rolling(24).std()

        # Support/Resistance levels (simplified)
        rolling_window = 48  # 48 hours
        df['support_level'] = df['low'].rolling(rolling_window).min()
        df['resistance_level'] = df['high'].rolling(rolling_window).max()

        return df