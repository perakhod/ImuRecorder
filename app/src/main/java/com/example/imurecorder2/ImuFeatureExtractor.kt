package com.example.imurecorder2

import kotlin.math.sqrt

object ImuFeatureExtractor {

    fun extract(window: List<ImuSample>): FloatArray {
        val axes = arrayOf(
            FloatArray(window.size) { window[it].ax },
            FloatArray(window.size) { window[it].ay },
            FloatArray(window.size) { window[it].az },
            FloatArray(window.size) { window[it].gx },
            FloatArray(window.size) { window[it].gy },
            FloatArray(window.size) { window[it].gz },
            FloatArray(window.size) { window[it].mx },
            FloatArray(window.size) { window[it].my },
            FloatArray(window.size) { window[it].mz }
        )

        val out = ArrayList<Float>(42)

        for (a in axes) {
            val mean = a.average().toFloat()
            var varSum = 0f
            var mn = Float.POSITIVE_INFINITY
            var mx = Float.NEGATIVE_INFINITY
            for (v in a) {
                val d = v - mean
                varSum += d * d
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            val std = sqrt(varSum / a.size.toFloat())

            out.add(mean)
            out.add(std)
            out.add(mn)
            out.add(mx)
        }

        // magnitudes acc/gyro/mag mean+std
        val mags = arrayOf(
            FloatArray(window.size) {
                val s = window[it]
                sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            },
            FloatArray(window.size) {
                val s = window[it]
                sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
            },
            FloatArray(window.size) {
                val s = window[it]
                sqrt(s.mx * s.mx + s.my * s.my + s.mz * s.mz)
            }
        )

        for (m in mags) {
            val mean = m.average().toFloat()
            var varSum = 0f
            for (v in m) {
                val d = v - mean
                varSum += d * d
            }
            val std = sqrt(varSum / m.size.toFloat())
            out.add(mean)
            out.add(std)
        }

        return out.toFloatArray()
    }
}
