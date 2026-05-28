package com.jwp.wearsensorsender.presentation

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import com.jwp.wearsensorsender.ble.BleGattService
import com.jwp.wearsensorsender.presentation.theme.WearSensorSenderTheme
import com.jwp.wearsensorsender.sensor.SensorTypeOption
import com.jwp.wearsensorsender.state.SenderState

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.BODY_SENSORS)            // HR 등
        add(Manifest.permission.WAKE_LOCK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)  // Foreground 알림
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 사용자 응답은 UI에서 collectAsState로 자동 반영되므로 별도 처리 없음 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        setContent {
            WearApp(
                onStart = { sensors -> startSenderService(sensors) },
                onStop  = { stopSenderService() },
            )
        }
    }

    private fun startSenderService(sensors: Set<SensorTypeOption>) {
        val intent = Intent(this, BleGattService::class.java).apply {
            action = BleGattService.ACTION_START
            putExtra(BleGattService.EXTRA_SENSORS, sensors.map { it.name }.toTypedArray())
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSenderService() {
        val intent = Intent(this, BleGattService::class.java).apply {
            action = BleGattService.ACTION_STOP
        }
        startService(intent)
    }
}

// =========================
// 화면 상태 분기
// =========================
enum class WatchScreenState {
    STANDBY,
    SENSOR_SELECT,
    SENDING,
    CONNECTION_INFO,
}

@Composable
fun WearApp(
    onStart: (Set<SensorTypeOption>) -> Unit,
    onStop: () -> Unit,
) {
    WearSensorSenderTheme {
        val state by SenderState.state.collectAsState()
        var screen by remember { mutableStateOf(WatchScreenState.STANDBY) }

        // 송신이 외부 트리거(서비스 자체 종료 등)로 멈췄을 때 STANDBY로 되돌리기
        LaunchedEffect(state.isRunning) {
            if (!state.isRunning && screen != WatchScreenState.STANDBY && screen != WatchScreenState.SENSOR_SELECT) {
                screen = WatchScreenState.STANDBY
            }
        }

        when (screen) {
            WatchScreenState.STANDBY -> StandbyScreen(
                selectedSensorsText = state.selectedSensors.joinToString(", ") { it.label }
                    .ifEmpty { "선택 안 됨" },
                protocol = "BLE GATT",
                statusText = state.statusMessage,
                errorText = state.errorMessage,
                onSensorClick = { screen = WatchScreenState.SENSOR_SELECT },
                onStartClick = {
                    if (state.selectedSensors.isNotEmpty()) {
                        onStart(state.selectedSensors)
                        screen = WatchScreenState.SENDING
                    }
                }
            )

            WatchScreenState.SENSOR_SELECT -> SensorSelectScreen(
                selected = state.selectedSensors,
                onToggle = { SenderState.toggleSensor(it) },
                onDone = { screen = WatchScreenState.STANDBY },
            )

            WatchScreenState.SENDING -> SendingScreen(
                statusText = state.statusMessage,
                seq = state.seq,
                currentSensor = state.currentSensorLabel,
                batteryPercent = state.batteryPercent,
                isConnected = state.isConnected,
                onStopClick = {
                    onStop()
                    screen = WatchScreenState.STANDBY
                },
                onConnectionInfoClick = {
                    if (state.isConnected) screen = WatchScreenState.CONNECTION_INFO
                }
            )

            WatchScreenState.CONNECTION_INFO -> ConnectionInfoScreen(
                deviceName = state.connectedDeviceName ?: "Unknown",
                mac = state.connectedMac ?: "-",
                statusText = state.statusMessage,
                protocol = "BLE GATT",
                onStopClick = {
                    onStop()
                    screen = WatchScreenState.STANDBY
                },
                onBack = { screen = WatchScreenState.SENDING },
            )
        }
    }
}

// =========================
// 1) STANDBY 화면
// =========================
@Composable
fun StandbyScreen(
    selectedSensorsText: String,
    protocol: String,
    statusText: String,
    errorText: String?,
    onSensorClick: () -> Unit,
    onStartClick: () -> Unit,
) {
    WatchRoot {
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "센서 송신기",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(7.dp))

        InfoCard(
            iconText = "≈",
            title = "센서 (탭하여 선택)",
            value = selectedSensorsText,
            iconBackground = Color(0xFF2F80ED),
            onClick = onSensorClick,
        )

        Spacer(modifier = Modifier.height(5.dp))

        InfoCard(
            iconText = "♢",
            title = "프로토콜",
            value = protocol,
            iconBackground = Color(0xFF5A4FCF),
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = "상태: $statusText",
            color = Color(0xFF79FF5A),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        if (errorText != null) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = errorText,
                color = Color(0xFFFF6B6B),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(5.dp))

        LargeActionButton(
            text = "▶  Start",
            background = Color(0xFF50C957),
            onClick = onStartClick
        )
    }
}

// =========================
// 2) SENSOR_SELECT 화면 (신규)
// =========================
@Composable
fun SensorSelectScreen(
    selected: Set<SensorTypeOption>,
    onToggle: (SensorTypeOption) -> Unit,
    onDone: () -> Unit,
) {
    WatchRoot {
        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "센서 선택",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))

        SensorTypeOption.entries.forEach { option ->
            SensorToggleRow(
                label = option.label,
                checked = option in selected,
                onToggle = { onToggle(option) },
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(6.dp))

        LargeActionButton(
            text = "✓  완료",
            background = Color(0xFF2F80ED),
            onClick = onDone,
        )
    }
}

