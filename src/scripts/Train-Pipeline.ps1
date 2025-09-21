param(
    [int]$SymbolsLimit = 10,
    [int]$MonthsBack = 6,
    [int]$Epochs = 50,
    [switch]$SkipDataGeneration,
    [switch]$SkipTraining,
    [switch]$UseRelaxedBacktest = $true
)

Write-Host "ML Trading Model Training Pipeline (Multi-Timeframe)" -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan

# Updated symbols list based on your available API symbols
# Prioritized by market cap and reliability
$symbols = @(
    "BTC_USDT", "ETH_USDT", "BNB_USDT", "XRP_USDT", "ADA_USDT",
    "AVAX_USDT", "SOL_USDT", "DOT_USDT", "LINK_USDT", "ATOM_USDT",
    "UNI_USDT", "ALGO_USDT", "VET_USDT", "MATIC_USDT", "TRX_USDT",
    "ETC_USDT", "LTC_USDT", "BCH_USDT", "XLM_USDT", "DOGE_USDT",
    "NEAR_USDT", "APT_USDT", "ARB_USDT", "OP_USDT", "SUI_USDT",
    "SEI_USDT", "INJ_USDT", "TIA_USDT", "JUP_USDT", "WIF_USDT"
)

$timeframes = @("1h", "4h", "1d")

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

function Test-SymbolAvailability {
    param(
        [string]$Symbol,
        [string]$Timeframe = "1d"
    )

    try {
        # Test with a small date range
        $endDate = Get-Date
        $startDate = $endDate.AddDays(-7)
        $startDateStr = $startDate.ToString("yyyy-MM-ddTHH:mm:ss")
        $endDateStr = $endDate.ToString("yyyy-MM-ddTHH:mm:ss")

        $testBody = @{
            symbol = $Symbol
            timeframe = $Timeframe
            startDate = $startDateStr
            endDate = $endDateStr
        } | ConvertTo-Json

        $testUrl = "http://localhost:8080/api/backtest"
        $response = Invoke-RestMethod -Uri $testUrl -Method Post -Body $testBody -ContentType "application/json" -TimeoutSec 15 -ErrorAction Stop

        # Even if no trades, if we get a successful response, the symbol is available
        return $true
    }
    catch {
        $errorMsg = $_.Exception.Message
        if ($errorMsg -contains "404" -or $errorMsg -contains "Not Found" -or $errorMsg -contains "Nem található") {
            return $false
        }
        # For other errors (timeouts, server errors), assume symbol might be available
        Write-Host "  Warning testing $Symbol`: $($_.Exception.Message)" -ForegroundColor Yellow
        return $true
    }
}

