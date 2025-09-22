#!/usr/bin/env python3
"""
Debug script: export and inspect features + labels from your training JSONs.

How to use:
 1) Save this file into your repo root (e.g. as debug_features_and_labels.py).
 2) Run (from project root):
      python debug_features_and_labels.py --pattern training_data_*.json

Outputs:
 - debug_features_labels.csv  (feature vectors + label)
 - pca_debug.png             (2D PCA scatter of a sample)
 - debug_summary.txt         (text summary with stats printed below)

What it does:
 - loads JSON files from src/main/resources/data/training (and fallback dirs)
 - extracts features with the same logic as your training script
 - computes per-feature global and per-class stats (min/max/mean/std/unique)
 - lists constant features and features with very low variance
 - runs ANOVA (f_classif) to rank features by between-class separability
 - creates a 2D PCA scatter (sampled) to visually check class separation
 - trains a quick LogisticRegression baseline (with/without class_weight)

This is intentionally verbose so you can quickly see whether features carry signal.
"""

import argparse
import json
import math
import sys
from pathlib import Path
import glob
import numpy as np
import pandas as pd
from sklearn.preprocessing import StandardScaler
from sklearn.decomposition import PCA
from sklearn.feature_selection import f_classif
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
import matplotlib.pyplot as plt


def extract_feature_vector(tech_indicators, sample):
    features = []
    current_price = sample.get('currentPrice', tech_indicators.get('currentPrice', 1.0))
    if current_price is None or current_price <= 0:
        current_price = 1.0

    features.extend([
        1.0 if tech_indicators.get('primaryTrend') == 'BULLISH' else (
            -1.0 if tech_indicators.get('primaryTrend') == 'BEARISH' else 0.0),
        1.0 if tech_indicators.get('shortTermTrend') == 'BULLISH' else (
            -1.0 if tech_indicators.get('shortTermTrend') == 'BEARISH' else 0.0),
        1.0 if tech_indicators.get('trendAlignment', False) else 0.0,
        float(tech_indicators.get('trendStrength', 0.0)),
        float(tech_indicators.get('ema20_4h', current_price)) / current_price,
        float(tech_indicators.get('ema50_4h', current_price)) / current_price
    ])

    rsi = float(tech_indicators.get('rsi', 50.0))
    features.extend([
        rsi / 100.0,
        1.0 if tech_indicators.get('macdBullish', False) else 0.0,
        1.0 if tech_indicators.get('macdBearish', False) else 0.0,
        1.0 if tech_indicators.get('rsiBullishZone', False) else 0.0,
        1.0 if tech_indicators.get('rsiBearishZone', False) else 0.0,
        1.0 if tech_indicators.get('rsiRising', False) else 0.0
    ])

    volume_ratio = float(tech_indicators.get('volumeRatio', 1.0))
    features.extend([
        volume_ratio,
        1.0 if tech_indicators.get('strongVolume', False) else 0.0,
        min(volume_ratio / 5.0, 1.0),
        1.0 if tech_indicators.get('volumeBreakout', False) else 0.0,
        1.0 if tech_indicators.get('volumeTrendUp', False) else 0.0
    ])

    risk_reward = float(tech_indicators.get('riskRewardRatio', 0.0))
    features.extend([
        min(risk_reward / 10.0, 1.0),
        float(tech_indicators.get('volatilityPercent', 0.0)) / 100.0,
        float(tech_indicators.get('distanceFromSupport', 0.0)) / 100.0,
        float(tech_indicators.get('distanceFromResistance', 0.0)) / 100.0
    ])

    features.extend([
        1.0 if tech_indicators.get('higherHighs', False) else 0.0,
        1.0 if tech_indicators.get('lowerLows', False) else 0.0,
        1.0 if tech_indicators.get('bullishStructure', False) else 0.0,
        1.0 if tech_indicators.get('bearishStructure', False) else 0.0
    ])

    while len(features) < 25:
        features.append(0.0)
    features = features[:25]
    features = [float(f) if not (np.isnan(f) or np.isinf(f)) else 0.0 for f in features]
    return np.array(features, dtype=np.float32)


