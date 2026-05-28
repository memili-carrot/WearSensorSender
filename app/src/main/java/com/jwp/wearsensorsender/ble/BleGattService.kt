package com.jwp.wearsensorsender.ble

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jwp.wearsensorsender.sensor.SensorTypeOption
import com.jwp.wearsensorsender.state.SenderState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Wear OS 송신부 Foreground Service.
 *
 * 논문 (b) BLE GATT 기반 센서 이벤트 프레임 (총 28 Bytes) 구현:
 *
 *   오프셋  필드          크기   타입            설명
 *   0       Seq          4      uint32 (BE)     시퀀스 번호
 *   4       Watch_TS     8      int64  (BE)     워치 송신 시각 (epoch millis)
 *   12      Sensor_Type  4      uint32 (BE)     센서 ID (1=ACC,2=GYRO,3=HR,4=LIGHT)
 *   16      Value0       4      float32 (BE)    x (또는 단일값)
 *   20      Value1       4      float32 (BE)    y
 *   24      Value2       4      float32 (BE)    z
 *                               = 28 bytes
 *
 * Big-endian(네트워크 바이트 순서)으로 인코딩한다.
 */
class BleGattService : Service(), SensorEventListener {
    private val tag = "BleGattService"

    // 커스텀 UUID (PC 수신기 adv_rx.py와 동일해야 함)
    private val serviceUuid = UUID.fromString("4f7d8a1c-1b2c-4d5e-6f70-8192a3b4c5d6")
    private val charUuid    = UUID.fromString("4f7d8a1c-1b2c-4d5e-6f70-8192a3b4c5d7")
    private val cccdUuid    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    companion object {
        const val ACTION_START = "com.jwp.wearsensorsender.START"
        const val ACTION_STOP  = "com.jwp.wearsensorsender.STOP"
        const val EXTRA_SENSORS = "sensors"

        // 논문 프레임 크기
        const val FRAME_SIZE = 28
    }

    private lateinit var sensorManager: SensorManager
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    private var seq = 0
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val registeredSensors = mutableListOf<Pair<Sensor, SensorTypeOption>>()
    private var isAdvertising = false
    private var isServerInitialized = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(tag, "✅ 광고 송출 성공")
            isAdvertising = true
            SenderState.setAdvertising(true)
        }
        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN"
            }
            Log.e(tag, "❌ 광고 송출 실패: $errorCode ($reason)")
            SenderState.setAdvertising(false)
            SenderState.setError("광고 실패: $reason")
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                SenderState.setBattery((level * 100) / scale)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sensorNames = intent.getStringArrayExtra(EXTRA_SENSORS)?.toList() ?: emptyList()
                val sensors = sensorNames.mapNotNull { name ->
                    runCatching { SensorTypeOption.valueOf(name) }.getOrNull()
                }.toSet()
                startSending(sensors)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startSending(sensors: Set<SensorTypeOption>) {
        if (sensors.isEmpty()) {
            SenderState.setError("선택된 센서가 없습니다")
            return
        }

        SenderState.setError(null)

        if (!isServerInitialized) {
            initForeground()
            isServerInitialized = true
        }

        registerSensors(sensors)

        // GATT 서비스가 시스템에 등록되는 데 약간의 시간이 걸리므로 광고는 살짝 늦춰서 시작
        handler.postDelayed({ startAdv() }, 500)

        SenderState.setRunning(true, "송신 중")
    }

    @SuppressLint("MissingPermission")
    private fun startAdv() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        advertiser = manager.adapter.bluetoothLeAdvertiser ?: run {
            Log.e(tag, "❌ BLE Advertiser 사용 불가")
            SenderState.setError("BLE Advertiser 사용 불가")
            return
        }

        if (isAdvertising) {
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // 광고 패킷은 Service UUID만 (31바이트 제한 회피)
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()

        // 디바이스 이름은 scan response로 분리
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, advData, scanResponse, advertiseCallback)
    }

    private fun registerSensors(sensors: Set<SensorTypeOption>) {
        unregisterAllSensors()
        for (option in sensors) {
            val sensor = sensorManager.getDefaultSensor(option.sensorType)
            if (sensor != null) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                registeredSensors.add(sensor to option)
                Log.d(tag, "✅ 센서 등록: ${option.label}")
            } else {
                Log.w(tag, "⚠️ 센서 미지원: ${option.label}")
            }
        }
    }

    private fun unregisterAllSensors() {
        sensorManager.unregisterListener(this)
        registeredSensors.clear()
    }

    /**
     * 논문 28바이트 GATT 프레임을 빌드한다.
     * Seq(4) + Watch_TS(8) + Sensor_Type(4) + Value0(4) + Value1(4) + Value2(4)
     */
    private fun buildFrame(
        seq: Int,
        watchTs: Long,
        sensorType: Int,
        v0: Float,
        v1: Float,
        v2: Float,
    ): ByteArray {
        return ByteBuffer.allocate(FRAME_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(seq)            // 0  : Seq (uint32)
            .putLong(watchTs)       // 4  : Watch_TS (int64, epoch millis)
            .putInt(sensorType)     // 12 : Sensor_Type (uint32)
            .putFloat(v0)           // 16 : Value0 (float32)
            .putFloat(v1)           // 20 : Value1 (float32)
            .putFloat(v2)           // 24 : Value2 (float32)
            .array()
    }

    @SuppressLint("MissingPermission")
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val option = registeredSensors.firstOrNull { it.first == event.sensor }?.second ?: return

        seq++
        val watchTs = System.currentTimeMillis()
        val v0 = event.values.getOrNull(0) ?: 0f
        val v1 = event.values.getOrNull(1) ?: 0f
        val v2 = event.values.getOrNull(2) ?: 0f

        // 28바이트 바이너리 프레임 생성
        val msg = buildFrame(seq, watchTs, option.frameId, v0, v1, v2)

        SenderState.incrementSeq(option.label)

        val service = gattServer?.getService(serviceUuid)
        val char = service?.getCharacteristic(charUuid) ?: return

        connectedDevices.forEach { device ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, char, false, msg)
            } else {
                char.value = msg
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @SuppressLint("MissingPermission")
    private fun initForeground() {
        val chan = NotificationChannel("ble", "BLE", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        startForeground(1, NotificationCompat.Builder(this, "ble")
            .setContentTitle("센서 송신 중")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build())

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = manager.openGattServer(this, object : BluetoothGattServerCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices.add(device)
                    SenderState.setConnected(true, device.name ?: "Unknown", device.address)
                    Log.d(tag, "🔗 연결됨: ${device.address}")
                } else {
                    connectedDevices.remove(device)
                    if (connectedDevices.isEmpty()) {
                        SenderState.setConnected(false)
                    }
                    Log.d(tag, "❌ 연결 해제: ${device.address}")
                }
            }

            // 🔧 MTU 변경 감지 (28바이트 프레임이 한 패킷에 들어가는지 확인)
            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                Log.d(tag, "📏 MTU 변경됨: $mtu (payload 가능: ${mtu - 3} bytes)")
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    Log.d(tag, "✅ Notify 활성화 요청 수락")
                }
            }
        })

        val srv = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val char = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val configDescriptor = BluetoothGattDescriptor(
            cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        char.addDescriptor(configDescriptor)
        srv.addCharacteristic(char)
        gattServer?.addService(srv)

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "BLE:TestLock"
        )
        wakeLock?.acquire(40 * 60 * 1000L)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        unregisterAllSensors()
        try {
            if (isAdvertising) advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        SenderState.resetSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
