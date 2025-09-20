"""
Feather adatok tesztelése Binance futures trading bothoz
Használat: python test_feather_data.py [SYMBOL]
"""
import pandas as pd
import numpy as np
from pathlib import Path
import sys
import os
from datetime import datetime

# Project paths
PROJECT_ROOT = Path(__file__).parent.parent.parent.parent  # src/main/python -> project root
DATA_DIR = PROJECT_ROOT / "data"
PROCESSED_DIR = PROJECT_ROOT / "processed_data"

def setup_directories():
    """Könyvtárak létrehozása ha nem léteznek"""
    PROCESSED_DIR.mkdir(exist_ok=True)
    print(f"📁 Project root: {PROJECT_ROOT}")
    print(f"📁 Data dir: {DATA_DIR}")
    print(f"📁 Processed dir: {PROCESSED_DIR}")

def check_data_files():
    """Ellenőrzi a data könyvtár tartalmát"""
    if not DATA_DIR.exists():
        print(f"❌ Data könyvtár nem található: {DATA_DIR}")
        return False

    feather_files = list(DATA_DIR.glob("*.feather"))
    if not feather_files:
        print(f"❌ Nincsenek .feather file-ok a {DATA_DIR} könyvtárban")
        return False

    print(f"✅ {len(feather_files)} .feather file található")

    # Szimbólumok csoportosítása
    symbols = {}
    timeframes = set()

    for file in feather_files:
        parts = file.stem.split('-')
        if len(parts) >= 2:
            symbol = '-'.join(parts[:-1])  # Minden a timeframe előtt
            timeframe = parts[-1]

            if symbol not in symbols:
                symbols[symbol] = []
            symbols[symbol].append(timeframe)
            timeframes.add(timeframe)

    print(f"📊 {len(symbols)} egyedi szimbólum")
    print(f"⏰ Timeframe-ek: {sorted(timeframes)}")

    return symbols

def analyze_symbol_data(symbol, max_samples=5):
    """Egy szimbólum adatainak részletes elemzése"""
    print(f"\n🔍 === {symbol} ELEMZÉS ===")

    timeframes = ['1d', '4h', '1h', '30m']
    symbol_data = {}

    for tf in timeframes:
        file_path = DATA_DIR / f"{symbol}-{tf}.feather"
        if not file_path.exists():
            print(f"⚠️  {tf}: file nem található")
            continue

        try:
            df = pd.read_feather(file_path)
            symbol_data[tf] = df

            print(f"\n📈 {tf} timeframe:")
            print(f"   Sorok: {len(df)}")
            print(f"   Oszlopok: {list(df.columns)}")
            print(f"   Memória: {df.memory_usage(deep=True).sum()/1024/1024:.2f} MB")

            # Idősor info
            time_cols = [col for col in df.columns if any(word in col.lower() for word in ['time', 'date', 'timestamp'])]
            if time_cols:
                time_col = time_cols[0]
                print(f"   Időoszlop: {time_col}")
                print(f"   Időtartam: {df[time_col].min()} -> {df[time_col].max()}")

            # Numerikus oszlopok statisztikái
            numeric_cols = df.select_dtypes(include=[np.number]).columns
            if len(numeric_cols) > 0:
                print(f"   Numerikus oszlopok: {list(numeric_cols)}")
                print(f"   Példa értékek:")
                for col in numeric_cols[:3]:  # Első 3
                    sample_vals = df[col].dropna().head(3).values
                    print(f"     {col}: {sample_vals}")

            # Hiányzó értékek
            missing = df.isnull().sum()
            if missing.sum() > 0:
                print(f"   ⚠️  Hiányzó értékek: {dict(missing[missing > 0])}")

            # Első pár sor
            if max_samples > 0:
                print(f"   📋 Első {min(max_samples, len(df))} sor:")
                print(df.head(max_samples).to_string(index=False))

        except Exception as e:
            print(f"❌ Hiba {tf} betöltésekor: {e}")

    return symbol_data

def create_sample_processing():
    """Minta adatfeldolgozás - train/test split"""
    print(f"\n🔄 === MINTA ADATFELDOLGOZÁS ===")

    # Első elérhető szimbólum kiválasztása
    symbols = check_data_files()
    if not symbols:
        return

    test_symbol = list(symbols.keys())[0]
    print(f"🎯 Teszt szimbólum: {test_symbol}")

    # 1d timeframe betöltése
    file_path = DATA_DIR / f"{test_symbol}-1d.feather"
    if not file_path.exists():
        print(f"❌ {test_symbol}-1d.feather nem található")
        return

    df = pd.read_feather(file_path)
    print(f"📊 Eredeti adatok: {len(df)} sor")

    # Train/test split (80/20)
    split_idx = int(len(df) * 0.8)
    train_df = df.iloc[:split_idx].copy()
    test_df = df.iloc[split_idx:].copy()

    print(f"🚂 Train: {len(train_df)} sor ({len(train_df)/len(df)*100:.1f}%)")
    print(f"🧪 Test: {len(test_df)} sor ({len(test_df)/len(df)*100:.1f}%)")

    # Mentés
    train_path = PROCESSED_DIR / f"{test_symbol}_train.feather"
    test_path = PROCESSED_DIR / f"{test_symbol}_test.feather"

    train_df.to_feather(train_path)
    test_df.to_feather(test_path)

    print(f"💾 Mentve: {train_path}")
    print(f"💾 Mentve: {test_path}")

    # Ellenőrzés
    train_check = pd.read_feather(train_path)
    test_check = pd.read_feather(test_path)
    print(f"✅ Ellenőrzés: train={len(train_check)}, test={len(test_check)}")

def main():
    print("🚀 === FEATHER DATA TESZTELŐ ===\n")

    # Setup
    setup_directories()

    # Data files check
    symbols = check_data_files()
    if not symbols:
        return

    # Szimbólum kiválasztása
    if len(sys.argv) > 1:
        target_symbol = sys.argv[1].upper()
        # Flexible matching
        matching_symbols = [s for s in symbols.keys() if target_symbol in s.upper()]
        if matching_symbols:
            target_symbol = matching_symbols[0]
            print(f"🎯 Választott szimbólum: {target_symbol}")
        else:
            print(f"❌ '{sys.argv[1]}' szimbólum nem található")
            print(f"💡 Elérhető szimbólumok: {list(symbols.keys())[:10]}...")
            return
    else:
        # Alapértelmezett: első BTC vagy ETH vagy első elérhető
        priority_symbols = ['BTC_USDT', 'BTCUSDT', 'ETH_USDT', 'ETHUSDT']
        target_symbol = None

        for priority in priority_symbols:
            matches = [s for s in symbols.keys() if priority in s.upper()]
            if matches:
                target_symbol = matches[0]
                break

        if not target_symbol:
            target_symbol = list(symbols.keys())[0]

        print(f"🎯 Alapértelmezett szimbólum: {target_symbol}")

    # Részletes elemzés
    analyze_symbol_data(target_symbol)

    # Sample processing
    create_sample_processing()

    print(f"\n✅ === TESZT KÉSZ ===")
    print(f"📁 Eredmények: {PROCESSED_DIR}")

if __name__ == "__main__":
    main()