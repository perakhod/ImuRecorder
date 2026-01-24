package com.example.imurecorder2

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MqttManager {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(err: String)
    }

    private var listener: Listener? = null

    private var client: Mqtt3AsyncClient? = null
    private val connected = AtomicBoolean(false)

    fun setListener(l: Listener?) {
        listener = l
    }

    fun isConnected(): Boolean = connected.get()

    fun connect(
        host: String,
        port: Int,
        username: String?,
        password: String?
    ) {
        disconnect()

        try {
            val clientId = "imu_${UUID.randomUUID()}"

            val builder = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(host)
                .serverPort(port)

            if (!username.isNullOrBlank()) {
                builder.simpleAuth()
                    .username(username)
                    .password((password ?: "").toByteArray(StandardCharsets.UTF_8))
                    .applySimpleAuth()
            }

            val c = builder.buildAsync()
            client = c

            c.connect()
                .whenComplete { _, err ->
                    if (err != null) {
                        connected.set(false)
                        val msg = "MQTT connect error: ${err.message}"
                        Log.e("MqttManager", msg, err)
                        listener?.onError(msg)
                    } else {
                        connected.set(true)
                        Log.i("MqttManager", "MQTT connected")
                        listener?.onConnected()
                    }
                }

        } catch (e: Exception) {
            connected.set(false)
            val msg = "MQTT connect exception: ${e.message}"
            Log.e("MqttManager", msg, e)
            listener?.onError(msg)
        }
    }

    fun disconnect() {
        try {
            val c = client
            client = null

            if (c != null) {
                c.disconnect()
            }
        } catch (_: Exception) {
        } finally {
            if (connected.getAndSet(false)) {
                listener?.onDisconnected()
            }
        }
    }

    fun publish(topic: String, payload: String) {
        val c = client ?: return
        if (!connected.get()) return

        try {
            val msg: Mqtt3Publish = Mqtt3Publish.builder()
                .topic(topic)
                .qos(MqttQos.AT_MOST_ONCE)
                .payload(payload.toByteArray(StandardCharsets.UTF_8))
                .build()

            c.publish(msg)
        } catch (e: Exception) {
            val err = "MQTT publish error: ${e.message}"
            Log.e("MqttManager", err, e)
            listener?.onError(err)
        }
    }
}
