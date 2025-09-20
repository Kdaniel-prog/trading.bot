# 🚀 Crypto Trading Bot with Deep Learning

Advanced cryptocurrency trading bot built with **Spring Boot** and **Python Deep Learning**, featuring automated backtesting, machine learning model training, and real-time predictions.

## 🎯 Features

### 🔄 **Backtesting System**
- Multi-symbol backtesting with historical data
- Feather file integration for fast data loading
- Comprehensive performance metrics (Sharpe ratio, drawdown, etc.)
- Trading rule analysis and optimization

### 🧠 **Machine Learning Framework**
- **Ensemble Models**: XGBoost + Neural Networks + LightGBM + Random Forest
- **Multi-timeframe Analysis**: 30m, 1h, 4h, 1d combined predictions
- **Feature Engineering**: 25+ technical indicators and market features
- **Real-time Predictions**: Live trading signals with confidence scores

### 📊 **Technical Analysis**
- Advanced SwingAlgo integration
- Multi-timeframe trend detection
- Risk management with stop-loss/take-profit
- Position sizing and portfolio management

### 🐳 **Production Ready**
- Docker containerized deployment
- RESTful API endpoints
- Monitoring and logging
- Scalable microservice architecture

---

## 🏗️ Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Java Service  │    │  Python ML       │    │  Data Storage   │
│   (Spring Boot) │◄──►│  (TensorFlow +   │◄──►│  (Feather +     │
│                 │    │   XGBoost)       │    │   Models)       │
│  • Backtesting  │    │                  │    │                 │
│  • Live Trading │    │  • Model Training│    │  • OHLCV Data   │
│  • Risk Mgmt    │    │  • Predictions   │    │  • ML Models    │
│  • API Endpoints│    │  • Evaluation    │    │  • Backtests    │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

---

## 🚀 Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 21+
- Python 3.11+
- 4GB+ RAM

### 1. Clone and Setup
```bash
git clone <repository>
cd trading-bot
make setup-dev
```

### 2. Start Services
```bash
# Production mode
make build && make run

# Development mode
make run-dev
```

### 3. Verify Installation
```bash
make test
curl http://localhost:8080/api/backtest/symbols
```

---

## 📈 Usage Examples

### 🔍 **Backtesting**
```bash
# Single symbol backtest
curl -X POST http://localhost:8080/api/backtest/run \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "timeframe": "4h",
    "startDate": "2024-01-01T00:00:00",
    "endDate": "2024-06-01T00:00:00"
  }'

# Multi-symbol backtest
curl -X POST http://localhost:8080/api/backtest/run/multi \
  -d '{"symbols": ["BTCUSDT", "ETHUSDT"], "timeframe": "4h", ...}'
```

### 🤖 **ML Model Training**
```bash
# Train from backtest results
curl -X POST http://localhost:8080/api/ml/train \
  -H "Content-Type: application/json" \
  -d '{
    "symbols": ["BTCUSDT", "ETHUSDT", "ADAUSDT"],
    "timeframe": "4h",
    "startDate": "2024-01-01T00:00:00",
    "endDate": "2024-11-01T00:00:00",
    "modelName": "swing_trader_v2"
  }'

# Or use Python directly
docker-compose exec ml-service python3 main.py train \
  --model-name swing_trader \
  --data-path /app/data/ml
```

### 🔮 **Real-time Predictions**
```bash
curl -X POST http://localhost:8080/api/ml/predict \
  -H "Content-Type: application/json" \
  -d '{
    "analysis": {
      "symbol": "BTCUSDT",
      "signal": "LONG",
      "score": 0.75,
      "tradingRule": 3
    },
    "currentPrice": 45000,
    "volume": 1250000,
    "rsi": 65,
    "macd": 125.5
  }'
```

### 📊 **Model Performance**
```bash
# Get model info
curl http://localhost:8080/api/ml/model/swing_trader/info

# Evaluate model
curl -X POST http://localhost:8080/api/ml/evaluate/swing_trader
```

---

## 🛠️ Configuration

### Java Application Properties
```yaml
# application.yml
backtest:
  initial:
    balance: 10000.0
  fee:
    rate: 0.0004
  position:
    size:
      percent: 0.1

ml:
  python:
    executable: python3
    script:
      path: /app/python
  data:
    output:
      path: /app/data/ml
  model:
    path: /app/models
```

### Python ML Configuration
```python
# config.py
NEURAL_NET_CONFIG = {
    'layers': [256, 128, 64, 32],
    'dropout': 0.3,
    'batch_size': 64,
    'epochs': 100
}

PREDICTION_THRESHOLDS = {
    'min_confidence': 0.6,
    'min_expected_return': 2.0,
    'max_risk_score': 0.7
}
```

---

## 🧪 ML Model Details

### **Ensemble Architecture**
1. **XGBoost Classifier** (40% weight) - Profitability prediction
2. **Neural Network Classifier** (35% weight) - Pattern recognition
3. **Random Forest** (25% weight) - Feature importance & robustness
4. **XGBoost Regressor** (60% weight) - Return magnitude prediction
5. **Neural Network Regressor** (40% weight) - Non-linear return modeling

