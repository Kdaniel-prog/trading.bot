# setup.py
from setuptools import setup, find_packages

setup(
    name="trading-ml",
    version="1.0.0",
    description="Deep Learning framework for crypto trading",
    packages=find_packages(),
    install_requires=[
        "tensorflow>=2.12.0",
        "scikit-learn>=1.3.0",
        "xgboost>=1.7.0",
        "pandas>=2.0.0",
        "numpy>=1.24.0",
        "pyarrow>=12.0.0"
    ],
    python_requires=">=3.8",
    entry_points={
        'console_scripts': [
            'train-model=train_model:main',
            'predict=predict:main',
            'evaluate-model=evaluate_model:main_evaluate',
            'model-info=evaluate_model:main_info'
        ],
    }
)