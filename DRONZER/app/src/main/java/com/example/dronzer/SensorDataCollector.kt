package com.example.dronzer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

object SensorDataCollector : SensorEventListener {
    private var sensorManager: SensorManager? = null
    
    private val sensorData = mutableMapOf<Int, FloatArray>()
    private val sensorNames = mapOf(
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_GYROSCOPE to "Gyroscope",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer",
        Sensor.TYPE_LIGHT to "Light",
        Sensor.TYPE_PROXIMITY to "Proximity",
        Sensor.TYPE_PRESSURE to "Pressure",
        Sensor.TYPE_AMBIENT_TEMPERATURE to "Ambient Temperature",
        Sensor.TYPE_RELATIVE_HUMIDITY to "Humidity"
    )

    fun start(context: Context) {
        if (sensorManager != null) return
        
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.let { manager ->
            sensorNames.keys.forEach { sensorType ->
                val sensor = manager.getDefaultSensor(sensorType)
                if (sensor != null) {
                    manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            sensorData[it.sensor.type] = it.values.copyOf()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for simple data collection
    }

    fun getSensorDetails(): String {
        val sb = StringBuilder()
        sb.append("--- Real-time Sensor Data ---\n")
        
        if (sensorData.isEmpty()) {
            sb.append("No sensor data available yet.\n")
            return sb.toString()
        }

        sensorNames.forEach { (type, name) ->
            val values = sensorData[type]
            if (values != null) {
                sb.append("$name: ")
                when (type) {
                    Sensor.TYPE_LIGHT -> sb.append("${values[0]} lx")
                    Sensor.TYPE_PROXIMITY -> sb.append("${values[0]} cm")
                    Sensor.TYPE_PRESSURE -> sb.append("${values[0]} hPa")
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> sb.append("${values[0]} °C")
                    Sensor.TYPE_RELATIVE_HUMIDITY -> sb.append("${values[0]} %")
                    else -> sb.append(values.joinToString(", ") { "%.2f".format(it) })
                }
                sb.append("\n")
            } else {
                sb.append("$name: Not Available\n")
            }
        }
        return sb.toString()
    }
}
