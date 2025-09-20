@echo off
chcp 65001 >nul
echo 🚀 Feather Data Tesztelő - Binance Futures Bot
echo ================================================

cd /d "%~dp0"
echo 📁 Jelenlegi könyvtár: %CD%

REM Python ellenőrzése
python --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Python nem található a PATH-ban
    echo 💡 Telepítsd a Python-t vagy add hozzá a PATH-hoz
    pause
    exit /b 1
)

echo ✅ Python elérhető

REM Pandas ellenőrzése
python -c "import pandas" >nul 2>&1
if errorlevel 1 (
    echo ❌ Pandas nincs telepítve
    echo 💡 Telepítsd: pip install pandas pyarrow
    echo.
    echo Telepítsem most? (y/n)
    set /p choice=
    if /i "%choice%"=="y" (
        pip install pandas pyarrow
        if errorlevel 1 (
            echo ❌ Telepítés sikertelen
            pause
            exit /b 1
        )
    ) else (
        pause
        exit /b 1
    )
)

echo ✅ Pandas elérhető

REM Script létezésének ellenőrzése
if not exist "test_feather_data.py" (
    echo ❌ test_feather_data.py nem található
    echo 💡 Győződj meg róla, hogy a script a src\main\python könyvtárban van
    pause
    exit /b 1
)

echo ✅ Script megtalálva

REM Data könyvtár ellenőrzése
set DATA_PATHS=..\..\data ..\..\..\data E:\Codes\trading.bot2\trading.bot\data
for %%P in (%DATA_PATHS%) do (
    if exist "%%P" (
        echo ✅ Data könyvtár található: %%P
        goto :run_test
    )
)

echo ❌ Data könyvtár nem található
echo 💡 Ellenőrizd hogy a .feather file-ok a megfelelő helyen vannak
echo 📋 Keresett helyek:
for %%P in (%DATA_PATHS%) do (
    echo    %%P
)
pause
exit /b 1

:run_test
echo.
echo 🧪 === TESZT FUTTATÁSA ===
echo.

if "%~1"=="" (
    echo 🎯 Alapértelmezett teszt futtatása...
    python test_feather_data.py
) else (
    echo 🎯 Teszt futtatása szimbólummal: %1
    python test_feather_data.py %1
)

if errorlevel 1 (
    echo.
    echo ❌ A teszt hibával fejeződött be
) else (
    echo.
    echo ✅ Teszt sikeresen befejezve!
)

echo.
echo 📋 További használat:
echo    test_feathers.bat           - alapértelmezett teszt
echo    test_feathers.bat BTC       - BTC szimbólum tesztelése
echo    test_feathers.bat 1000SATS  - 1000SATS szimbólum tesztelése
echo.
pause