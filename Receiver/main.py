import json
import time
import uuid
import socket
from dataclasses import dataclass
from typing import List

import numpy as np
import paho.mqtt.client as mqtt

from PySide6.QtCore import Qt, QObject, Signal, QTimer
from PySide6.QtWidgets import (
    QApplication, QWidget, QLabel, QLineEdit, QPushButton, QTextEdit,
    QVBoxLayout, QHBoxLayout, QGroupBox, QFormLayout, QSplitter
)

import pyqtgraph as pg


@dataclass
class RawPacket:
    deviceId: str
    seq: int
    rateHz: int
    t0: int
    samples: list


class UiBus(QObject):
    log = Signal(str)
    status = Signal(str)
    raw = Signal(dict)
    activity = Signal(dict)


def resolve_ipv4(host: str) -> str:
    try:
        infos = socket.getaddrinfo(host, None, socket.AF_INET, socket.SOCK_STREAM)
        if infos:
            return infos[0][4][0]
    except Exception:
        pass
    return host


class MqttBridge:
    def __init__(self, bus: UiBus):
        self.bus = bus
        self.prefix = "imu/phone1"
        self._loop_running = False
        self._build_client()

    def _build_client(self):
        cid = f"imu-monitor-{uuid.uuid4().hex[:10]}"

        self.client = mqtt.Client(
            client_id=cid,
            protocol=mqtt.MQTTv311,
            clean_session=True,
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            transport="tcp",
        )

        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message
        self.client.on_connect_fail = self._on_connect_fail
        self.client.on_log = self._on_log

        self.client.reconnect_delay_set(min_delay=1, max_delay=10)

    def _rc_value(self, rc):
        return getattr(rc, "value", rc)

    def connect(self, host: str, port: int, username: str, password: str, prefix: str):
        self.prefix = (prefix or "").strip() or "imu/phone1"

        try:
            self.disconnect()
        except Exception:
            pass

        self._build_client()

        try:
            self.client.username_pw_set(None)
            if (username or "").strip():
                self.client.username_pw_set(username.strip(), password or None)

            ip = resolve_ipv4(host.strip())
            self.bus.status.emit("Connecting...")
            self.bus.log.emit(f"[mqtt] connect {host}:{port} (ipv4={ip}) prefix={self.prefix}")

            self.client.connect_async(ip, int(port), keepalive=30)
            self.client.loop_start()
            self._loop_running = True

        except Exception as e:
            self.bus.log.emit(f"[err] connect failed: {type(e).__name__}: {e}")
            self.bus.status.emit("Disconnected")

    def disconnect(self):
        self.bus.log.emit("[mqtt] disconnect")
        try:
            self.client.disconnect()
        except Exception:
            pass
        try:
            if self._loop_running:
                self.client.loop_stop()
        except Exception:
            pass
        self._loop_running = False

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        rc = self._rc_value(reason_code)
        self.bus.status.emit("Connected" if rc == 0 else f"Connected(rc={rc})")

        topic_all = f"{self.prefix}/#"
        client.subscribe(topic_all, qos=0)
        self.bus.log.emit(f"[mqtt] subscribed: {topic_all}")

    def _on_connect_fail(self, client, userdata):
        self.bus.status.emit("Disconnected")
        self.bus.log.emit("[mqtt] connect failed")

    def _on_disconnect(self, client, userdata, disconnect_flags, reason_code, properties):
        rc = self._rc_value(reason_code)
        self.bus.status.emit("Disconnected")
        self.bus.log.emit(f"[mqtt] disconnected rc={rc}")

    def _on_log(self, client, userdata, level, buf):
        s = str(buf)
        if ("failed" in s.lower()) or ("error" in s.lower()) or ("timed out" in s.lower()) or ("refused" in s.lower()):
            self.bus.log.emit(f"[mqtt-log] {s}")

    def _on_message(self, client, userdata, msg):
        try:
            payload = msg.payload.decode("utf-8", errors="ignore").strip()
            if not payload:
                return

            obj = json.loads(payload)

            
            if isinstance(obj, dict) and ("ax" in obj) and ("gx" in obj) and ("mx" in obj):
                self.bus.raw.emit(obj)
                self.bus.activity.emit(obj)
                return

            if msg.topic.endswith("/raw"):
                self.bus.raw.emit(obj)
                return
            if msg.topic.endswith("/activity"):
                self.bus.activity.emit(obj)
                return

            if isinstance(obj, dict) and "samples" in obj:
                self.bus.raw.emit(obj)
            elif isinstance(obj, dict) and ("label" in obj or "confidence" in obj or "conf" in obj):
                self.bus.activity.emit(obj)

        except Exception:
            pass