### **Feature Engineering (25+ features)**
```python
Technical Indicators:
- RSI, MACD, Bollinger Bands
- Moving Averages (SMA, EMA)
- ATR, ADX, Volume indicators

Multi-timeframe Features:
- 1h, 4h, 1d trend alignment
- Cross-timeframe momentum
- Volatility across timeframes

Market Context:
- Hour of day / Day of week effects
- Market regime detection
- Support/Resistance levels
```

### **Model Performance Metrics**
- **Classification**: Accuracy, AUC, Precision, Recall
- **Regression**: RMSE, MAE, Directional Accuracy
- **Trading Metrics**: Win Rate, Profit Factor, Sharpe Ratio
- **Risk Metrics**: Max Drawdown, Risk-adjusted returns

---

## 📁 Project Structure

```
trading-bot/
├── src/main/java/kd/trading/bot/
│   ├── model/
│   │   ├── Position.java                    # Trading positions
│   │   ├── backtest/
│   │   │   ├── BacktestTrade.java          # Trade records
│   │   │   ├── BacktestResult.java         # Performance metrics
│   │   │   └── BacktestConfiguration.java   # Settings
│   │   └── ml/
│   │       ├── MLFeatures.java             # Feature vectors
│   │       ├── MLPrediction.java           # Prediction results
│   │       └── MLTrainingData.java         # Training samples
│   ├── service/
│   │   ├── backtest/BacktestService.java   # Core backtesting
│   │   └── ml/PythonMLService.java         # ML integration
│   └── web/
│       ├── BacktestController.java         # Backtest API
│       └── MLController.java               # ML API
├── python/
│   ├── train_model.py                      # Model training
│   ├── predict.py                          # Predictions
│   ├── evaluate_model.py                   # Model evaluation
│   ├── data_loader.py                      # Data processing
│   └── config.py                           # ML configuration
├── data/
│   ├── feather/                            # Raw OHLCV data
│   └── ml/                                 # Training data
├── models/                                 # Trained ML models
└── docker-compose.yml                      # Container orchestration
```

---

## 🔧 Development

### **Local Development**
```bash
# Setup environment
make setup-dev

# Run in development mode with live reload
make run-dev

# Access services
# Java API: http://localhost:8080
# Jupyter: http://localhost:8888 (token: trading123)
```

### **Testing**
```bash
# Run all tests
make test

# Test ML components
docker-compose exec ml-service ./run_ml.sh test

# Test Java components
./mvnw test
```

### **Adding New Features**

1. **New Trading Rules**: Extend `SwingAlgoService`
2. **New ML Models**: Add to `TradingMLTrainer.train_ensemble_model()`
3. **New Features**: Modify `feature_columns` in `train_model.py`
4. **New Endpoints**: Add to `BacktestController` or `MLController`

---

## 📊 Performance & Monitoring

### **Metrics Dashboard**
- Real-time trading performance
- Model prediction accuracy
- System resource usage
- Error rates and alerts

### **Logging**
```bash
# View logs
make logs

# Java application logs
make logs-java

# ML service logs
make logs-ml
```

### **Health Checks**
```bash
# System health
curl http://localhost:8080/actuator/health

# ML model status
curl http://localhost:8080/api/ml/model/swing_trader/info
```

---

## 🚀 Deployment

### **Production Deployment**
```bash
# Build production images
make build

# Deploy with proper resources
docker-compose up -d

# Backup models
make backup-models
```

### **Environment Variables**
```bash
# Java Service
SPRING_PROFILES_ACTIVE=production
ML_PYTHON_EXECUTABLE=python3
ML_DATA_OUTPUT_PATH=/app/data/ml

# Python ML Service
PYTHONPATH=/app
ML_DATA_PATH=/app/data/ml
ML_MODEL_PATH=/app/models
TF_CPP_MIN_LOG_LEVEL=2
```

### **Resource Requirements**
- **CPU**: 2+ cores recommended
- **RAM**: 4GB minimum, 8GB recommended
- **Storage**: 10GB+ for data and models
- **Network**: Low latency for real-time trading

---

## 🤝 Contributing

1. Fork the repository
2. Create feature branch: `git checkout -b feature/amazing-feature`
3. Commit changes: `git commit -m 'Add amazing feature'`
4. Push to branch: `git push origin feature/amazing-feature`
5. Open Pull Request

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 🆘 Support

### **Common Issues**

**Q: Model training fails with "Insufficient data"**
A: Ensure you have run backtests first to generate training data in `/data/ml/`

**Q: Python import errors**
A: Run `make setup-dev` to install all dependencies

**Q: Low prediction accuracy**
A: Try training with more historical data or adjusting hyperparameters

### **Getting Help**
- 📧 Email: [support@example.com]
- 💬 Discord: [discord-link]
- 📚 Documentation: [docs-link]
- 🐛 Issues: [github-issues]

---

## 🎯 Roadmap

- [ ] **Advanced ML Models**: Transformer networks, LSTM
- [ ] **Real-time Data**: WebSocket market data integration
- [ ] **Portfolio Management**: Multi-asset allocation
- [ ] **Risk Management**: Advanced position sizing algorithms
- [ ] **Web Interface**: React-based trading dashboard
- [ ] **Mobile App**: React Native mobile application
- [ ] **Cloud Deployment**: AWS/GCP integration
- [ ] **Backtesting UI**: Interactive backtesting interface

---

*Built with ❤️ for the crypto trading community*
