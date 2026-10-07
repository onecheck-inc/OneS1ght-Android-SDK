# Changelog

이 파일은 사람이 읽는 릴리스 기록입니다.
코딩 에이전트가 읽는 **버전 간 코드 수정 지침**은 [`Migrations/android.json`](Migrations/android.json)에 따로 있습니다.

버전은 [유의적 버전](https://semver.org/lang/ko/)을 따릅니다. `0.x` 동안은 마이너 판올림에도
깨지는 변경이 들어갈 수 있으며, 그때는 아래에 **Breaking** 으로 표시하고 마이그레이션 파일에
고치는 방법을 함께 싣습니다.

---

## [Unreleased]

### 고침
- **Galaxy S25+(SM-S936N, Android 17)에서 측위가 시작되지 않던 문제를 고쳤습니다.** 측위 엔진을 새 판으로 올렸습니다 — UWB 세션이 열리지 않아 `E4001` 이 반복되던 기기입니다. 공개 API·전이 의존성 변경 없음.

---

## [0.0.8] — 2026-10-02

공개 API 변경 없음. 의존성 버전만 올리면 됩니다.

### 고침
- **보낸 요청이 몰래 다시 전송되지 않습니다.** 네트워크 라이브러리(OkHttp) 기본값은 재사용하던 연결이 응답 전에 끊기면,
  서버가 이미 받은 POST 라도 새 연결로 다시 보냈습니다. 구역 진입·이탈 기록·좌표·프로필 생성이 SDK 도 모르게 두 번
  쌓일 수 있었습니다(방문·체류 집계 부풀림, 주인 없는 프로필). 이제 한 번 보낸 POST 는 다시 보내지 않고, 재시도는 SDK 가
  정한 규칙(구역 이벤트 1회·좌표 backoff)만 따릅니다. 연결 단계 실패의 자동 복구와 조회(GET)는 그대로입니다. iOS 와 같은 동작입니다.

### 고객 앱 영향 축소
- **SDK 가 싣는 R8 규칙이 앱 코드에 걸리지 않습니다.** Gson 관련 규칙이 앱의 모든 클래스에 걸려, SDK 를 넣었다는 이유로 앱 자신의
  TypeAdapter·`@SerializedName` 필드·TypeToken 이 축소·난독화에서 빠졌습니다. 범위를 측위 엔진 패키지로 좁혔습니다.
  앱이 자기 Gson 모델을 쓰면서 이 규칙에 기대고 있었다면 앱에 Gson 규칙을 직접 두세요(Gson 2.11+ 는 자체 규칙을 싣습니다).

---

## [0.0.7] — 2026-10-02

**공개 API 를 iOS SDK(main #55)와 똑같이 맞췄습니다.** 이름이 바뀐 API 는 옛 이름이 경고만 내고 그대로 동작하지만,
고객이 쓸 일이 없던 내부 타입은 공개에서 내렸습니다(**Breaking**). 엔진이 스스로 꺼져도 세션이 「측위 중」에 굳지 않습니다.

- **고객 앱 R8**: 진입점 `OneS1ght` 를 지우지 않는 규칙을 SDK 가 싣는다(범위는 이 한 클래스 — 앱 코드의 축소·난독화엔 영향 없음). 0.0.6 과 같은 결과물 모양이다.

### Breaking

> 마이그레이션 지침은 [`Migrations/android.json`](Migrations/android.json) 의 `migrations[]` 마지막 칸(0.0.6 → 0.0.7)에 있다.

**공개에서 내린 것(internal)** — README·스니펫이 안내한 적 없는 내부 부품입니다. 쓰고 있었다면 오른쪽으로 옮기세요.

| 0.0.6 공개 심볼 | 옮길 곳 |
|---|---|
| `ApiClient` 생성자·`apiKey`·`baseUrl` | `OneS1ght.initialize(context, sdkKey[, baseUrl])` · 프로필은 `OneS1ght.createProfile`·`fetchProfile`·`replaceProfile`·`deleteProfile`. 타입 `ApiClient` 와 `ApiClient.DEFAULT_BASE_URL`(deprecated)만 남았다. |
| `IdentityStore` · `KeyValueStore` · `InMemoryKeyValueStore` | 없음 — 방문 ID 는 SDK 가 발급한다(iOS 는 `IdentityStore` internal, `SecureStore`·`KeychainSecureStore` 삭제). |
| `SdkDefaults` | 없음 — 서버가 값을 안 줄 때의 내부 기본값(4·1·100 Hz)이다. |
| `MockPositioningProvider` | `PositioningProvider` 를 직접 구현한다(요구사항은 `delegate`·`start()`·`stop()` 셋, 나머지는 기본 구현). |
| `ConfigChange.Companion`(빈 companion) | 없음. |

**이름 바꿈(옛 이름은 `@Deprecated(WARNING)` — 경고만, IDE 의 Replace 가 바꿔 줌)**

| 0.0.6 | 이번 판 | iOS |
|---|---|---|
| `OneS1ght.permissions(activity[, cb])` | `OneS1ght.requestPermission(activity[, cb])` — 확인이 아니라 **시스템 창을 띄운다** | `requestPermission()` |
| `OneS1ght.getProfile(id[, cb])` | `OneS1ght.fetchProfile(id[, cb])` | `fetchProfile(_:)` |
| `OneS1ght.putProfile(id, attrs[, cb])` | `OneS1ght.replaceProfile(id, attrs[, cb])` — 넘기지 않은 속성은 지워진다 | `replaceProfile(_:attributes:)` |
| `OneS1ght.send([cb])` | `OneS1ght.uploadPendingPositions([cb])` | `uploadPendingPositions()` |
| `OneS1ght.empty()` | `OneS1ght.discardPendingPositions()` | `discardPendingPositions()` |
| `ApiClient.DEFAULT_BASE_URL` | `OneS1ght.DEFAULT_BASE_URL` | `OneS1ght.defaultBaseURL` |
| `OneS1ght.building(buildingId = …)` | `OneS1ght.building(id = …)` — 매개변수 이름만(위치 인자는 그대로) | `building(id:)` |

`floors(buildingId)`·`floor(buildingId, floorId)`·`zones(…)`·`zone(…)`·`locators(…)`·`setFloorMap(floor, buildingId)`·
`Trigger.triggerId` 는 안드로이드가 이미 iOS 새 이름과 같았다.

**0.0.6 뒤 main 에만 있던 것(릴리스 전) — 모양을 iOS 에 맞춤**

- `FloorSession.onStopped` — `SessionStoppedListener { reason -> }` 가 `FloorSession.StopReason`(`ENDED`·`ENGINE_FAILED`)을 받는다.
  `end()`·`reset()`·키 교체 때도 `ENDED` 로 온다.
- `FloorSession.onFloorDetected` — `SessionFloorListener { floorId: String? -> }`, `Floor.id` 와 같은 문자열(iOS `(String?) -> Void`).
  엔진 층 번호(`Long?`)는 `UwbPositioningProvider.onFloorDetected` 가 그대로 준다.
- `PositioningProvider.applyZones` 를 공개 계약에서 뺐다(iOS 에 없다) — 구역 새로고침은 iOS 처럼 앵커가 빈
  `apply(PositioningConfig(zones = …))` 로 온다. 내장 provider 는 SDK 안에서 따로 받아 등록 로케이터 수를 지키므로 동작은 같다.

**동작 바뀜**

- `Trigger.triggerId` 는 서버가 빼면 빈 문자열(숫자면 문자열로), `type` 은 빼면 `"generic"` 입니다(예전엔 그 트리거가 버려졌습니다).
  존 이벤트 응답의 `accepted`·`event_id` 도 관대하게 읽습니다.
- `UwbPositioningProvider.stop()`·`start()` 가 일시정지를 풀지 않습니다(`resume()` 만 푼다). `FloorSession` 의 `begin()`·`end()` 는
  예전처럼 일시정지 없이 시작·종료합니다.

### 추가

- `PositioningProviderDelegate.onFloorDetected(provider, floorId: String?)` · `onEmit(provider, event: ZoneEvent)` — 선택 채택(기본 구현).
  커스텀 provider 가 부르면 `FloorSession.onFloorDetected`·`onZoneEnter/Exit/Dwell` 로 이어진다 — 예전엔 내장 provider 일 때만 왔다(iOS K14).
- `FloorSession.StopReason` · `SessionFloorListener` · `OneS1ght.DEFAULT_BASE_URL`.

### 고침 (감사 2026-10-02, #11~#26 — iOS #54·#55 와 같은 수정)

- 엔진이 스스로 꺼지면 3·10·30초 뒤 다시 켜 보고, 안 되면 세션을 닫는다(`FloorSession.onStopped(ENGINE_FAILED)`).
- `E3001`(층 미지정)을 서버로 올리지 않는다 · 좌표 전송 중 `empty()` 크래시 · 오프라인 전송 폭주 · 프로필 연결 전 로그 유실.
- `identify` 를 `initialize` 보다 먼저 불러도 이어진다 · `reset` 뒤 옛 건물 문맥 · `baseUrl` 이 공간 조회에도 · 도면 캐시 무효화.
- 서버 목록·응답을 항목별로 관대하게 · 망가진 폴리곤 점만 거르기 · 구역 새로고침이 체류 타이머를 지우지 않기.
- 백그라운드에 다녀와도 일시정지 유지 · 엔진 상태 기계(동기 오류·탐지 모드 고착·정지 중 시작) · 판정·실시간 연결·오류 매핑.
- 건물 없이 처음 `setFloorMap(floor)` 하면 `SdkError.BuildingNotSet`(E3001)으로 거절(안드로이드에만 — iOS 는 조용히 층을 비운다).

### 문서

- README 3개 언어·스니펫을 새 이름으로 · `onStopped`/`onFloorDetected` 예제 · 커스텀 provider 도 구역 콜백이 온다고 정정.
- SDK enum·sealed class(`ConfigChange`·`SdkErrorCode`·`ZoneEvent`·`FloorSession.StopReason`·`PermissionStatus`·`DeviceAvailability`·
  `LogLevel`)는 마이너 판에서 갈래가 늘 수 있으니 `when` 에 `else` 를 두라고 명시(iOS `@unknown default` 와 같은 안내).

---

## [0.0.6] — 2026-09-30

**Bluetooth 꺼짐을 권한 거부와 나눠 `E2004` 로 남깁니다 — iOS 0.1.24 와 같습니다.**
예전엔 Bluetooth 를 끈 것도 `E2003` 「측위 권한 거부」로 올라갔습니다. 권한은 설정 앱에서 풀어야 하고, 꺼짐은
빠른 설정에서 켜면 풀려 안내가 달라야 해 나눴습니다. 공개 API·권한·동작은 그대로입니다
([`Migrations/android.json`](Migrations/android.json) 0.0.5→0.0.6).

```kotlin
implementation("com.ones1ght.sdk:android:0.0.6")
```

### 추가

- **`E2004` Bluetooth 꺼짐(`SdkErrorCode.BLUETOOTH_OFF`, WARN).** 측위 엔진은 꺼짐·권한·미지원을 모두 오류 3 으로
  주고 문장으로만 구분합니다 — 문장에 `powered off` 가 있을 때(시작할 때 꺼져 있었거나, 도는 중에 껐을 때)만
  `E2004` 이고, 권한(`BLUETOOTH_SCAN`)·미지원은 종전대로 `E2003` 입니다. 서버 로그·`OneS1ght.onDebugLog` 에 `E2004`
  로 남고, `provider.onEngineError` 는 종전처럼 엔진 원본 번호(3)·문장을 그대로 받습니다.
  Bluetooth 상태를 SDK 가 따로 묻지 않으므로 **새 권한은 필요 없습니다.**

### 앱에서 할 일

- 없습니다. `onDebugLog` 에서 `E2003` 으로 Bluetooth 꺼짐을 판단하던 코드가 있었다면 `E2004` 도 보세요.
  `SdkErrorCode` 를 `when` 으로 빠짐없이(else 없이) 가르는 코드는 `BLUETOOTH_OFF` 가지를 더해야 컴파일됩니다.

## [0.0.5] — 2026-09-29

**앱이 측위 provider 를 직접 만들어 엔진 상태를 지켜볼 수 있습니다 — iOS 0.1.24 와 같은 공개 표면입니다.**
API 를 더하기만 했고 기존 API·동작은 그대로입니다. 인자 없는 `begin()` 을 쓰는 앱은 고칠 것이 없습니다
([`Migrations/android.json`](Migrations/android.json) 0.0.4→0.0.5).

```kotlin
implementation("com.ones1ght.sdk:android:0.0.5")
```

### 추가

- **`UwbPositioningProvider(context)`** — 내장 측위 provider 를 앱이 만든다. 어느 OS 에서든 던지지 않는다
  (Android 17 미만이면 엔진 없이 만들어지고, 시작하면 `E2002` 로 끝난다).
- **`UwbPositioningProvider.isSupported(context)`** — 정적 판정. `initialize` 전에도 쓸 수 있고, 판정은
  `deviceAvailability == AVAILABLE` 과 같다(Android 17+ && UWB 칩).
- **`floorSession().begin(provider)` 에 `UwbPositioningProvider` 를 넣으면 `begin()` 과 똑같이 다룬다** —
  같은 기기 확인(`OsVersionTooLow`·`DeviceNotSupported`, 초기화 확인이 먼저), SDK 가 넣는 라이선스,
  구역 이벤트 → `onZoneEnter/Exit/Dwell`, 엔진 로그 → `OneS1ght.onDebugLog`. provider 에 앱이 단 훅은
  덮지 않는다. 다른 provider(Mock 등)는 종전처럼 기기 확인 없이 돈다.
- **관찰 상태** — `phase`(`PositioningPhase`: `IDLE`·`STARTING`·`SEARCHING`·`TRACKING`·`STOPPING`)·
  `isDetecting`·`isRunning`·`isPaused`·`latestPosition`·`detectedFloorId`·`measurementCount`·`log`(최근 200줄).
  게터와 `StateFlow`(`phaseFlow`·`latestPositionFlow` …) 둘 다 있고, Java 는 `setOnChange(…)` 로 변경 통지를 받는다.
- **훅** — `onFloorDetected`(층 번호 / `null`)·`onEngineError`(엔진 원본 번호·문장)·`onRawAreaEvent`
  (엔진 원본 영역 이벤트)·`onZoneEvent`(콘솔 구역으로 옮긴 진입·이탈·체류)·`onLog`, 그리고 `note(…)`
  (앱 로그를 같은 스트림에 합류).
- **진단** — `diagnostic`(`AnchorDiagnostic`: `registered`·`received`·`matched`·`missing`·`hasFix`·
  `canPosition`·`summary`). ⚠️ 측위 엔진은 앵커별 수신 상태를 주지 않는다 — `registered` 는 콘솔
  로케이터(`apply(config)`)이고, 좌표가 있으면 `received`·`matched` 가 `registered` 전부, 없으면 빈 목록이다.
  `missing` 은 **항상 비어 있다**(특정할 수 없는 것을 고장으로 칠하지 않는다). `positioningDiagnostic` 도
  그대로 `canAttributePerAnchor = false`.
- **엔진 기동 분리** — `startDetection()`(엔진만 띄워 층부터 찾기, 좌표는 측위를 켜기 전까지 버림)·
  `stopDetection()`(엔진까지 정지). iOS 와 같이 측위가 꺼진 상태의 `stop()` 은 엔진을 건드리지 않는다.
- `apply(buildingId, floorId)`·`apply(config)`·`start`·`stop`·`pause`·`resume`·`reloadGeofences` 는 원래
  `PositioningProvider` 로 공개돼 있던 그대로다.

### 바뀜

- `kotlinx-coroutines-core`(1.9.0)가 **api 의존**이 됐다 — 공개 `StateFlow` 때문이다. 고객 앱 컴파일
  클래스패스에 함께 올라간다(Kotlin 1.9+ · Java 8 호환 검사 통과).

### iOS 와 다른 점

- `license` 는 열지 않는다 — 측위 엔진 라이선스는 SDK 가 콘솔 값으로 넣는다(앱이 알 필요 없음).
  `startDetection()` 도 초기화된 SDK 의 값을 쓴다(초기화 전이면 `E1007`).
- iOS `@Published` 는 `StateFlow` + 게터 + `onChange` 로, `Date` 는 epoch 밀리초(`atMs`)로 옮겼다.
- `FloorSession.onPosition` 은 iOS 와 같이 좌표만 준다 — 층은 `provider.detectedFloorId` 로 본다.

## [0.0.4] — 2026-09-29

**Android 17 미만 앱에도 SDK 를 넣을 수 있습니다.** 패키지 `minSdk` 를 37 → **26**(Android 8.0)으로
낮췄습니다 — "설치는 넓게, 측위는 지원 OS 에서만"(iOS 의 패키지 iOS 18 · 측위 iOS 27 과 같은 모양).
공개 API 는 그대로이고 앱 코드는 고칠 것이 없습니다([`Migrations/android.json`](Migrations/android.json)
0.0.3→0.0.4).

### 바뀜

- **패키지 최소 사양 Android 8.0(API 26)** — 0.0.3 때문에 앱 `minSdk` 를 37 로 올렸다면 되돌려도 됩니다.
  ```kotlin
  implementation("com.ones1ght.sdk:android:0.0.4")
  ```
- **측위는 여전히 Android 17(API 37) 이상에서만** — 측위 엔진이 Android 17 의 `android.ranging` 을 쓰기
  때문입니다. 그 아래 OS 에서는 측위만 꺼지고 앱은 정상 동작합니다:
  `deviceAvailability` = `OS_VERSION_TOO_LOW` · `isDeviceAvailable` = `false` ·
  `permissions(activity)` = 팝업 없이 `UNSUPPORTED` · `floorSession().begin()` =
  `SdkError.OsVersionTooLow`(`E2001`). 초기화·공간 조회·프로필·전송은 모든 지원 OS 에서 동작합니다.
- **내부** — 엔진에 닿는 길(엔진 감싸개·UWB 칩 조회·내장 provider 생성)을 `SDK_INT` 게이트와
  `@RequiresApi(37)` 로 묶었습니다. Android 17 미만에서는 엔진 클래스가 로드되지 않습니다
  (API 26 · 35 에뮬레이터에서 확인).

## [0.0.3] — 2026-09-29

**측위 엔진을 통합 엔진으로 바꿨습니다 — iOS(0.1.23)와 같은 구조입니다.** 층 탐지(BLE)·UWB 측위·
구역 진출입 판정을 모두 엔진이 하고, SDK 는 그 결과를 서버 계약으로 옮깁니다. 공개 API 시그니처는
그대로지만 **앱 `minSdk` 를 37 로 올려야 합니다** — 코드 수정 방법은
[`Migrations/android.json`](Migrations/android.json) 0.0.2→0.0.3.

### Breaking

- **최소 사양 Android 17(API 37)** — 패키지 `minSdk` 27 → **37**. 측위 엔진의 최소 사양과 같습니다.
  앱 모듈 `minSdk` 가 37 미만이면 매니페스트 병합이 실패합니다.
  ```kotlin
  android { defaultConfig { minSdk = 37 } }
  implementation("com.ones1ght.sdk:android:0.0.3")
  ```

### 바뀜

- **층을 스스로 찾습니다** — 엔진이 BLE 로 층을 고릅니다. `setFloorMap` 은 이제 **선택**입니다:
  층 없이 `begin()` 해도 좌표가 나오고(서버로는 엔진이 찾은 층이 실립니다), `E3001` 은 정상 경로의
  WARN 입니다. 구역 이벤트(진입·이탈·시책)를 쓰면 `setFloorMap` 으로 층을 지정합니다 — 엔진은 구역을
  영역 **이름**으로 알려 주고, SDK 가 그 층의 콘솔 존 이름에 맞춰 zone id 로 옮깁니다.
- **구역 판정을 엔진이 합니다** — 엔진 자체 지오펜스로 판정합니다. 콘솔 존의 판정 파라미터
  (진입 거리·횟수 등)는 더 이상 쓰이지 않습니다(존이 있으면 한 번 WARN 으로 알립니다). 체류(DWELL)는
  종전처럼 `dwellSeconds` 도달 시 1회, 앱 콜백 전용입니다.
- **구역이 바뀌면 엔진이 다시 읽습니다** — `refreshZones()` 가 구역 집합이 바뀐 것을 보면 엔진을 잠깐
  재시작합니다(좌표가 잠깐 끊깁니다). 가동·일시정지 상태는 그대로입니다.
- **권한** — `permissions(activity)` 가 `BLUETOOTH_SCAN`(근처 기기) 도 함께 요청합니다. `RANGING` ·
  `ACCESS_FINE_LOCATION` · `BLUETOOTH_SCAN` 셋 다 허용이어야 `AUTHORIZED` 입니다. 라이브러리 매니페스트가
  `BLUETOOTH_SCAN` · `ACCESS_NETWORK_STATE` · `CHANGE_NETWORK_STATE` 를 더 병합합니다.
- **기기 판정** — `deviceAvailability` 는 이제 기다리지 않습니다(Android 17 이상 && UWB 칩 유무).
  칩은 있지만 DL-TDoA 를 못 하는 기기는 측위 시작 때 `E2002` 로 남습니다.
- **수신 진단** — 엔진이 로케이터별 수신 상태를 주지 않아, 로케이터 일부 미수신(`E4003`)은 더 이상
  특정하지 않습니다. 좌표가 끝내 안 나오면 `E4002` 가 남습니다.
- **의존** — 엔진이 쓰는 `gson 2.10.1` · `jts 1.13` · `androidx.core 1.10.1+` 가 런타임 의존에
  더해졌습니다(기존 `commons-math3` · `jts-core` · `slf4j-api` 유지). 고객 앱 Kotlin 1.9+ 호환은 그대로입니다.

### 새로 남는 로그 코드

코드 자체는 0.0.1 부터 있었고, 이번 판부터 실제로 올라갑니다(예외로 던지지는 않습니다).

- `E3007` 층 미탐지 — 측위를 켜고 20초 안에 BLE 로 층을 못 찾음(근처 기기 권한·블루투스·층 비콘 확인)
- `E3008` 엔진이 찾은 층 ≠ 앱이 `setFloorMap` 으로 지정한 층(층마다 한 번)
- `E3009` 엔진 영역 이름에 맞는 콘솔 존 없음 — 그 영역의 이벤트는 서버로 가지 않음(이름마다 한 번)
- `E4004` 엔진의 구역 판정 실패

### iOS 와 다른 점

0.0.1 에 적은 세 가지 중 **3번(층 지정 필수)이 없어졌습니다.** 남은 두 가지는 그대로입니다 —
`initialize` 의 `Context` 인자, `permissions(activity)` 의 `Activity` 인자.

## [0.0.2] — 2026-09-29

**Maven Central 첫 배포판입니다.** 공개 API·동작은 0.0.1 과 같고, 앱 코드는 고칠 것이 없습니다
— 의존성 좌표만 바꾸면 됩니다([`Migrations/android.json`](Migrations/android.json) 0.0.1→0.0.2).

### 바뀜

- **배포 좌표** — `co.onecheck.ones1ght:android` → **`com.ones1ght.sdk:android`**.
  Maven Central 에서 받습니다. `repositories` 에 `mavenCentral()` 만 있으면 되고,
  별도 저장소 주소는 더 이상 필요 없습니다. 패키지 이름(`co.onecheck.ones1ght.android`)은
  그대로라 `import` 는 바뀌지 않습니다.
  ```kotlin
  implementation("com.ones1ght.sdk:android:0.0.2")
  ```
- **실제 측위 엔진 내장** — 배포 AAR 에 측위 엔진이 들어 있습니다. 엔진을 받으려고 앱에
  저장소·의존성을 따로 추가하지 않습니다.
- **의존 버전 정렬** — 엔진이 쓰는 라이브러리를 엔진과 같은 판으로 맞췄습니다:
  `commons-math3 3.6.1` · `jts-core 1.18.2` · `slf4j-api 1.7.31`(런타임 의존).

### 더함

- **라이선스** — [`LICENSE`](LICENSE)(OneS1ght SDK License, 법무 검토 전 초안). POM 에도 실립니다.
- **소스·문서 jar** — Maven Central 판에 `-sources.jar` · `-javadoc.jar`(Dokka HTML)가 함께 올라갑니다.

## [0.0.1] — 2026-09-28

**첫 공개판입니다.** iOS SDK(v0.1.23 기준)와 같은 사양 — 같은 기능, 같은 서버 계약 —
으로 시작합니다.

### 더함

- **초기화·설정** — `OneS1ght.initialize(context, sdkKey, baseUrl)`, `reset()`,
  `isInitialized`, `deviceAvailability`, `isDeviceAvailable`, `googleMapKey`,
  `setLanguage(code)`, `onDebugLog`
- **권한** — `OneS1ght.permissions(activity)`
- **프로필** — `createProfile(attrs)` · `getProfile(id)` · `putProfile(id, attrs)` ·
  `deleteProfile(id)` · `identify(profileId)`
- **공간 조회** — `buildings()` · `building(id)` · `floors(buildingId)` ·
  `floor(b, f)` · `zones(b, f)` · `zone(b, f, z)` · `locators(b, f)`
- **층·측위** — `setFloorMap(floor, buildingId)` · `refreshZones()` · `floorSession()`
- **측위 세션** — `begin()` · `begin(provider)` · `pause()` · `resume()` · `end()`,
  콜백 `onZoneEnter` · `onZoneExit` · `onZoneDwell` · `onPosition` · `onTriggers` ·
  `onConfigChanged`
- **전송 버퍼** — `send()` · `empty()`
- **Java 상호운용** — 모든 비동기 공개 함수에 `Callback<T>` 판을 같은 이름으로 둔다.
  이벤트 콜백은 `fun interface`(`ZoneListener` 등)라 Java 람다로도 자연스럽게 쓴다.

### iOS 와 다른 점

안드로이드는 **iOS 와 같은 사양**을 목표로 하지만, 플랫폼이 강제하는 세 가지는 다릅니다.

1. **`initialize` 에 `Context` 인자가 추가됩니다.** `SharedPreferences`, 앱 id(`packageName`),
   생명주기 관찰에 안드로이드 `Context` 가 필요합니다 — iOS 의
   `initialize(sdkKey:baseURL:)` 에는 없던 인자입니다. SDK 는 `applicationContext` 만
   보관합니다.
2. **`permissions()` 가 `Activity` 를 받습니다.** iOS 는 인자 없이 시스템 팝업을
   띄웠지만, 안드로이드는 `ActivityResultRegistry` 로 요청을 등록해야 해서
   `permissions(activity: ComponentActivity)` 형태입니다. 요청 권한도
   `RANGING` + `ACCESS_FINE_LOCATION` 두 가지를 한 번에 받습니다.
3. **`setFloorMap` 으로 층을 지정하는 것이 필수입니다.** iOS 는 로케이터가 BLE 로
   층을 광고해 엔진이 스스로 찾아내지만(`onFloorDetected`), 안드로이드에는 그 경로가
   없습니다. 층을 지정하지 않고 `begin()` 하면 파이프라인은 돌아도 좌표가 나오지
   않습니다(`E3001`, WARN).

그 밖의 이름·인자 순서·기본값·동작·오류 코드 26개·서버 계약은 iOS 와 같습니다.

[0.0.3]: https://github.com/onecheck-inc/OneS1ght-Android-SDK/releases/tag/v0.0.3
[0.0.2]: https://github.com/onecheck-inc/OneS1ght-Android-SDK/releases/tag/v0.0.2
[0.0.1]: https://github.com/onecheck-inc/OneS1ght-Android-SDK/releases/tag/v0.0.1
