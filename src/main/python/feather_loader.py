import pandas as pd
from pathlib import Path
import sys

class FeatherLoader:
    def __init__(self, data_dir="../../data"):
        """
        Feather file loader Binance futures trading adatokhoz

        Args:
            data_dir: A .feather file-ok mappája (relatív út a python scripthez képest)
        """
        self.data_dir = Path(data_dir).resolve()
        print(f"Data directory: {self.data_dir}")

        if not self.data_dir.exists():
            raise FileNotFoundError(f"Data directory not found: {self.data_dir}")

    def list_available_symbols(self):
        """Listázza az elérhető szimbólumokat"""
        symbols = set()
        for file in self.data_dir.glob("*.feather"):
            # Példa: BTC_USDT-1d.feather -> BTC_USDT
            symbol = file.stem.rsplit('-', 1)[0]
            symbols.add(symbol)
        return sorted(list(symbols))

    def load_symbol_data(self, symbol, timeframes=['1d', '4h', '1h', '30m'],
                         start=None, end=None):
        """
        Betölti egy szimbólum összes timeframe adatát, opcionális szűréssel

        Args:
            symbol: pl. 'BTC_USDT', '1000SATS_USDT'
            timeframes: lista a kívánt timeframe-ekről
            start: szűrés kezdete (string vagy datetime) → mindig UTC-re konvertálva
            end: szűrés vége (string vagy datetime) → mindig UTC-re konvertálva

        Returns:
            dict: {timeframe: DataFrame}
        """
        data = {}
        if start:
            start = pd.to_datetime(start, utc=True)
        if end:
            end = pd.to_datetime(end, utc=True)

        for tf in timeframes:
            file_path = self.data_dir / f"{symbol}-{tf}.feather"

            if not file_path.exists():
                print(f"⚠️  Nem található: {file_path}")
                continue

            try:
                df = pd.read_feather(file_path)

                # ✅ Timestamp normalizálás UTC-re
                if 'timestamp' in df.columns:
                    df['timestamp'] = pd.to_datetime(df['timestamp'], utc=True)
                    date_col = 'timestamp'
                elif 'date' in df.columns:
                    df['date'] = pd.to_datetime(df['date'], utc=True)
                    date_col = 'date'
                else:
                    print(f"❌ {file_path} nem tartalmaz dátum oszlopot")
                    continue

                # ✅ Szűrés (ha van start/end)
                if start or end:
                    mask = pd.Series(True, index=df.index)
                    if start:
                        mask &= df[date_col] >= start
                    if end:
                        mask &= df[date_col] <= end
                    df = df.loc[mask]

                data[tf] = df
                print(f"✅ Betöltve: {symbol}-{tf} ({len(df)} sor)")

                if len(df) > 0:
                    print(f"   Időtartam: {df[date_col].min()} - {df[date_col].max()}")

            except Exception as e:
                print(f"❌ Hiba {symbol}-{tf} betöltésekor: {e}")

        return data

    def get_file_info(self, symbol=None):
        """File-ok információi"""
        files = []
        pattern = f"{symbol}-*.feather" if symbol else "*.feather"

        for file_path in self.data_dir.glob(pattern):
            try:
                df = pd.read_feather(file_path)

                # UTC normalizálás
                if 'timestamp' in df.columns:
                    df['timestamp'] = pd.to_datetime(df['timestamp'], utc=True)
                elif 'date' in df.columns:
                    df['date'] = pd.to_datetime(df['date'], utc=True)

                files.append({
                    'file': file_path.name,
                    'size_mb': file_path.stat().st_size / 1024 / 1024,
                    'rows': len(df),
                    'columns': list(df.columns),
                    'memory_mb': df.memory_usage(deep=True).sum() / 1024 / 1024
                })
            except Exception as e:
                files.append({
                    'file': file_path.name,
                    'error': str(e)
                })

        return files


def main():
    print("🚀 FeatherLoader Teszt\n")

    # Loader inicializálás
    current_dir = Path(__file__).parent
    data_dir = current_dir / "../../data"

    loader = FeatherLoader(str(data_dir))

    # Szimbólumok listázása
    symbols = loader.list_available_symbols()
    print(f"📊 {len(symbols)} elérhető szimbólum")
    if symbols:
        print(f"Első 5: {symbols[:5]}")

    # Teszt betöltés szűréssel
    if symbols:
        test_symbol = symbols[0]
        print(f"\n🔍 Teszt szimbólum: {test_symbol}")
        data = loader.load_symbol_data(
            test_symbol,
            timeframes=['1d', '4h'],
            start="2024-01-01T00:00:00",
            end="2024-03-01T00:00:00"
        )
        for tf, df in data.items():
            print(f"   {tf}: {len(df)} sor")

    # File infó
    print("\n📁 File info (első 3):")
    for info in loader.get_file_info()[:3]:
        print(info)


if __name__ == "__main__":
    main()
