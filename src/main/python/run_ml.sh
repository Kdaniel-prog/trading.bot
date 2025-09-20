#!/bin/bash
# run_ml.sh - Helper script for running ML operations

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default paths
DATA_PATH="${ML_DATA_PATH:-/app/data/ml}"
MODEL_PATH="${ML_MODEL_PATH:-/app/models}"
PYTHON_PATH="${ML_PYTHON_PATH:-python3}"

print_usage() {
    echo -e "${BLUE}Trading ML Framework${NC}"
    echo ""
    echo "Usage: $0 [command] [options]"
    echo ""
    echo "Commands:"
    echo "  train       Train new ML model"
    echo "  predict     Make prediction"
    echo "  evaluate    Evaluate model performance"
    echo "  info        Get model information"
    echo "  setup       Setup Python environment"
    echo "  test        Run tests"
    echo ""
    echo "Examples:"
    echo "  $0 train --model-name swing_trader_v2"
    echo "  $0 predict --features features.json --model swing_trader"
    echo "  $0 evaluate --model swing_trader"
    echo "  $0 info --model swing_trader"
}

setup_environment() {
    echo -e "${YELLOW}Setting up Python environment...${NC}"

    # Install requirements
    if [ -f "requirements.txt" ]; then
        pip install -r requirements.txt
        echo -e "${GREEN}✓ Python requirements installed${NC}"
    fi

    # Create directories
    mkdir -p "$DATA_PATH" "$MODEL_PATH"
    echo -e "${GREEN}✓ Directories created${NC}"

    # Check TensorFlow GPU
    python3 -c "import tensorflow as tf; print('GPU Available:', tf.config.list_physical_devices('GPU'))" 2>/dev/null || true
}

train_model() {
    local model_name="swing_trader"
    local data_path="$DATA_PATH"

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --model-name|--model)
                model_name="$2"
                shift 2
                ;;
            --data-path|--data)
                data_path="$2"
                shift 2
                ;;
            *)
                echo -e "${RED}Unknown option: $1${NC}"
                return 1
                ;;
        esac
    done

    echo -e "${BLUE}Training model: $model_name${NC}"
    echo -e "${BLUE}Data path: $data_path${NC}"

    if [ ! -d "$data_path" ]; then
        echo -e "${RED}Error: Data path does not exist: $data_path${NC}"
        return 1
    fi

    # Check for training data files
    training_files=$(find "$data_path" -name "training_data_*.json" | wc -l)
    if [ "$training_files" -eq 0 ]; then
        echo -e "${RED}Error: No training data files found in $data_path${NC}"
        echo -e "${YELLOW}Expected files: training_data_*.json${NC}"
        return 1
    fi

    echo -e "${GREEN}Found $training_files training data files${NC}"

    # Run training
    $PYTHON_PATH train_model.py \
        --model-name "$model_name" \
        --data-path "$data_path" \
        --model-path "$MODEL_PATH"
}

make_prediction() {
    local model_name="swing_trader"
    local features_file=""

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --model-name|--model)
                model_name="$2"
                shift 2
                ;;
            --features-file|--features)
                features_file="$2"
                shift 2
                ;;
            *)
                echo -e "${RED}Unknown option: $1${NC}"
                return 1
                ;;
        esac
    done

    if [ -z "$features_file" ]; then
        echo -e "${RED}Error: Features file required${NC}"
        echo "Usage: $0 predict --features features.json [--model model_name]"
        return 1
    fi

    if [ ! -f "$features_file" ]; then
        echo -e "${RED}Error: Features file not found: $features_file${NC}"
        return 1
    fi

    echo -e "${BLUE}Making prediction with model: $model_name${NC}"

    $PYTHON_PATH predict.py \
        --features-file "$features_file" \
        --model-name "$model_name" \
        --model-path "$MODEL_PATH"
}

evaluate_model() {
    local model_name="swing_trader"
    local test_data_path="$DATA_PATH"

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --model-name|--model)
                model_name="$2"
                shift 2
                ;;
            --test-data-path|--test-data)
                test_data_path="$2"
                shift 2
                ;;
            *)
                echo -e "${RED}Unknown option: $1${NC}"
                return 1
                ;;
        esac
    done

    echo -e "${BLUE}Evaluating model: $model_name${NC}"

    $PYTHON_PATH evaluate_model.py \
        --model-name "$model_name" \
        --model-path "$MODEL_PATH" \
        --test-data-path "$test_data_path"
}

get_model_info() {
    local model_name="swing_trader"

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --model-name|--model)
                model_name="$2"
                shift 2
                ;;
            *)
                echo -e "${RED}Unknown option: $1${NC}"
                return 1
                ;;
        esac
    done

    echo -e "${BLUE}Getting info for model: $model_name${NC}"

    # Check if model exists
    info_file="$MODEL_PATH/${model_name}_info.json"
    if [ ! -f "$info_file" ]; then
        echo -e "${RED}Error: Model not found: $model_name${NC}"
        echo -e "${YELLOW}Available models:${NC}"
        find "$MODEL_PATH" -name "*_info.json" -exec basename {} _info.json \; 2>/dev/null || echo "No models found"
        return 1
    fi

    # Show basic info
    echo -e "${GREEN}Model file: $info_file${NC}"

    # Run model info script
    python3 -c "
import