function Generate-TrainingDataForSymbol {
    param(
        [string]$Symbol,
        [string]$Timeframe,
        [int]$MonthsBack,
        [bool]$UseRelaxed = $true,
        [int]$RetryCount = 2
    )

    $endDate = Get-Date
    $startDate = $endDate.AddMonths(-$MonthsBack)

    $startDateStr = $startDate.ToString("yyyy-MM-ddTHH:mm:ss")
    $endDateStr = $endDate.ToString("yyyy-MM-ddTHH:mm:ss")

    Write-Host "--- Processing $Symbol ($Timeframe) ---" -ForegroundColor Yellow
    Write-Host "Running backtest for $Symbol $Timeframe from $($startDate.ToString('yyyy-MM-dd')) to $($endDate.ToString('yyyy-MM-dd'))"

    for ($attempt = 1; $attempt -le $RetryCount; $attempt++) {
        try {
            $backtestUrl = if ($UseRelaxed) {
                "http://localhost:8080/api/backtest/relaxed"
            } else {
                "http://localhost:8080/api/backtest"
            }

            $body = @{
                symbol = $Symbol
                timeframe = $Timeframe
                startDate = $startDateStr
                endDate = $endDateStr
            } | ConvertTo-Json

            $result = Invoke-RestMethod -Uri $backtestUrl -Method Post -Body $body -ContentType "application/json" -TimeoutSec 300 -ErrorAction Stop

            if ($result.success) {
                $tradesCount = if ($result.totalTrades) { $result.totalTrades } else { 0 }
                $returnPct = if ($result.totalReturnPercent) { $result.totalReturnPercent.ToString("F2") } else { "0.00" }

                Write-Host "✓ Backtest successful: $tradesCount trades, $returnPct% return" -ForegroundColor Green

                if ($tradesCount -gt 0) {
                    # Extract training samples from trades
                    $trainingSamples = @()

                    foreach ($trade in $result.trades) {
                        $sample = @{
                            symbol = $trade.symbol
                            timeframe = $Timeframe
                            direction = $trade.direction.ToString().ToLower()
                            entry_price = $trade.entryPrice
                            exit_price = $trade.exitPrice
                            pnl_percent = $trade.pnlPercent
                            score = $trade.score
                            entry_time = $trade.entryTime
                            exit_time = $trade.exitTime
                            successful = if ($trade.pnl -gt 0) { $true } else { $false }
                        }
                        $trainingSamples += $sample
                    }

                    # Save training data
                    $trainingDir = "src\main\resources\data\training"
                    if (-not (Test-Path $trainingDir)) {
                        New-Item -ItemType Directory -Path $trainingDir -Force | Out-Null
                    }

                    $filename = "training_data_$($Symbol)_$($Timeframe)_$($startDate.ToString('yyyyMM')).json"
                    $filepath = Join-Path $trainingDir $filename

                    $trainingSamples | ConvertTo-Json -Depth 10 | Set-Content $filepath -Encoding UTF8
                    Write-Host "✓ Training samples saved: $filepath ($($trainingSamples.Count) samples)" -ForegroundColor Green

                    return $trainingSamples.Count
                } else {
                    Write-Host "⚠ No trades generated for $Symbol $Timeframe" -ForegroundColor Yellow
                    return 0
                }
            } else {
                $errorMsg = if ($result.errorMessage) { $result.errorMessage } else { "Unknown error" }
                Write-Host "✗ Backtest failed for $Symbol`: $errorMsg" -ForegroundColor Red
                return 0
            }
        }
        catch {
            $errorMsg = $_.Exception.Message

            if ($errorMsg -contains "404" -or $errorMsg -contains "Not Found" -or $errorMsg -contains "Nem található") {
                Write-Host "✗ Symbol $Symbol not found (404) - skipping" -ForegroundColor Red
                return 0
            }
            elseif ($errorMsg -contains "timeout" -or $errorMsg -contains "időtúllépés") {
                Write-Host "⚠ Timeout on attempt $attempt for $Symbol $Timeframe" -ForegroundColor Yellow
                if ($attempt -lt $RetryCount) {
                    Write-Host "  Retrying in 5 seconds..." -ForegroundColor Gray
                    Start-Sleep -Seconds 5
                    continue
                }
            }
            elseif ($errorMsg -contains "500" -or $errorMsg -contains "Internal Server Error") {
                Write-Host "⚠ Server error on attempt $attempt for $Symbol $Timeframe" -ForegroundColor Yellow
                if ($attempt -lt $RetryCount) {
                    Write-Host "  Retrying in 10 seconds..." -ForegroundColor Gray
                    Start-Sleep -Seconds 10
                    continue
                }
            }
            else {
                Write-Host "✗ Error processing $Symbol $Timeframe (attempt $attempt): $errorMsg" -ForegroundColor Red
                if ($attempt -lt $RetryCount) {
                    Write-Host "  Retrying in 3 seconds..." -ForegroundColor Gray
                    Start-Sleep -Seconds 3
                    continue
                }
            }
        }
    }

    Write-Host "✗ All attempts failed for $Symbol $Timeframe" -ForegroundColor Red
    return 0
}

# 1. Environment Check
Write-Host "`n--- Environment Check ---" -ForegroundColor Yellow

try {
    $pythonVersion = python --version 2>&1
    Write-Host "Python: $pythonVersion" -ForegroundColor Green
}
catch {
    Write-Host "ERROR: Python not found" -ForegroundColor Red
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
    Write-Host "Missing packages: $($missingPackages -join ', ')" -ForegroundColor Yellow
    Write-Host "Installing..." -ForegroundColor Yellow
    $installCmd = "pip install " + ($missingPackages -join ' ') + " matplotlib seaborn joblib"
    Invoke-Expression $installCmd
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Package installation failed" -ForegroundColor Red
        exit 1
    }
}

