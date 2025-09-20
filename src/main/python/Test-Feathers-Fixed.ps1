param(
    [string]$Symbol = "",
[switch]$Install,
[switch]$ListSymbols
)

Write-Host "Feather Data Tesztelo - Binance Futures Bot" -ForegroundColor Cyan
Write-Host "===========================================" -ForegroundColor Cyan

# Jelenlegi konyvtar
$currentDir = Get-Location
Write-Host "Jelenlegi konyvtar: $currentDir" -ForegroundColor Green

# Python ellenorzese
try {
$pythonVersion = python --version 2>&1
Write-Host "Python elerheto: $pythonVersion" -ForegroundColor Green
}
catch {
    Write-Host "HIBA: Python nem talalhato a PATH-ban" -ForegroundColor Red
Write-Host "Telepitsd a Python-t vagy add hozza a PATH-hoz" -ForegroundColor Yellow
return
}

# Pandas ellenorzese es telepites
$pandasCheck = python -c "import pandas; import pyarrow; print('OK')" 2>&1
if ($pandasCheck -ne "OK") {
Write-Host "HIBA: Pandas vagy PyArrow nincs telepitve" -ForegroundColor Red

if ($Install) {
Write-Host "Fuggosegek telepitese..." -ForegroundColor Yellow
pip install pandas pyarrow

# Ujra ellenorzes
$pandasCheck2 = python -c "import pandas; import pyarrow; print('OK')" 2>&1
if ($pandasCheck2 -ne "OK") {
Write-Host "HIBA: Telepites sikertelen" -ForegroundColor Red
return
}
Write-Host "Fuggosegek telepitve" -ForegroundColor Green
}
else {
    Write-Host "Telepitsd: pip install pandas pyarrow" -ForegroundColor Yellow
Write-Host "Vagy futtasd: .\Test-Feathers-Fixed.ps1 -Install" -ForegroundColor Yellow
return
}
}
else {
    Write-Host "Pandas es PyArrow elerheto" -ForegroundColor Green
}

# Script ellenorzese
if (-not (Test-Path "simple_feather_test.py")) {
Write-Host "HIBA: simple_feather_test.py nem talalhato" -ForegroundColor Red
Write-Host "Hozd letre a scriptet a src\main\python konyvtarban" -ForegroundColor Yellow
return
}
Write-Host "Script megtalálva" -ForegroundColor Green

# Data konyvtar ellenorzese
$dataPaths = @("..\..\data", "..\..\..\data", "E:\Codes\trading.bot2\trading.bot\data", "data")
$dataFound = $false

foreach ($path in $dataPaths) {
if (Test-Path $path) {
$featherCount = (Get-ChildItem $path -Filter "*.feather" -ErrorAction SilentlyContinue).Count
if ($featherCount -gt 0) {
Write-Host "Data konyvtar talalhato: $path ($featherCount feather file)" -ForegroundColor Green
$dataFound = $true
break
}
}
}

if (-not $dataFound) {
Write-Host "HIBA: Data konyvtar nem talalhato vagy ures" -ForegroundColor Red
Write-Host "Ellenorizd hogy a .feather file-ok a megfelelo helyen vannak" -ForegroundColor Yellow
Write-Host "Keresett helyek:" -ForegroundColor Yellow
foreach ($path in $dataPaths) {
if (Test-Path $path) {
$count = (Get-ChildItem $path -Filter "*.feather" -ErrorAction SilentlyContinue).Count
Write-Host "   $path -> letezik, $count feather file" -ForegroundColor Yellow
}
else {
Write-Host "   $path -> nem letezik" -ForegroundColor Red
}
}
return
}

# Szimbolumok listazasa
if ($ListSymbols) {
Write-Host "Elerheto szimbolumok listazasa..." -ForegroundColor Yellow
python simple_feather_test.py
return
}

# Teszt futtatasa
Write-Host ""
Write-Host "=== TESZT FUTTATASA ===" -ForegroundColor Cyan
Write-Host ""

if ($Symbol -eq "") {
Write-Host "Alapertelmezett teszt futtatasa..." -ForegroundColor Yellow
python simple_feather_test.py
}
else {
Write-Host "Teszt futtatasa szimbolummal: $Symbol" -ForegroundColor Yellow
python simple_feather_test.py $Symbol
}

if ($LASTEXITCODE -eq 0) {
Write-Host ""
Write-Host "Teszt sikeresen befejezo!" -ForegroundColor Green
}
else {
Write-Host ""
Write-Host "A teszt hibával fejezodött be" -ForegroundColor Red
}

Write-Host ""
Write-Host "Tovabbi hasznalat:" -ForegroundColor Cyan
Write-Host "   .\Test-Feathers-Fixed.ps1                    - alapertelmezett teszt" -ForegroundColor White
Write-Host "   .\Test-Feathers-Fixed.ps1 -Symbol BTC        - BTC szimbolum tesztelese" -ForegroundColor White
Write-Host "   .\Test-Feathers-Fixed.ps1 -ListSymbols       - elerheto szimbolumok listazasa" -ForegroundColor White
Write-Host "   .\Test-Feathers-Fixed.ps1 -Install           - fuggosegek telepitese" -ForegroundColor White
Write-Host ""