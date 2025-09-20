# Dockerfile - Python ML Service
FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    gcc \
    g++ \
    libgomp1 \
    wget \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for better caching
COPY python/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy Python ML code
COPY python/ .

# Create necessary directories
RUN mkdir -p /app/data/ml /app/models /app/logs

# Make scripts executable
RUN chmod +x *.py
COPY python/run_ml.sh .
RUN chmod +x run_ml.sh

# Set environment variables
ENV PYTHONPATH=/app
ENV ML_DATA_PATH=/app/data/ml
ENV ML_MODEL_PATH=/app/models
ENV TF_CPP_MIN_LOG_LEVEL=2

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD python3 -c "import tensorflow as tf; import sklearn; print('OK')" || exit 1

# Default command
CMD ["python3", "main.py", "--help"]

---

# docker-compose.yml
version: '3.8'

services:
  trading-bot:
    build:
      context: .
      dockerfile: Dockerfile.java
    container_name: trading-bot-java
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data
      - ./models:/app/models
      - ./logs:/app/logs
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - ML_PYTHON_EXECUTABLE=python3
      - ML_PYTHON_SCRIPT_PATH=/app/python
      - ML_DATA_OUTPUT_PATH=/app/data/ml
      - ML_MODEL_PATH=/app/models
      - FEATHER_DATA_PATH=/app/data/feather
    depends_on:
      - ml-service
    networks:
      - trading-network
    restart: unless-stopped

  ml-service:
    build:
      context: .
      dockerfile: Dockerfile.python
    container_name: trading-ml-python
    volumes:
      - ./data:/app/data
      - ./models:/app/models
      - ./logs:/app/logs
    environment:
      - PYTHONPATH=/app
      - ML_DATA_PATH=/app/data/ml
      - ML_MODEL_PATH=/app/models
      - FEATHER_DATA_PATH=/app/data/feather
      - TF_CPP_MIN_LOG_LEVEL=2
    networks:
      - trading-network
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 4G
          cpus: '2.0'

  # Optional: Jupyter notebook for ML experimentation
  jupyter:
    build:
      context: .
      dockerfile: Dockerfile.jupyter
    container_name: trading-jupyter
    ports:
      - "8888:8888"
    volumes:
      - ./data:/app/data
      - ./models:/app/models
      - ./notebooks:/app/notebooks
      - ./python:/app/python
    environment:
      - JUPYTER_ENABLE_LAB=yes
      - JUPYTER_TOKEN=trading123
    networks:
      - trading-network
    restart: unless-stopped
    profiles:
      - development

networks:
  trading-network:
    driver: bridge

volumes:
  trading-data:
  trading-models:
  trading-logs:

---

# Dockerfile.java - Java Spring Boot application
FROM openjdk:21-jdk-slim

WORKDIR /app

# Install Python for ML integration
RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    python3-venv \
    && rm -rf /var/lib/apt/lists/*

# Copy Java application
COPY target/trading-bot-*.jar app.jar

# Copy Python ML scripts
COPY python/ /app/python/
RUN cd /app/python && pip3 install -r requirements.txt

# Create directories
RUN mkdir -p /app/data/feather /app/data/ml /app/models /app/logs

# Set environment variables
ENV JAVA_OPTS="-Xmx2g -Xms1g"
ENV ML_PYTHON_EXECUTABLE=python3
ENV ML_PYTHON_SCRIPT_PATH=/app/python

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run application
CMD ["java", "-jar", "/app/app.jar"]

---

# Dockerfile.jupyter - Optional Jupyter notebook
FROM python:3.11-slim

WORKDIR /app

# Install Jupyter and ML packages
RUN pip install --no-cache-dir \
    jupyter \
    jupyterlab \
    notebook \
    tensorflow>=2.12.0 \
    scikit-learn>=1.3.0 \
    xgboost>=1.7.0 \
    lightgbm>=3.3.5 \
    pandas>=2.0.0 \
    numpy>=1.24.0 \
    matplotlib>=3.7.0 \
    seaborn>=0.12.0 \
    plotly>=5.14.0 \
    pyarrow>=12.0.0

# Create notebooks directory
RUN mkdir -p /app/notebooks

# Copy sample notebooks
COPY notebooks/ /app/notebooks/

# Expose Jupyter port
EXPOSE 8888

# Start Jupyter
CMD ["jupyter", "lab", "--ip=0.0.0.0", "--port=8888", "--no-browser", "--allow-root", "--notebook-dir=/app"]

---

# .dockerignore
.git
.gitignore
README.md
.env
.venv
__pycache__
*.pyc
*.pyo
*.pyd
.Python
env
pip-log.txt
pip-delete-this-directory.txt
.tox
.coverage
.coverage.*
.cache
nosetests.xml
coverage.xml
*.cover
*.log
.pytest_cache
.mypy_cache

# Java specific
target/
!.mvn/wrapper/maven-wrapper.jar
!**/src/main/**/target/
!**/src/test/**/target/