Write-Host "Python packages OK" -ForegroundColor Green

if (-not (Test-JavaApp)) {
    Write-Host "Java application not running on localhost:8080" -ForegroundColor Red
    $choice = Read-Host "Would you like to start it? (y/n)"
    if ($choice -eq 'y' -or $choice -eq 'Y') {
        Start-Process -FilePath "cmd" -ArgumentList "/c", "mvn spring-boot:run" -WindowStyle Normal
        Write-Host "Waiting for application to start (30 sec)..." -ForegroundColor Yellow
        Start-Sleep -Seconds 30
        if (-not (Test-JavaApp)) {
            Write-Host "Application still not available" -ForegroundColor Red
            exit 1
        }
    } else { exit 1 }
}

Write-Host "Java application OK" -ForegroundColor Green

# 2. Directory Setup
Write-Host "`n--- Directory Setup ---" -ForegroundColor Yellow
$directories = @(
    "src\main\resources\data",
    "src\main\resources\data\models",
    "src\main\resources\data\training",
    "src\data",
    "src\data\training"
)

foreach ($dir in $directories) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        Write-Host "Created: $dir" -ForegroundColor Green
    }
}

# 3. Symbol Validation
Write-Host "`n--- Symbol Validation ---" -ForegroundColor Yellow
$selectedSymbols = $symbols | Select-Object -First $SymbolsLimit

Write-Host "Testing symbol availability (this may take a moment)..." -ForegroundColor Cyan
$validSymbols = @()
$symbolIndex = 0

foreach ($symbol in $selectedSymbols) {
    $symbolIndex++
    Write-Host "[$symbolIndex/$($selectedSymbols.Count)] Testing $symbol..." -NoNewline

    if (Test-SymbolAvailability $symbol) {
        $validSymbols += $symbol
        Write-Host " ✓" -ForegroundColor Green
    } else {
        Write-Host " ✗ (404 - not available)" -ForegroundColor Red
    }
}

Write-Host "`nUsing $($validSymbols.Count) valid symbols out of $($selectedSymbols.Count) tested" -ForegroundColor Cyan
if ($validSymbols.Count -eq 0) {
    Write-Host "ERROR: No valid symbols found!" -ForegroundColor Red
    exit 1
}

$selectedSymbols = $validSymbols

# 4. Generate Training Data
if (-not $SkipDataGeneration) {
    Write-Host "`n--- Training Data Generation ---" -ForegroundColor Yellow

    if ($UseRelaxedBacktest) {
        Write-Host "Using RELAXED backtest parameters for better signal generation" -ForegroundColor Cyan
    }

    $totalSamples = 0
    $successfulSymbols = 0
    $totalCombinations = $selectedSymbols.Count * $timeframes.Count
    $currentCombination = 0

    foreach ($symbol in $selectedSymbols) {
        foreach ($tf in $timeframes) {
            $currentCombination++
            Write-Host "`n[$currentCombination/$totalCombinations] Processing $symbol $tf..." -ForegroundColor Cyan

            $samplesGenerated = Generate-TrainingDataForSymbol -Symbol $symbol -Timeframe $tf -MonthsBack $MonthsBack -UseRelaxed $UseRelaxedBacktest
            $totalSamples += $samplesGenerated
            if ($samplesGenerated -gt 0) {
                $successfulSymbols++
            }

            # Small delay to avoid overwhelming the server
            Start-Sleep -Milliseconds 500
        }
    }

    Write-Host "`n=== TRAINING DATA SUMMARY ===" -ForegroundColor Cyan
    Write-Host "Total training samples generated: $totalSamples" -ForegroundColor White
    Write-Host "Successful symbol-timeframe combinations: $successfulSymbols" -ForegroundColor White
    Write-Host "Required minimum: 100 samples" -ForegroundColor Gray

    if ($totalSamples -lt 100) {
        Write-Host "WARNING: Insufficient training data generated!" -ForegroundColor Red
        Write-Host "Consider:" -ForegroundColor Yellow
        Write-Host "1. Increasing -MonthsBack parameter" -ForegroundColor Yellow
        Write-Host "2. Increasing -SymbolsLimit parameter" -ForegroundColor Yellow
        Write-Host "3. Checking backtest configuration" -ForegroundColor Yellow

        $choice = Read-Host "Continue with limited data? (y/n)"
        if ($choice -ne 'y' -and $choice -ne 'Y') {
            exit 1
        }
    }

    # Copy files to Python directory if they don't exist there
    $sourceDir = "src\main\resources\data\training"
    $targetDir = "src\data\training"

    if (Test-Path $sourceDir) {
        Get-ChildItem $sourceDir -Filter "*.json" | ForEach-Object {
            $targetFile = Join-Path $targetDir $_.Name
            if (-not (Test-Path $targetFile)) {
                Copy-Item $_.FullName $targetFile
                Write-Host "Copied $($_.Name) to Python directory" -ForegroundColor Green
            }
        }
    }

    Write-Host "Training data generation completed" -ForegroundColor Green
}
else {
    Write-Host "Training data generation skipped" -ForegroundColor Yellow
}

