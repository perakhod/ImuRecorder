## Overview
**ImuRecorder** is a two-part project:

- **Android app (Kotlin)** — reads IMU sensors, records datasets, trains/uses an on-device ML model, and publishes data via MQTT.
- **PC app (Python + Qt)** — subscribes to MQTT topics, displays live charts, and shows predicted activity (`label + confidence`).

The project covers the full pipeline:
**data capture → dataset creation → model training → live prediction → PC monitoring**.

---

##  Features

### Android App
- Reads 3 IMU sensors:
  - Accelerometer (m/s²)
  - Gyroscope (rad/s)
  - Magnetometer (uT)
- Live real-time charts
- Dataset recording mode (Start / Stop)
- Saves recordings to CSV files
- Real-time activity recognition
- Streams to PC via MQTT:
  - raw IMU packets (for charts)
  - prediction results (`label + confidence`)

### PC App
- Connects to an MQTT broker (public or private)
- Receives data in real time
- 3 live charts (Accelerometer / Gyroscope / Magnetometer)
- Live activity panel: **label + confidence**
- Connection + message logs

---

## Machine Learning (final model)

Final classifier used in the project:

### **ExtraTreesClassifier (Extremely Randomized Trees)**

The model runs with a sliding window pipeline:
1. IMU signal is split into **sliding windows** (e.g., 128 samples, step 64)
2. **Statistical features** are extracted from each window (e.g., mean/std/min/max/energy)
3. The classifier outputs:
   - `label` — predicted activity (walking/standing/…)
   - `confidence` — prediction confidence

**Why ExtraTrees:**
- fast training and fast inference on the phone
- robust to IMU noise and device differences
- works great on tabular features
- no feature scaling required (unlike many SVM setups)

---

## Dataset structure

Datasets are stored by activity label:
datasets/
walking/
*.csv
standing/
*.csv
running/
*.csv
stairs_up/
*.csv
stairs_down/
*.csv



### CSV format (Android)
Each `*.csv` file is one recorded session.
Each row contains timestamp + 9 IMU channels:
ts, ax, ay, az, gx, gy, gz, mx, my, mz


---

## MQTT — how the connection works


ImuRecorder uses MQTT (publish/subscribe):


- The phone **publishes** data to the MQTT broker
- The PC **subscribes** to the same topics and receives live updates


Example topic prefix:
imu/phone1


Topics:
- `imu/phone1/raw` — raw IMU packets (batch)
- `imu/phone1/activity` — activity prediction `{label, confidence}`

### Why a public broker?
A public broker (e.g. `test.mosquitto.org`) makes setup easier:
- PC and phone do **not** need to be on the same Wi-Fi network
- quick testing without running your own server
- scalable architecture for future features (remote monitoring)

> Note: public brokers do not guarantee privacy — use a unique topic prefix.

---

## How to run

## 1) Android
**Requirements:**
- Android Studio
- Android phone with IMU sensors

**Steps:**
1. Open the project in Android Studio
2. Run the app on your device
3. Select an activity and record a dataset (Start/Stop)
4. Enable MQTT streaming and set:
   - host (e.g., `test.mosquitto.org`)
   - port `1883`
   - topic prefix (e.g., `imu/phone1`)

---

## 2) PC (Receiver)
**Requirements:**
- Python 3.10+ (tested on Python 3.12)
- Windows / Linux

**Install dependencies:**
```bash
pip install -r requirements.txt

Run the app:
python main.py

Example settings:

- Host: test.mosquitto.org
- Port: 1883
- Topic prefix: imu/phone1
