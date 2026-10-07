package com.condroid.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.roundToInt

class GyroTracker(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    var enabled: Boolean = false
        set(value) {
            field = value
            if (value) start() else stop()
        }

    var sensitivity: Float = 1.0f

    @Volatile
    var gyroX: Short = 0
        private set

    @Volatile
    var gyroY: Short = 0
        private set

    @Volatile
    var gyroZ: Short = 0
        private set

    private fun start() {
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    private fun stop() {
        sensorManager.unregisterListener(this)
        gyroX = 0
        gyroY = 0
        gyroZ = 0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !enabled) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            // Gyroscope angular speed in rad/s
            // Scaled to 16-bit range for controller transmission
            val scale = 5000.0f * sensitivity
            gyroX = (event.values[0] * scale).roundToInt().coerceIn(-32768, 32767).toShort()
            gyroY = (event.values[1] * scale).roundToInt().coerceIn(-32768, 32767).toShort()
            gyroZ = (event.values[2] * scale).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