def prepare_from_files(file_paths, max_samples=None):
    features = []
    labels = []
    count = 0

    for fp in file_paths:
        try:
            with open(fp, 'r', encoding='utf-8-sig') as f:
                data = json.load(f)
        except Exception as e:
            print(f"Error loading {fp}: {e}")
            continue

        # Handle different JSON structures
        if isinstance(data, dict):
            if 'samples' in data:
                samples = data['samples']
            elif 'training_data' in data:
                samples = data['training_data']
            else:
                # Try to find the first list-valued field
                lists = [v for v in data.values() if isinstance(v, list)]
                samples = lists[0] if lists else []
        elif isinstance(data, list):
            samples = data
        else:
            print(f"Skipping (unknown structure): {fp}")
            continue

        for sample in samples:
            if max_samples and count >= max_samples:
                return np.vstack(features) if features else np.empty((0, 25)), np.array(labels)

            # Complex format: check for swingalgo_features or technicalIndicators
            if 'swingalgo_features' in sample or 'technicalIndicators' in sample or 'actual_outcome' in sample:
                tech = sample.get('swingalgo_features', sample.get('technicalIndicators', sample))
                fv = extract_feature_vector(tech, sample)
                outcome = sample.get('actual_outcome', sample.get('actualOutcome', 'NO_TRADE'))
                if outcome == 'NO_TRADE':
                    continue
                features.append(fv)
                labels.append(outcome)
                count += 1

            # Fallback: simple format
            elif 'direction' in sample or 'pnl_percent' in sample:
                try:
                    pnl_percent = float(sample.get('pnl_percent', 0.0))
                    direction = sample.get('direction', 'long').lower()
                    score = float(sample.get('score', 0.5))
                    entry_price = float(sample.get('entry_price', 1.0))
                    exit_price = float(sample.get('exit_price', entry_price))
                    price_change = (exit_price - entry_price) / entry_price if entry_price > 0 else 0.0

                    fv = [
                        1.0 if direction == 'long' else -1.0,
                        score,
                        abs(pnl_percent) / 10.0,
                        1.0 if pnl_percent > 0 else 0.0,
                        price_change,
                        1.0 if abs(pnl_percent) > 2.0 else 0.0,
                        min(abs(score - 0.5) * 2, 1.0),
                        1.0 if pnl_percent > 5.0 else 0.0,
                        1.0 if pnl_percent < -5.0 else 0.0,
                        np.random.normal(0.0, 0.1)
                    ]
                    while len(fv) < 25:
                        fv.append(0.0)
                    features.append(np.array(fv, dtype=np.float32))

                    if sample.get('successful', pnl_percent > 0):
                        label = 'PROFIT'
                    elif pnl_percent < -2.0:
                        label = 'LOSS'
                    else:
                        label = 'NEUTRAL'

                    labels.append(label)
                    count += 1
                except Exception:
                    continue

    if not features:
        return np.empty((0, 25)), np.array([])

    return np.vstack(features), np.array(labels)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pattern', default='training_data_*.json', help='glob pattern (in training dir)')
    parser.add_argument('--training-dir', default='src/main/resources/data/training', help='folder with JSONs')
    parser.add_argument('--max-samples', type=int, default=None, help='stop after N samples (for speed)')
    parser.add_argument('--sample-pca', type=int, default=2000, help='how many points to sample for PCA scatter')
    args = parser.parse_args()

    tdir = Path(args.training_dir)
    if not tdir.exists():
        # fallback search
        candidates = [Path('src/data/training'), Path('data/training'), Path('.')]
        found = False
        for c in candidates:
            p = c
            if p.exists():
                tdir = p
                found = True
                break
        if not found:
            print(f"Training dir not found: {args.training_dir}")
            sys.exit(1)

    files = sorted(tdir.glob(args.pattern))
    if not files:
        print(f"No files matching {args.pattern} in {tdir}")
        sys.exit(1)

    print(f"Found {len(files)} files, loading...")
    X, y = prepare_from_files(files, max_samples=args.max_samples)
    print(f"Prepared features: {X.shape}, labels: {y.shape}")

    if X.size == 0:
        print("No samples found after parsing. Check format and 'actualOutcome' values.")
        sys.exit(1)

    n_features = X.shape[1]
    columns = [f'f{i}' for i in range(n_features)]
    df = pd.DataFrame(X, columns=columns)
    df['label'] = y

    out_csv = Path('debug_features_labels.csv')
    df.to_csv(out_csv, index=False)
    print(f"Saved CSV -> {out_csv.resolve()}")

    # Summary stats
    with open('debug_summary.txt', 'w') as s:
        s.write(f"Total samples: {len(df)}\n")
        s.write("Label counts:\n")
        s.write(df['label'].value_counts().to_string())
        s.write('\n\n')

        stats = df[columns].agg(['min','max','mean','std','nunique']).T
        s.write("Per-feature stats (min,max,mean,std,nunique):\n")
        s.write(stats.to_string())
        s.write('\n\n')

        const_feats = stats[stats['nunique'] <= 1].index.tolist()
        s.write(f"Constant features (nunique<=1): {const_feats}\n")

        low_var = stats[stats['std'] < 1e-6].index.tolist()
        s.write(f"Very low variance features (std<1e-6): {low_var}\n\n")

    print("Wrote debug_summary.txt (see constant/low-variance features there)")

    # Print a handful of samples per class to stdout
    print('\n--- Sample rows per class (up to 8 each) ---')
    for cl in df['label'].unique():
        print(f'\nClass: {cl}  Count: {len(df[df.label==cl])}')
        print(df[df.label==cl].head(8).to_string(index=False))

    # ANOVA f_classif to rank features
    try:
        y_int, uniques = pd.factorize(df['label'])
        fvals, pvals = f_classif(df[columns].values, y_int)
        feats_rank = sorted(list(zip(columns, fvals, pvals)), key=lambda x: -x[1])
        print('\nTop 12 features by ANOVA F-value:')
        for name, fval, pval in feats_rank[:12]:
            print(f"{name}: F={fval:.3f}, p={pval:.3e}")
    except Exception as e:
        print(f"ANOVA failed: {e}")

    # PCA scatter (sampled)
    try:
        scaler = StandardScaler()
        Xs = scaler.fit_transform(df[columns].values)
        sample_n = min(args.sample_pca, len(df))
        idx = np.random.RandomState(42).choice(len(df), size=sample_n, replace=False)
        pca = PCA(n_components=2)
        pc = pca.fit_transform(Xs[idx])

        plt.figure(figsize=(8,6))
        uniq = np.unique(y_int[idx])
        for ui in uniq:
            mask = (y_int[idx] == ui)
            plt.scatter(pc[mask,0], pc[mask,1], s=6, label=uniques[ui])
        plt.legend(markerscale=3)
        plt.title('PCA 2D (sample)')
        plt.tight_layout()
        plt.savefig('pca_debug.png', dpi=150)
        plt.close()
        print('Saved PCA scatter -> pca_debug.png')
    except Exception as e:
        print(f'PCA scatter failed: {e}')

    # Quick logistic regression baseline (on a sample)
    try:
        max_train = 50000
        idx_train = None
        if len(df) > max_train:
            idx_train = np.random.RandomState(1).choice(len(df), size=max_train, replace=False)
            X_small = Xs[idx_train]
            y_small = y_int[idx_train]
        else:
            X_small = Xs
            y_small = y_int

        X_tr, X_te, y_tr, y_te = train_test_split(X_small, y_small, test_size=0.2, random_state=42, stratify=y_small)

        clf = LogisticRegression(max_iter=1000)
        clf.fit(X_tr, y_tr)
        acc = clf.score(X_te, y_te)

        clf2 = LogisticRegression(max_iter=1000, class_weight='balanced')
        clf2.fit(X_tr, y_tr)
        acc2 = clf2.score(X_te, y_te)

        print(f"\nLogistic baseline (no class_weight) acc: {acc:.4f}")
        print(f"Logistic baseline (class_weight='balanced') acc: {acc2:.4f}")

    except Exception as e:
        print(f"Logistic baseline failed: {e}")

    print('\nDone. If many features are constant or ANOVA F-values are tiny, your features carry little signal.')


if __name__ == '__main__':
    main()
