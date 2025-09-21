# test_signal_generation.py - Új script a signal teszteléshez
# !/usr/bin/env python3
"""
Test signal generation for debugging
"""

import requests
import json
from datetime import datetime, timedelta


def test_single_symbol_backtest(symbol="BTCUSDT", base_url="http://localhost:8080"):
    """
    Test single symbol to check signal generation
    """
    api_base = f"{base_url}/api/backtest"

    # Use 2024 dates
    end_date = datetime(2024, 9, 20)
    start_date = datetime(2024, 6, 20)  # 3 months

    payload = {
        "symbol": symbol,
        "timeframe": "4h",
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat()
    }

    print(f"Testing backtest for {symbol}")
    print(f"Date range: {start_date.date()} to {end_date.date()}")
    print(f"Payload: {json.dumps(payload, indent=2)}")

    try:
        response = requests.post(
            f"{api_base}/run",
            json=payload,
            timeout=60
        )
        response.raise_for_status()

        result = response.json()

        print(f"\n=== BACKTEST RESULT ===")
        print(f"Success: {result.get('success', False)}")
        print(f"Symbol: {result.get('symbol')}")
        print(f"Total Trades: {result.get('totalTrades', 0)}")
        print(f"Total Return: {result.get('totalReturnPercent', 0):.2f}%")
        print(f"Win Rate: {result.get('winRate', 0):.1f}%")

        if result.get('success') and result.get('totalTrades', 0) > 0:
            print(f"Winning Trades: {result.get('winningTrades', 0)}")
            print(f"Losing Trades: {result.get('losingTrades', 0)}")
            print(f"Profit Factor: {result.get('profitFactor', 0):.2f}")
            print(f"Max Drawdown: {result.get('maxDrawdownPercent', 0):.2f}%")

            # Show first few trades
            trades = result.get('trades', [])
            if trades:
                print(f"\nFirst 3 trades:")
                for i, trade in enumerate(trades[:3]):
                    print(
                        f"  Trade {i + 1}: {trade.get('side')} at {trade.get('entryPrice')} -> {trade.get('exitPrice')} = {trade.get('pnlPercent', 0):.2f}%")

        else:
            print(f"Error: {result.get('errorMessage', 'No trades generated')}")

        return result

    except Exception as e:
        print(f"Test failed: {e}")
        return None


def test_multiple_symbols():
    """
    Test multiple symbols to find which ones generate trades
    """
    test_symbols = ["BTCUSDT", "ETHUSDT", "ADAUSDT", "DOTUSDT", "LINKUSDT"]

    results = []

    for symbol in test_symbols:
        print(f"\n{'=' * 50}")
        result = test_single_symbol_backtest(symbol)
        if result:
            results.append({
                'symbol': symbol,
                'success': result.get('success', False),
                'trades': result.get('totalTrades', 0),
                'return': result.get('totalReturnPercent', 0)
            })

    print(f"\n{'=' * 50}")
    print("SUMMARY:")
    for r in results:
        print(f"{r['symbol']}: {r['trades']} trades, {r['return']:.2f}% return")


if __name__ == "__main__":
    print("Testing signal generation...")
    test_multiple_symbols()