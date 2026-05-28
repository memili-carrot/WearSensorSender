package com.jwp.wearsensorsender.state

import com.jwp.wearsensorsender.sensor.SensorTypeOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI와 BleGattService가 공유하는 단일 상태 소스.
 * StateFlow 기반이라 Compose에서 collectAsState로 바로 관찰 가능.
 */
object SenderState {

    data class State(
        val selectedSensors: Set<SensorTypeOption> = setOf(SensorTypeOption.ACC),
        val isRunning: Boolean = false,
        val isAdvertising: Boolean = false,
        val isConnected: Boolean = false,
        val connectedDeviceName: String? = null,
        val connectedMac: String? = null,
        val seq: Int = 0,
        val currentSensorLabel: String = "-",
        val batteryPercent: Int = 0,
        val statusMessage: String = "대기",
        val errorMessage: String? = null,
    ) {
        val selectedSensorsText: String
            get() = if (selectedSensors.isEmpty()) "없음" else selectedSensors.joinToString(", ") { it.label }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun toggleSensor(option: SensorTypeOption) {
        _state.update {
            val newSet = if (option in it.selectedSensors) {
                it.selectedSensors - option
            } else {
                it.selectedSensors + option
            }
            it.copy(selectedSensors = newSet)
        }
    }

    fun setRunning(running: Boolean, status: String) {
        _state.update { it.copy(isRunning = running, statusMessage = status) }
    }

    fun setAdvertising(advertising: Boolean) {
        _state.update { it.copy(isAdvertising = advertising) }
    }

    fun setConnected(connected: Boolean, name: String? = null, mac: String? = null) {
        _state.update {
            it.copy(
                isConnected = connected,
                connectedDeviceName = name ?: it.connectedDeviceName,
                connectedMac = mac ?: it.connectedMac,
                statusMessage = when {
                    connected -> "GATT Connected"
                    it.isRunning -> "송신 중"
                    else -> "대기"
                }
            )
        }
    }

    fun incrementSeq(sensorLabel: String) {
        _state.update { it.copy(seq = it.seq + 1, currentSensorLabel = sensorLabel) }
    }

    fun setBattery(percent: Int) {
        _state.update { it.copy(batteryPercent = percent) }
    }

    fun setError(message: String?) {
        _state.update { it.copy(errorMessage = message) }
    }

    fun resetSession() {
        _state.update {
            it.copy(
                isRunning = false,
                isAdvertising = false,
                isConnected = false,
                connectedDeviceName = null,
                connectedMac = null,
                seq = 0,
                currentSensorLabel = "-",
                statusMessage = "대기",
            )
        }
    }
}
