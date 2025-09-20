#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Egyszerű feather file tesztelő
Használat: python simple_feather_test.py [SYMBOL]
"""
import pandas as pd
import sys
from pathlib import Path

def find_data_directory():
    """Megkeresi a data könyvtárat"""
    current = Path(__file__).parent

    # Lehetséges helyek
    candidates = [
        current / "../../data",
        current / "../../../data",
        Path("E:/Codes/trading.bot2/trading.bot/data"),
        current / "data"
    ]

    for path in candidates:
        if path.exists() and any(path.glob("*.feather")):
            return path.resolve()

    return None

def list_symbols(data_dir):
    """Elérhető szimbólumok listázása"""
    symbols = set()
    for file in data_dir.glob("*.feather"):
        # BTC_USDT-1d.feather -> BTC_USDT
        symbol = file.stem.split('-')[0]
        symbols.add(symbol)
    return sorted(symbols)

def test_symbol_data(data_dir, symbol):
    """Egy szimbólum tesztelése"""
    print(f"\n=== {symbol} TESZT ===")

    timeframes = ['1d', '4h', '1h', '30m']
    found_data = False

    for tf in timeframes:
        file_path = data_dir / f"{symbol}-{tf}.feather"

        if file_path.exists():
            try:
                df = pd.read_feather(file_path)
                print(f"✓ {tf}: {len(df)} sorok, oszlopok: {list(df.columns)}")

                if len(df) > 0:
                    print(f"  Első sor: {dict(df.iloc[0])}")

                found_data = True

            except Exception as e:
                print(f"✗ {tf}: Hiba - {e}")
        else:
            print(f"- {tf}: nem található")

    if not found_data:
        print(f"✗ Nincs adat {symbol} szimbólumhoz")

def main():
    print("Feather File Tesztelő")
    print("=" * 30)

    # Data könyvtár keresése
    data_dir = find_data_directory()
    if not data_dir:
        print("HIBA: Data könyvtár nem található!")
        print("Keresett helyek:")
        current = Path(__file__).parent
        for path in [current / "../../data", current / "../../../data",
                     Path("E:/Codes/trading.bot2/trading.bot/data"), current / "data"]:
            print(f"  {path} - {'LÉTEZIK' if path.exists() else 'NEM LÉTEZIK'}")
        return

    print(f"Data könyvtár: {data_dir}")

    # Elérhető szimbólumok
    symbols = list_symbols(data_dir)
    print(f"\nElérhető szimbólumok ({len(symbols)} db):")
    for i, symbol in enumerate(symbols[:10]):
        print(f"  {i+1}. {symbol}")
    if len(symbols) > 10:
        print(f"  ... és még {len(symbols)-10} db")

    # Teszt szimbólum kiválasztása
    if len(sys.argv) > 1:
        test_symbol = sys.argv[1].upper()
        # Rugalmas keresés
        matches = [s for s in symbols if test_symbol in s.upper()]
        if matches:
            test_symbol = matches[0]
        else:
            print(f"\nHIBA: '{sys.argv[1]}' nem található")
            return
    else:
        # Alapértelmezett: BTC, ETH vagy első
        test_symbol = None
        for candidate in ['BTC_USDT', 'BTCUSDT', 'ETH_USDT', 'ETHUSDT']:
            matches = [s for s in symbols if candidate in s]
            if matches:
                test_symbol = matches[0]
                break

        if not test_symbol and symbols:
            test_symbol = symbols[0]

    if test_symbol:
        test_symbol_data(data_dir, test_symbol)
    else:
        print("Nincs tesztelhető szimbólum!")

if __name__ == "__main__":
    main()