# WearSensorSender — Wear OS 송신 모듈

스마트워치에서 멀티센서 데이터를 수집하여 두 경로로 전송하는 Wear OS 앱입니다.

- **BLE GATT 경로** → PC / Linux 수신기 (`BleGattService`, 기존)
- **Data Layer API 경로** → Android 스마트폰 수신기 (`DataLayerSenderService`, 신규)

두 경로 모두 동일한 **28바이트 Big-Endian 프레임**을 사용하므로, 수신기는 통신 방식과
무관하게 동일한 복호화 규칙으로 파싱할 수 있습니다.

## 파일 구성

```
com.jwp.wearsensorsender
├─ presentation/
│  ├─ MainActivity.kt        화면 4종(STANDBY/SENSOR_SELECT/SENDING/CONNECTION_INFO) + 프로토콜 토글·서비스 분기
│  └─ theme/Theme.kt         Wear Compose 테마
├─ state/
│  ├─ SenderState.kt         UI·서비스 공유 단일 상태 소스(StateFlow 싱글톤)
│  └─ TransmitProtocol.kt    전송 경로 enum (BLE_GATT ↔ DATA_LAYER)
├─ sensor/
│  └─ SensorTypeOption.kt    센서 정의(ACC=1, GYRO=2, HR=3, LIGHT=4)
├─ frame/
│  └─ SensorFrameCodec.kt    공통 28B 프레임 인코더(두 경로가 공유)
├─ ble/
│  └─ BleGattService.kt      BLE GATT 송신 Foreground Service (→ PC/Linux)
└─ datalayer/
   └─ DataLayerSenderService.kt  Data Layer 송신 Foreground Service (→ Android 폰)
```

## 동작 개요

1. `MainActivity` 가 `SenderState` 를 관찰하며 4개 화면을 렌더링.
2. 대기 화면에서 센서 선택(`SensorTypeOption`)과 프로토콜(`TransmitProtocol`)을 고름.
3. Start → 선택된 프로토콜에 따라 `BleGattService` 또는 `DataLayerSenderService` 를
   Foreground Service 로 기동.
4. 각 서비스가 `SensorManager` 이벤트마다 Seq·Watch_TS 를 부여하고 `SensorFrameCodec`
   으로 28B 프레임을 만들어 전송.
5. 연결/시퀀스/배터리/오류 상태를 `SenderState` 로 갱신 → UI 자동 recomposition.

## 빌드 메모

- `play-services-wearable` 의존성 필요(Data Layer).
- `DataLayerSenderService` 를 Manifest `<application>` 에 등록(`foregroundServiceType="connectedDevice"`).
- Data Layer 페어링을 위해 워치 앱과 스마트폰 수신기 앱은 동일 서명 키·applicationId 로 빌드.
- 상세 배치/매니페스트/테스트 순서는 함께 제공된 `INTEGRATION.md` 참고.
