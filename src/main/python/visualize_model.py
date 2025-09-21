#!/usr/bin/env python3

from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import Dense
from tensorflow.keras.utils import plot_model
from pathlib import Path
import os

# Mentési hely
output_dir = Path("src/main/resources/data/pictures")
output_dir.mkdir(parents=True, exist_ok=True)
output_file = output_dir / "model_plot.png"

# Dummy modell (példa)
model = Sequential()
model.add(Dense(64, input_dim=20, activation='relu'))
model.add(Dense(32, activation='relu'))
model.add(Dense(1, activation='sigmoid'))

# Modell vizualizáció mentése
plot_model(model, to_file=str(output_file), show_shapes=True, show_layer_names=True)

print(f"Model diagram saved to {output_file}")

# Windows alatt automatikus megnyitás
if os.name == "nt":
    os.startfile(output_file)
