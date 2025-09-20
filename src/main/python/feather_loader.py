import pandas as pd
import os
import sys
from pathlib import Path

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

        if not self.data_dir.exists():
            print(f"❌ Data directory nem található: {self.data_dir}")
            return []

        for file in self.data_dir.glob("*.feather"):
            # Példa: BTC_USDT-1d.feather -> BTC_USDT
            symbol = file.stem.rsplit('-', 1)[0]
            symbols.add(symbol)

        return sorted(list(symbols))

    def load_symbol_data(self, symbol, timeframes=['1d', '4h', '1h', '30m']):
        """
        Betölti egy szimbólum összes timeframe adatát

        Args:
            symbol: pl. 'BTC_USDT', '1000SATS_USDT'
            timeframes: lista a kívánt timeframe-ekről

        Returns:
            dict: {timeframe: DataFrame}
        """
        data = {}

        for tf in timeframes:
            file_path = self.data_dir / f"{symbol}-{tf}.feather"

            if file_path.exists():
                try:
                    df = pd.read_feather(file_path)
                    data[tf] = df
                    print(f"✅ Betöltve: {symbol}-{tf} -> {len(df)} sor")

                    # Alapvető info
                    if len(df) > 0:
                        print(f"   Oszlopok: {list(df.columns)}")
                        if 'timestamp' in df.columns or 'date' in df.columns:
                            date_col = 'timestamp' if 'timestamp' in df.columns else 'date'
                            print(f"   Időtartam: {df[date_col].min()} - {df[date_col].max()}")
                        print(f"   Első sor: {dict(df.iloc[0])}")

                except Exception as e:
                    print(f"❌ Hiba {symbol}-{tf} betöltésekor: {e}")
            else:
                print(f"⚠️  Nem található: {file_path}")

        return data

    def get_file_info(self, symbol=None):
        """File-ok információi"""
        files = []
        pattern = f"{symbol}-*.feather" if symbol else "*.feather"

        for file_path in self.data_dir.glob(pattern):
            try:
                df = pd.read_feather(file_path)
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
    """Tesztelés és debug"""
    print("🚀 FeatherLoader Teszt\n")

    # Loader inicializálás
    try:
        # Automatikus path detection
        current_dir = Path(__file__).parent
        possible_data_dirs = [
            current_dir / "../../data",           # E:/Codes/.../src/main/python -> E:/Codes/.../data
            current_dir / "../../../data",        # Ha mélyebben van
            Path("E:/Codes/trading.bot2/trading.bot/data"),  # Abszolút
            current_dir / "data"                  # Ugyanabban a mappában
        ]

        loader = None
        for data_dir in possible_data_dirs:
            if data_dir.exists():
                print(f"✅ Data directory található: {data_dir}")
                loader = FeatherLoader(str(data_dir))
                break

        if not loader:
            print("❌ Egyik data directory sem található:")
            for d in possible_data_dirs:
                print(f"   {d} -> {'létezik' if d.exists() else 'NEM létezik'}")
            return

    except Exception as e:
        print(f"❌ FeatherLoader inicializálás hiba: {e}")
        return

    # Elérhető szimbólumok
    print("\n📋 Elérhető szimbólumok:")
    symbols = loader.list_available_symbols()
    if symbols:
        for i, symbol in enumerate(symbols[:10]):  # Első 10
            print(f"   {i+1}. {symbol}")
        if len(symbols) > 10:
            print(f"   ... és még {len(symbols)-10} darab")
        print(f"\n📊 Összesen: {len(symbols)} szimbólum")
    else:
        print("   ❌ Nincsenek elérhető szimbólumok")
        return

    # Teszt szimbólum betöltése
    if len(sys.argv) > 1:
        test_symbol = sys.argv[1]
    else:
        # Alapértelmezett teszteléshez
        test_symbols = ['BTC_USDT', '1000SATS_USDT', 'ETH_USDT']
        test_symbol = None
        for s in test_symbols:
            if s in symbols:
                test_symbol = s
                break

        if not test_symbol:
            test_symbol = symbols[0] if symbols else None

    if test_symbol:
        print(f"\n🔍 Teszt szimbólum: {test_symbol}")
        data = loader.load_symbol_data(test_symbol)

        if data:
            print(f"\n📈 {test_symbol} összesítés:")
            for tf, df in data.items():
                print(f"   {tf}: {len(df)} sor, {df.memory_usage(deep=True).sum()/1024/1024:.2f} MB")
        else:
            print(f"❌ Nincs adat {test_symbol}-hez")

    # File információk (első 5)
    print(f"\n📁 File információk (első 5):")
    files_info = loader.get_file_info()[:5]
    for info in files_info:
        if 'error' in info:
            print(f"   ❌ {info['file']}: {info['error']}")
        else:
            print(f"   📄 {info['file']}: {info['rows']} sor, {info['size_mb']:.2f} MB")

if __name__ == "__main__":
    main()