# IDE
.idea
.vscode
*.swp
*.swo

# OS
.DS_Store
Thumbs.db

---

# docker-compose.override.yml - Development overrides
version: '3.8'

services:
  trading-bot:
    volumes:
      - ./src:/app/src  # Live code reload for development
    environment:
      - SPRING_PROFILES_ACTIVE=development
      - LOGGING_LEVEL_ROOT=DEBUG
    ports:
      - "5005:5005"  # Debug port
    command: >
      java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
      -jar /app/app.jar

  ml-service:
    volumes:
      - ./python:/app:rw  # Live code reload
    environment:
      - PYTHONDONTWRITEBYTECODE=1
      - PYTHONUNBUFFERED=1

---

# Makefile - Build and run shortcuts
.PHONY: help build run stop clean test logs

help: ## Show this help message
	@echo 'Usage: make [target]'
	@echo ''
	@echo 'Targets:'
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  %-15s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

build: ## Build all Docker images
	@echo "Building Docker images..."
	docker-compose build

run: ## Start all services
	@echo "Starting trading bot services..."
	docker-compose up -d

run-dev: ## Start services in development mode
	@echo "Starting in development mode..."
	docker-compose -f docker-compose.yml -f docker-compose.override.yml up -d

stop: ## Stop all services
	@echo "Stopping services..."
	docker-compose down

clean: ## Clean up containers and images
	@echo "Cleaning up..."
	docker-compose down -v --remove-orphans
	docker system prune -f

test: ## Run tests
	@echo "Running tests..."
	docker-compose exec ml-service python3 -m pytest tests/ -v

logs: ## Show logs
	docker-compose logs -f

logs-java: ## Show Java application logs
	docker-compose logs -f trading-bot

logs-ml: ## Show ML service logs
	docker-compose logs -f ml-service

shell-java: ## Shell into Java container
	docker-compose exec trading-bot bash

shell-ml: ## Shell into ML container
	docker-compose exec ml-service bash

train: ## Train ML model
	docker-compose exec ml-service python3 main.py train --data-path /app/data/ml

jupyter: ## Start Jupyter notebook
	docker-compose --profile development up jupyter -d
	@echo "Jupyter available at: http://localhost:8888"
	@echo "Token: trading123"

# Data management
backup-models: ## Backup trained models
	@echo "Backing up models..."
	tar -czf models-backup-$(shell date +%Y%m%d-%H%M%S).tar.gz models/

restore-models: ## Restore models from backup (specify BACKUP_FILE)
	@if [ -z "$(BACKUP_FILE)" ]; then echo "Please specify BACKUP_FILE=filename"; exit 1; fi
	tar -xzf $(BACKUP_FILE)

# Development helpers
mvn-package: ## Package Java application
	./mvnw clean package -DskipTests

install-python-deps: ## Install Python dependencies locally
	pip install -r python/requirements.txt

setup-dev: ## Setup development environment
	@echo "Setting up development environment..."
	make install-python-deps
	mkdir -p data/feather data/ml models logs
	@echo "Development environment ready!"