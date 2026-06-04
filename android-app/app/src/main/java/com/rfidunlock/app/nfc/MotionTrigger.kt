package com.rfidunlock.app.nfc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Триггер на основе акселерометра.
 *
 * Пока смартфон неподвижно лежит на метке — NFC не опрашивается.
 * При обнаружении движения (модуль ускорения отклоняется от g сверх
 * порога) вызывается [onMotion], по которому инициируется внеочередная
 * проверка присутствия метки (детекция снятия → команда LOCK).
 */
class MotionTrigger(
    context: Context,
    private val onMotion: () -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Порог отклонения |a|-g (м/с²), выше которого считаем, что было движение. */
    var thresholdMs2: Float = 1.2f

    /** Минимальный интервал между срабатываниями (мс), чтобы не частить. */
    var debounceMs: Long = 800

    private var lastTrigger = 0L

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = kotlin.math.abs(magnitude - SensorManager.GRAVITY_EARTH)
        if (delta > thresholdMs2) {
            val now = System.currentTimeMillis()
            if (now - lastTrigger >= debounceMs) {
                lastTrigger = now
                onMotion()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