@Composable
fun SensorToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) Color(0xFF2A4A7F) else Color(0xFF222225))
            .noRippleClickable { onToggle() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (checked) "■" else "□",
            color = if (checked) Color(0xFF79FF5A) else Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// =========================
// 3) SENDING 화면
// =========================
@Composable
fun SendingScreen(
    statusText: String,
    seq: Int,
    currentSensor: String,
    batteryPercent: Int,
    isConnected: Boolean,
    onStopClick: () -> Unit,
    onConnectionInfoClick: () -> Unit,
) {
    WatchRoot {
        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "센서 송신기",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "상태: ", color = Color.White, fontSize = 13.sp)
            Text(
                text = statusText,
                color = Color(0xFF79FF5A),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "⌁",
                color = Color(0xFF79FF5A),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        SmallStatusCard(iconText = "#", value = "Seq: $seq", iconColor = Color(0xFF2F80ED))
        Spacer(modifier = Modifier.height(5.dp))

        SmallStatusCard(iconText = "⌁", value = "센서: $currentSensor", iconColor = Color(0xFF61D550))
        Spacer(modifier = Modifier.height(5.dp))

        SmallStatusCard(iconText = "▣", value = "배터리: ${batteryPercent}%", iconColor = Color(0xFFFFD43B))

        Spacer(modifier = Modifier.height(6.dp))

        LargeActionButton(
            text = "■  Stop",
            background = Color(0xFFE9453F),
            onClick = onStopClick
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (isConnected) "연결 정보 보기" else "수신기 대기 중...",
            color = if (isConnected) Color(0xFF6BB6FF) else Color(0xFF888888),
            fontSize = 10.sp,
            modifier = Modifier.noRippleClickable { onConnectionInfoClick() }
        )
    }
}

// =========================
// 4) CONNECTION_INFO 화면
// =========================
@Composable
fun ConnectionInfoScreen(
    deviceName: String,
    mac: String,
    statusText: String,
    protocol: String,
    onStopClick: () -> Unit,
    onBack: () -> Unit,
) {
    WatchRoot {
        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "연결 정보",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
                .noRippleClickable { onBack() }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ConnectionInfoCard(
            iconText = "▣",
            title = "연결된 기기: $deviceName",
            line1 = "MAC: $mac",
            line2 = "Device: PC Receiver",
            iconColor = Color(0xFF2F80ED)
        )

        Spacer(modifier = Modifier.height(6.dp))

        ConnectionStatusCard(title = "연결 상태", status = statusText)

        Spacer(modifier = Modifier.height(6.dp))

        ProtocolPill(text = "♢  $protocol")

        Spacer(modifier = Modifier.height(6.dp))

        LargeActionButton(
            text = "■  Stop",
            background = Color(0xFFE9453F),
            onClick = onStopClick
        )
    }
}

// =========================
// 공용 컴포넌트
// =========================
@Composable
fun WatchRoot(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        TimeText(modifier = Modifier.align(Alignment.TopCenter))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
fun InfoCard(
    iconText: String,
    title: String,
    value: String,
    iconBackground: Color,
    onClick: (() -> Unit)? = null,
) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .height(50.dp)
        .clip(RoundedCornerShape(13.dp))
        .background(
            Brush.horizontalGradient(listOf(Color(0xFF1C1C1E), Color(0xFF242426)))
        )
    val rowModifier = if (onClick != null) baseModifier.noRippleClickable(onClick) else baseModifier

    Row(
        modifier = rowModifier.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleIcon(text = iconText, background = iconBackground)
        Spacer(modifier = Modifier.width(9.dp))
        Column {
            Text(text = title, color = Color(0xFFBFC3C7), fontSize = 10.sp)
            Text(text = value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SmallStatusCard(iconText: String, value: String, iconColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF222225))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = iconText, color = iconColor, fontSize = 18.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp)
        )
        Text(text = value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ConnectionInfoCard(
    iconText: String, title: String, line1: String, line2: String, iconColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF1F1F22))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = iconText, color = iconColor, fontSize = 24.sp, modifier = Modifier.width(32.dp))
        Column {
            Text(text = title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(text = line1, color = Color(0xFFDADADA), fontSize = 9.sp)
            Text(text = line2, color = Color(0xFFDADADA), fontSize = 9.sp)
        }
    }
}

@Composable
fun ConnectionStatusCard(title: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF1F1F22))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "🔗", fontSize = 18.sp, modifier = Modifier.width(32.dp))
        Column {
            Text(text = title, color = Color(0xFFBFC3C7), fontSize = 10.sp)
            Text(text = status, color = Color(0xFF79FF5A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ProtocolPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(width = 1.dp, color = Color(0xFF2F80ED), shape = RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
fun CircleIcon(text: String, background: Color) {
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LargeActionButton(text: String, background: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .noRippleClickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    this.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

// =========================
// Preview
// =========================
@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun StandbyPreview() {
    WearSensorSenderTheme {
        StandbyScreen(
            selectedSensorsText = "ACC, HR",
            protocol = "BLE GATT",
            statusText = "대기",
            errorText = null,
            onSensorClick = {},
            onStartClick = {}
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun SensorSelectPreview() {
    WearSensorSenderTheme {
        SensorSelectScreen(
            selected = setOf(SensorTypeOption.ACC),
            onToggle = {},
            onDone = {}
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun SendingPreview() {
    WearSensorSenderTheme {
        SendingScreen(
            statusText = "송신 중",
            seq = 1024,
            currentSensor = "ACC",
            batteryPercent = 78,
            isConnected = true,
            onStopClick = {},
            onConnectionInfoClick = {}
        )
    }
}
