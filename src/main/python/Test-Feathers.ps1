# Test-Feathers.ps1 - Feather Data Tesztelő
param(
    [string]$Symbol = "",
    [switch]$Install,
    [switch]$ListSymbols
)

Write-Host "🚀 Feather Data Tesztelő - Binance Futures Bot" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan

# Jelenlegi könyvtár
$currentDir = Get-Location
Write-Host "📁 Jelenlegi könyvtár: $currentDir" -ForegroundColor Green

# Python ellenőrzése
try {
    $pythonVersion = python --version 2>&1
    Write-Host "✅ Python elérhető: $pythonVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ Python nem található a PATH-ban" -ForegroundColor Red
    Write-Host "💡 Telepítsd a Python-t vagy add hozzá a PATH-hoz" -ForegroundColor Yellow
    return
}

# Pandas ellenőrzése és telepítés
$pandasCheck = python -c "import pandas; import pyarrow; print('OK')" 2>&1
if ($pandasCheck -ne "OK") {
    Write-Host "❌ Pandas vagy PyArrow nincs telepítve" -ForegroundColor Red

    if ($Install) {
        Write-Host "📦 Függőségek telepítése..." -ForegroundColor Yellow
        pip install pandas pyarrow

        # Újra ellenőrzés
        $pandasCheck2 = python -c "import pandas; import pyarrow; print('OK')" 2>&1
        if ($pandasCheck2 -ne "OK") {
            Write-Host "❌ Telepítés sikertelen" -ForegroundColor Red
            return
        }
        Write-Host "✅ Függőségek telepítve" -ForegroundColor Green
    } else {
        Write-Host "💡 Telepítsd: pip install pandas pyarrow" -ForegroundColor Yellow
        Write-Host "💡 Vagy futtasd: .\Test-Feathers.ps1 -Install" -ForegroundColor Yellow
        return
    }
} else {
    Write-Host "✅ Pandas és PyArrow elérhető" -ForegroundColor Green
}

# Script ellenőrzése
if (-not (Test-Path "test_feather_data.py")) {
    Write-Host "❌ test_feather_data.py nem található" -ForegroundColor Red
    Write-Host "💡 Győződj meg róla, hogy a script a src\main\python könyvtárban van" -ForegroundColor Yellow
    return
}
Write-Host "✅ Script megtalálva" -ForegroundColor Green

# Data könyvtár ellenőrzése
$dataPaths = @("..\..\data", "..\..\..\data", "E:\Codes\trading.bot2\trading.bot\data", "data")
$dataFound = $false

foreach ($path in $dataPaths) {
    if (Test-Path $path) {
        Write-Host "✅ Data könyvtár található: $path" -ForegroundColor Green
        $dataFound = $true
        break
    }
}

if (-not $dataFound) {
    Write-Host "❌ Data könyvtár nem található" -ForegroundColor Red
    Write-Host "💡 Ellenőrizd hogy a .feather file-ok a megfelelő helyen vannak" -ForegroundColor Yellow
    Write-Host "📋 Keresett helyek:" -ForegroundColor Yellow
    foreach ($path in $dataPaths) {
        $fullPath = Resolve-Path $path -ErrorAction SilentlyContinue
        if ($fullPath) {
            Write-Host "   $path -> $fullPath (létezik)" -ForegroundColor Green
        } else {
            Write-Host "   $path (nem létezik)" -ForegroundColor Red
        }
    }
    return
}

# Szimbólumok listázása
if ($ListSymbols) {
    Write-Host "📋 Elérhető szimbólumok listázása..." -ForegroundColor Yellow
    python -c @"
import pandas as pd
from pathlib import Path
import sys

data_paths = ['../../data', '../../../data', 'E:/Codes/trading.bot2/trading.bot/data', 'data']
data_dir = None

for path in data_paths:
    if Path(path).exists():
        data_dir = Path(path)
        break

if not data_dir:
    print('❌ Data könyvtár nem található')
    sys.exit(1)

symbols = set()
for file in data_dir.glob('*.feather'):
    symbol = file.stem.rsplit('-', 1)[0]
    symbols.add(symbol)

print(f'📊 {len(symbols)} szimbólum található:')
for i, symbol in enumerate(sorted(symbols), 1):
    print(f'   {i:3}. {symbol}')
"@
    return
}

# Teszt futtatása
Write-Host ""
Write-Host "🧪 === TESZT FUTTATÁSA ===" -ForegroundColor Cyan
Write-Host ""

if ($Symbol -eq "") {
    Write-Host "🎯 Alapértelmezett teszt futtatása..." -ForegroundColor Yellow
    python test_feather_data.py
} else {
    Write-Host "🎯 Teszt futtatása szimbólummal: $Symbol" -ForegroundColor Yellow
    python test_feather_data.py $Symbol
}

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "✅ Teszt sikeresen befejezve!" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "❌ A teszt hibával fejeződött be" -ForegroundColor Red
}

Write-Host ""
Write-Host "📋 További használat:" -ForegroundColor Cyan
Write-Host "   .\Test-Feathers.ps1                    - alapértelmezett teszt" -ForegroundColor White
Write-Host "   .\Test-Feathers.ps1 -Symbol BTC        - BTC szimbólum tesztelése" -ForegroundColor White
Write-Host "   .\Test-Feathers.ps1 -ListSymbols       - elérhető szimbólumok listázása" -ForegroundColor White
Write-Host "   .\Test-Feathers.ps1 -Install           - függőségek telepítése" -ForegroundColor White
Write-Host ""