# 5. Verify Training Data Exists
Write-Host "`n--- Training Data Verification ---" -ForegroundColor Yellow
$pythonTrainingDir = "src\data\training"
$javaTrainingDir = "src\main\resources\data\training"

$pythonFiles = if (Test-Path $pythonTrainingDir) {
    (Get-ChildItem $pythonTrainingDir -Filter "training_data_*.json").Count
} else { 0 }

$javaFiles = if (Test-Path $javaTrainingDir) {
    (Get-ChildItem $javaTrainingDir -Filter "training_data_*.json").Count
} else { 0 }

Write-Host "Training files in Python directory: $pythonFiles" -ForegroundColor $(if ($pythonFiles -gt 0) { "Green" } else { "Red" })
Write-Host "Training files in Java directory: $javaFiles" -ForegroundColor $(if ($javaFiles -gt 0) { "Green" } else { "Red" })

if ($pythonFiles -eq 0) {
    Write-Host "ERROR: No training data available for model training!" -ForegroundColor Red
    exit 1
}

# 6. Train Model
if (-not $SkipTraining) {
    Write-Host "`n--- Model Training ---" -ForegroundColor Yellow
    Write-Host "Epochs: $Epochs" -ForegroundColor Cyan

    $currentDir = Get-Location
    Write-Host "Current directory: $currentDir" -ForegroundColor Gray

    $trainCmd = "python src\main\python\train_model.py training_data_*.json $Epochs"
    Write-Host "Training command: $trainCmd" -ForegroundColor Gray

    Invoke-Expression $trainCmd
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Model training failed" -ForegroundColor Red
        exit 1
    }
    Write-Host "Model training completed" -ForegroundColor Green
}
else {
    Write-Host "Model training skipped" -ForegroundColor Yellow
}

# 7. Test Model
Write-Host "`n--- Model Testing ---" -ForegroundColor Yellow
try {
    # Use one of our validated symbols for testing
    $testSymbol = $validSymbols[0]
    Write-Host "Test symbol: $testSymbol" -ForegroundColor Cyan

    $testResult = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/quick/$testSymbol?daysBack=30" -TimeoutSec 30
    if ($testResult.success) {
        Write-Host "Test results:" -ForegroundColor Green
        Write-Host "  Return: $($testResult.totalReturnPercent.ToString('F2'))%" -ForegroundColor White
        Write-Host "  Trades: $($testResult.totalTrades)" -ForegroundColor White
        Write-Host "  Win rate: $($testResult.winRate.ToString('F1'))%" -ForegroundColor White
    } else {
        Write-Host "Test failed: $($testResult.errorMessage)" -ForegroundColor Red
    }
}
catch {
    Write-Host "Model test error: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n--- Pipeline Complete ---" -ForegroundColor Cyan
Write-Host "Summary:" -ForegroundColor White
Write-Host "- Generated training data with $(if ($UseRelaxedBacktest) { 'RELAXED' } else { 'STANDARD' }) parameters" -ForegroundColor Gray
Write-Host "- Used $($validSymbols.Count) valid symbols over $MonthsBack months" -ForegroundColor Gray
Write-Host "- Trained model with $Epochs epochs" -ForegroundColor Gray
Write-Host "- Valid symbols used: $($validSymbols -join ', ')" -ForegroundColor Gray