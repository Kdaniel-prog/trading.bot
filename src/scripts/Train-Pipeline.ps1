param(
    [int]$SymbolsLimit = 10,
    [int]$MonthsBack = 6,
    [int]$Epochs = 50,
    [switch]$SkipDataGeneration,
    [switch]$SkipTraining
)

Write-Host "ML Trading Model Training Pipeline (Multi-Timeframe)" -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan

# Szimbólumok
$symbols = @(
    "BTC_USDT", "ETH_USDT", "BNB_USDT"  # ide illeszd a teljes listád
# ... a többi szimbólum
)

$timeframes = @("1d", "1h", "4h", "30m")

# Functions
function Test-PythonPackage {
    param([string]$PackageName)
    $result = python -c "try: import $PackageName; print('OK')
except: print('MISSING')" 2>$null
    return $result -eq "OK"
}

function Test-JavaApp {
    try {
        $response = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/symbols" -TimeoutSec 5 -ErrorAction Stop
        return $true
    }
    catch {
        return $false
    }
}

# 1. Environment Check
Write-Host "`n--- Environment Check ---" -ForegroundColor Yellow

try {
    $pythonVersion = python --version 2>&1
    Write-Host "Python: $pythonVersion" -ForegroundColor Green
}
catch {
    Write-Host "HIBA: Python nem talalhato" -ForegroundColor Red
    exit 1
}

$requiredPackages = @("tensorflow", "pandas", "numpy", "sklearn", "requests")
$missingPackages = @()

foreach ($package in $requiredPackages) {
    if (-not (Test-PythonPackage $package)) {
        $missingPackages += $package
    }
}

if ($missingPackages.Count -gt 0) {
    Write-Host "Hianyzo csomagok: $($missingPackages -join ', ')" -ForegroundColor Yellow
    Write-Host "Telepites..." -ForegroundColor Yellow
    $installCmd = "pip install " + ($missingPackages -join ' ') + " matplotlib seaborn joblib"
    Invoke-Expression $installCmd
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Csomag telepites sikertelen" -ForegroundColor Red
        exit 1
    }
}

Write-Host "Python csomagok OK" -ForegroundColor Green

if (-not (Test-JavaApp)) {
    Write-Host "Java alkalmazas nem fut localhost:8080-on" -ForegroundColor Red
    $choice = Read-Host "Szeretned elinditani? (y/n)"
    if ($choice -eq 'y' -or $choice -eq 'Y') {
        Start-Process -FilePath "cmd" -ArgumentList "/c", "mvn spring-boot:run" -WindowStyle Normal
        Write-Host "Varakozas az alkalmazas indulasara (30 sec)..." -ForegroundColor Yellow
        Start-Sleep -Seconds 30
        if (-not (Test-JavaApp)) {
            Write-Host "Alkalmazas meg mindig nem elerheto" -ForegroundColor Red
            exit 1
        }
    } else { exit 1 }
}

Write-Host "Java alkalmazas OK" -ForegroundColor Green

# 2. Directory Setup
Write-Host "`n--- Directory Setup ---" -ForegroundColor Yellow
$directories = @(
    "src\main\resources\data",
    "src\main\resources\data\models",
    "src\main\resources\data\training"
)

foreach ($dir in $directories) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        Write-Host "Letrehozva: $dir" -ForegroundColor Green
    }
}

# 3. Generate Training Data for all symbols and timeframes
if (-not $SkipDataGeneration) {
    Write-Host "`n--- Training Data Generation ---" -ForegroundColor Yellow
    $selectedSymbols = $symbols | Select-Object -First $SymbolsLimit

    foreach ($symbol in $selectedSymbols) {
        foreach ($tf in $timeframes) {
            Write-Host "Generating data for $symbol ($tf)" -ForegroundColor Cyan
            $generateCmd = "python src\main\python\generate_training_data.py generate $SymbolsLimit $MonthsBack $tf"
            Invoke-Expression $generateCmd
            if ($LASTEXITCODE -ne 0) {
                Write-Host "Training adat generalas sikertelen: $symbol $tf" -ForegroundColor Red
                exit 1
            }
        }
    }

    Write-Host "Training adatok generalva minden symbolhoz és timeframe-hez" -ForegroundColor Green
}
else {
    Write-Host "Training data generation kihagyva" -ForegroundColor Yellow
}

# 4. Train Model
if (-not $SkipTraining) {
    Write-Host "`n--- Model Training ---" -ForegroundColor Yellow
    Write-Host "Epochs: $Epochs" -ForegroundColor Cyan
    $trainCmd = "python src\main\python\train_model.py `"training_data_*.json`" $Epochs"
    Write-Host "Futtatasi parancs: $trainCmd" -ForegroundColor Gray
    Invoke-Expression $trainCmd
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Model tanitas sikertelen" -ForegroundColor Red
        exit 1
    }
    Write-Host "Model tanitas kesz" -ForegroundColor Green
}
else {
    Write-Host "Model training kihagyva" -ForegroundColor Yellow
}

# 5. Test Model
# 5. Test Model
Write-Host "`n--- Model Testing ---" -ForegroundColor Yellow
try {
    $symbolsAvailable = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/symbols" -TimeoutSec 10
    $testSymbol = $symbolsAvailable[0]
    Write-Host "Test szimbolum: $testSymbol" -ForegroundColor Cyan

    $testResult = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/quick/$testSymbol?daysBack=30" -TimeoutSec 30

    if ($testResult.success) {
        Write-Host "Test eredmeny:" -ForegroundColor Green
        Write-Host "  Return: $($testResult.totalReturnPercent.ToString('F2'))%" -ForegroundColor White
        Write-Host "  Trades: $($testResult.totalTrades)" -ForegroundColor White
        Write-Host "  Win rate: $($testResult.winRate.ToString('F1'))%" -ForegroundColor White
    } else {
        Write-Host "Test sikertelen: $($testResult.errorMessage)" -ForegroundColor Red
    }
}
catch {
    Write-Host "Model test hiba: $($_.Exception.Message)" -ForegroundColor Red
}
Write-Host "`n--- Pipeline Kesz ---" -ForegroundColor Cyan