class Chart3(pg.PlotWidget):
    def __init__(self, title: str, y_label: str):
        super().__init__()

        self.setBackground((20, 20, 24))
        self.showGrid(x=True, y=True, alpha=0.25)

        self.getAxis("left").setTextPen(pg.mkPen("w"))
        self.getAxis("bottom").setTextPen(pg.mkPen("w"))
        self.getAxis("left").setPen(pg.mkPen((180, 180, 190)))
        self.getAxis("bottom").setPen(pg.mkPen((180, 180, 190)))

        self.setTitle(title, color="w", size="12pt")
        self.setLabel("left", y_label, **{"color": "#CFCFD6", "size": "10pt"})
        self.setLabel("bottom", "samples", **{"color": "#CFCFD6", "size": "10pt"})

        self.setClipToView(True)
        self.enableAutoRange(axis="y", enable=True)

        self.max_points = 600
        self.x = np.arange(self.max_points, dtype=np.float32)
        self.yx = np.zeros(self.max_points, dtype=np.float32)
        self.yy = np.zeros(self.max_points, dtype=np.float32)
        self.yz = np.zeros(self.max_points, dtype=np.float32)

        pen_x = pg.mkPen((255, 82, 82), width=2)
        pen_y = pg.mkPen((105, 240, 174), width=2)
        pen_z = pg.mkPen((68, 138, 255), width=2)

        self.curve_x = self.plot(self.x, self.yx, pen=pen_x)
        self.curve_y = self.plot(self.x, self.yy, pen=pen_y)
        self.curve_z = self.plot(self.x, self.yz, pen=pen_z)

        self.legend = pg.LegendItem((90, 60), offset=(0, 0))
        self.legend.setParentItem(self.getPlotItem().graphicsItem())
        self.legend.addItem(self.curve_x, "X")
        self.legend.addItem(self.curve_y, "Y")
        self.legend.addItem(self.curve_z, "Z")
        self.legend.anchor((1, 0), (1, 0), offset=(-10, 10))

    def push(self, xs: List[float], ys: List[float], zs: List[float]):
        n = len(xs)
        if n == 0:
            return

        xs = np.asarray(xs, dtype=np.float32)
        ys = np.asarray(ys, dtype=np.float32)
        zs = np.asarray(zs, dtype=np.float32)

        if n >= self.max_points:
            self.yx[:] = xs[-self.max_points:]
            self.yy[:] = ys[-self.max_points:]
            self.yz[:] = zs[-self.max_points:]
        else:
            self.yx[:-n] = self.yx[n:]
            self.yy[:-n] = self.yy[n:]
            self.yz[:-n] = self.yz[n:]
            self.yx[-n:] = xs
            self.yy[-n:] = ys
            self.yz[-n:] = zs

        self.curve_x.setData(self.x, self.yx)
        self.curve_y.setData(self.x, self.yy)
        self.curve_z.setData(self.x, self.yz)
        self.enableAutoRange(axis="y", enable=True)


