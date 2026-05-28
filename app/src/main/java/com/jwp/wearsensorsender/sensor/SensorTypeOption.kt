package com.jwp.wearsensorsender.sensor

import android.hardware.Sensor

/**
 * 송신 가능한 센서 종류 정의.
 *
 * frameId: BLE 프레임에 박히는 정수 ID (PC 수신기에서 센서 구분용).
 * needsBodySensorPermission: HR/체온 등 BODY_SENSORS 권한이 필요한 센서.
 */
enum class SensorTypeOption(
    val sensorType: Int,
    val label: String,
    val frameId: Int,
    val needsBodySensorPermission: Boolean = false,
) {
    ACC(Sensor.TYPE_ACCELEROMETER, "ACC", 1),
    GYRO(Sensor.TYPE_GYROSCOPE, "GYRO", 2),
    HR(Sensor.TYPE_HEART_RATE, "HR", 3, needsBodySensorPermission = true),
    LIGHT(Sensor.TYPE_LIGHT, "LIGHT", 4);

    companion object {
        fun fromLabel(label: String): SensorTypeOption? = entries.firstOrNull { it.label == label }
    }
}