class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("IMU Stream Monitor (MQTT)")
        self.resize(1400, 820)

        pg.setConfigOptions(antialias=True, useOpenGL=False)

        self.bus = UiBus()
        self.mqtt = MqttBridge(self.bus)

        self.rx_raw = 0
        self.rx_act = 0
        self._status_text = "Disconnected"

        self.edHost = QLineEdit("test.mosquitto.org")
        self.edPort = QLineEdit("1883")
        self.edUser = QLineEdit("")
        self.edPass = QLineEdit("")
        self.edPass.setEchoMode(QLineEdit.EchoMode.Password)
        self.edPrefix = QLineEdit("imu/phone1")

        self.btnConnect = QPushButton("Connect")
        self.btnDisconnect = QPushButton("Disconnect")
        self.btnDisconnect.setEnabled(False)

        self.lblStatus = QLabel("Disconnected")
        self.lblStatus.setStyleSheet("color:#CFCFD6;")

        self.lblActivity = QLabel("unknown")
        self.lblActivity.setStyleSheet("color:white; font-size:22px; font-weight:700;")
        self.lblConf = QLabel("conf: 0.00")
        self.lblConf.setStyleSheet("color:#9A9AA3;")

        self.txtLog = QTextEdit()
        self.txtLog.setReadOnly(True)

        self.chartAcc = Chart3("Accelerometer", "m/s^2")
        self.chartGyro = Chart3("Gyroscope", "rad/s")
        self.chartMag = Chart3("Magnetometer", "uT")

        left = self._build_left_panel()
        right = self._build_right_panel()

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.addWidget(left)
        splitter.addWidget(right)
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)
        splitter.setSizes([420, 980])

        root = QHBoxLayout()
        root.addWidget(splitter)
        self.setLayout(root)

        self.setStyleSheet("""
            QWidget { background:#0E0E10; color:white; font-family:Segoe UI; }
            QGroupBox { border:1px solid #22222A; border-radius:14px; margin-top:10px; }
            QGroupBox::title { subcontrol-origin: margin; left:12px; padding:0 6px; color:#CFCFD6; }
            QLineEdit { background:#15151A; border:1px solid #2A2A33; border-radius:10px; padding:8px; }
            QTextEdit { background:#15151A; border:1px solid #2A2A33; border-radius:10px; padding:8px; }
            QPushButton { background:#BDA8FF; color:#000; border:none; border-radius:12px; padding:10px; font-weight:600; }
            QPushButton:disabled { background:#2A2A33; color:#777; }
        """)

        self.btnConnect.clicked.connect(self.on_connect_clicked)
        self.btnDisconnect.clicked.connect(self.on_disconnect_clicked)

        self.bus.log.connect(self.log)
        self.bus.status.connect(self.set_status)
        self.bus.raw.connect(self.on_raw)
        self.bus.activity.connect(self.on_activity)

        self._acc_buf = [[], [], []]
        self._gyro_buf = [[], [], []]
        self._mag_buf = [[], [], []]

        self._chart_timer = QTimer(self)
        self._chart_timer.setInterval(33)
        self._chart_timer.timeout.connect(self._flush_charts)
        self._chart_timer.start()

        self._status_timer = QTimer(self)
        self._status_timer.setInterval(300)
        self._status_timer.timeout.connect(self._refresh_status_line)
        self._status_timer.start()

        self._watchdog = QTimer(self)
        self._watchdog.setSingleShot(True)
        self._watchdog.timeout.connect(self._connect_timeout)

    def _build_left_panel(self) -> QWidget:
        w = QWidget()
        v = QVBoxLayout()

        gbConn = QGroupBox("Connection")
        form = QFormLayout()
        form.addRow("Host", self.edHost)
        form.addRow("Port", self.edPort)
        form.addRow("Username", self.edUser)
        form.addRow("Password", self.edPass)
        form.addRow("Topic prefix", self.edPrefix)
        gbConn.setLayout(form)

        rowBtns = QHBoxLayout()
        rowBtns.addWidget(self.btnConnect)
        rowBtns.addWidget(self.btnDisconnect)

        v.addWidget(gbConn)
        v.addLayout(rowBtns)
        v.addWidget(self.lblStatus)

        gbAct = QGroupBox("Live activity")
        actV = QVBoxLayout()
        actV.addWidget(self.lblActivity)
        actV.addWidget(self.lblConf)
        gbAct.setLayout(actV)
        v.addWidget(gbAct)

        gbLog = QGroupBox("Log")
        logV = QVBoxLayout()
        logV.addWidget(self.txtLog)
        gbLog.setLayout(logV)
        v.addWidget(gbLog)

        v.setStretch(3, 1)
        w.setLayout(v)
        return w

    def _build_right_panel(self) -> QWidget:
        w = QWidget()
        v = QVBoxLayout()

        gb = QGroupBox("Live charts")
        chartsV = QVBoxLayout()
        chartsV.addWidget(self.chartAcc, 1)
        chartsV.addWidget(self.chartGyro, 1)
        chartsV.addWidget(self.chartMag, 1)
        gb.setLayout(chartsV)

        v.addWidget(gb)
        w.setLayout(v)
        return w

    def log(self, text: str):
        ts = time.strftime("%H:%M:%S")
        self.txtLog.append(f"{ts}  {text}")

    def set_status(self, s: str):
        self._status_text = s
        self._refresh_status_line()

    def _refresh_status_line(self):
        self.lblStatus.setText(f"{self._status_text} | raw:{self.rx_raw} act:{self.rx_act}")

    def _connect_timeout(self):
        if self._status_text.startswith("Connecting"):
            self.set_status("Disconnected")
            self.log("[ui] connect timeout")

    def on_connect_clicked(self):
        host = self.edHost.text().strip()
        port = int(self.edPort.text().strip() or "1883")
        user = self.edUser.text()
        pw = self.edPass.text()
        prefix = self.edPrefix.text().strip() or "imu/phone1"

        self.btnConnect.setEnabled(False)
        self.btnDisconnect.setEnabled(True)

        self.rx_raw = 0
        self.rx_act = 0
        self.set_status("Connecting...")

        self.mqtt.connect(host, port, user, pw, prefix)
        self._watchdog.start(5000)

    def on_disconnect_clicked(self):
        self.mqtt.disconnect()
        self.btnConnect.setEnabled(True)
        self.btnDisconnect.setEnabled(False)
        self.set_status("Disconnected")

    def on_activity(self, obj: dict):
        self.rx_act += 1

        label = obj.get("label", "unknown")
        conf = obj.get("confidence", obj.get("conf", 0.0))  

        self.lblActivity.setText(str(label))
        try:
            self.lblConf.setText(f"conf: {float(conf):.2f}")
        except Exception:
            self.lblConf.setText("conf: 0.00")

    def on_raw(self, obj: dict):
        self.rx_raw += 1

        samples = obj.get("samples", None)
        if isinstance(samples, list) and samples:
            ax, ay, az = [], [], []
            gx, gy, gz = [], [], []
            mx, my, mz = [], [], []

            for s in samples:
                a = s.get("acc", [0, 0, 0])
                g = s.get("gyro", [0, 0, 0])
                m = s.get("mag", [0, 0, 0])

                if len(a) < 3: a = [0, 0, 0]
                if len(g) < 3: g = [0, 0, 0]
                if len(m) < 3: m = [0, 0, 0]

                ax.append(float(a[0])); ay.append(float(a[1])); az.append(float(a[2]))
                gx.append(float(g[0])); gy.append(float(g[1])); gz.append(float(g[2]))
                mx.append(float(m[0])); my.append(float(m[1])); mz.append(float(m[2]))

            self._acc_buf[0].extend(ax); self._acc_buf[1].extend(ay); self._acc_buf[2].extend(az)
            self._gyro_buf[0].extend(gx); self._gyro_buf[1].extend(gy); self._gyro_buf[2].extend(gz)
            self._mag_buf[0].extend(mx); self._mag_buf[1].extend(my); self._mag_buf[2].extend(mz)
            return

        if "ax" in obj and "ay" in obj and "az" in obj:
            ax = float(obj.get("ax", 0.0))
            ay = float(obj.get("ay", 0.0))
            az = float(obj.get("az", 0.0))

            gx = float(obj.get("gx", 0.0))
            gy = float(obj.get("gy", 0.0))
            gz = float(obj.get("gz", 0.0))

            mx = float(obj.get("mx", 0.0))
            my = float(obj.get("my", 0.0))
            mz = float(obj.get("mz", 0.0))

            self._acc_buf[0].append(ax); self._acc_buf[1].append(ay); self._acc_buf[2].append(az)
            self._gyro_buf[0].append(gx); self._gyro_buf[1].append(gy); self._gyro_buf[2].append(gz)
            self._mag_buf[0].append(mx); self._mag_buf[1].append(my); self._mag_buf[2].append(mz)

    def _flush_charts(self):
        if self._acc_buf[0]:
            self.chartAcc.push(self._acc_buf[0], self._acc_buf[1], self._acc_buf[2])
            self._acc_buf = [[], [], []]
        if self._gyro_buf[0]:
            self.chartGyro.push(self._gyro_buf[0], self._gyro_buf[1], self._gyro_buf[2])
            self._gyro_buf = [[], [], []]
        if self._mag_buf[0]:
            self.chartMag.push(self._mag_buf[0], self._mag_buf[1], self._mag_buf[2])
            self._mag_buf = [[], [], []]


if __name__ == "__main__":
    app = QApplication([])
    w = MainWindow()
    w.show()
    app.exec()
