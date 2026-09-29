# OneS1ght SDK — Android (Kotlin)

[English](README.md) | **한국어** | [日本語](README.ja.md)

📖 문서: https://docs.ones1ght.com/sdk/integration/android

실내 위치 인텔리전스 SDK 입니다. 앱에 추가하면 UWB(DL-TDoA) 실내 측위로 방문·동선
데이터를 수집하고, 구역 진입·이탈·체류 이벤트를 기기에서 직접 받을 수 있습니다.
서버 계약과 측위 구조(엔진이 BLE 로 층을 찾고 구역도 직접 판정)는 iOS SDK 와 같고, 공개 API 도
(두 가지 플랫폼상 불가피한 차이를 빼면, [CHANGELOG](CHANGELOG.md) 참고) 동일합니다.

---

## 요구사항

| 항목 | 요구사항 |
|---|---|
| 측위 동작 | **Android 17 (API 37)+** · UWB **DL-TDoA** 지원 기기 · Bluetooth LE(층 탐지) |
| 패키지 추가 | **Android 8.0 (API 26)+** (`minSdk 26`). Android 17 미만이거나 UWB 가 없는 기기에서도 앱은 정상 동작하고 측위만 비활성(`OS_VERSION_TOO_LOW` / `E2001`) |
| 빌드 환경 | `compileSdk` / `targetSdk` 37, JVM target 17 |
| 언어 | Java 8+ / Kotlin 1.9+ 앱에서 사용 가능 |

SDK가 실제로 동작하려면 키와 공간 설정이 먼저 준비되어야 합니다.

| 사전 준비 | 어디서 |
|---|---|
| SDK 키 (`ock_sdk_…`) | OneS1ght 콘솔 → **모바일 SDK** |
| 건물·층·로케이터 설치 | 통합관리자 (설치 시 함께 진행) |
| 구역(Zone) | OneS1ght 콘솔 → **공간 관리** |

---

## Step 1: 프로젝트 설정

앱 모듈의 `build.gradle.kts` 에 의존성을 추가합니다.

```kotlin
dependencies {
    implementation("com.ones1ght.sdk:android:0.0.5")
}
```

SDK 는 **Maven Central** 에 배포됩니다 — 저장소를 따로 추가할 필요가 없습니다. 새 안드로이드
프로젝트에는 이미 들어 있고, 없다면 `settings.gradle.kts` 에 다음이 있는지 확인하세요.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

앱 모듈의 `minSdk` 는 **26 이상**이면 됩니다. 측위 자체는 Android 17(API 37) 이상에서만 동작합니다 —
그 아래에서는 `deviceAvailability` 가 `OS_VERSION_TOO_LOW`, `permissions(activity)` 가 팝업 없이
`UNSUPPORTED`, `begin()` 이 `SdkError.OsVersionTooLow`(`E2001`)입니다. 초기화·공간 조회·프로필 등
나머지는 모든 지원 OS 에서 동작합니다.

> 0.0.4 에서 올라오나요? 고칠 것이 없습니다 — 0.0.5 는 API 를 더하기만 했습니다(앱이 측위 provider 를 직접
> 만들어 상태를 지켜볼 수 있게 됨 — [CHANGELOG](CHANGELOG.md)).
> 0.0.3 에서 올라오나요? 고칠 것이 없습니다 — 앱 `minSdk` 를 다시 낮춰도 됩니다(26 이상).
> 0.0.2 에서 올라오나요? `permissions(activity)` 가 `BLUETOOTH_SCAN`
> 도 함께 요청하고, `setFloorMap` 은 선택이 됐습니다 — [CHANGELOG](CHANGELOG.md) 참고.
> 0.0.1 에서 올라오나요? 좌표도 바뀌었습니다(`co.onecheck.ones1ght:android` →
> `com.ones1ght.sdk:android`). 패키지 이름은 같습니다.

라이브러리 자체 매니페스트가 `RANGING` · `BLUETOOTH_SCAN` · `ACCESS_FINE_LOCATION` ·
`ACCESS_COARSE_LOCATION` · `INTERNET` · `ACCESS_NETWORK_STATE` · `CHANGE_NETWORK_STATE` 권한을
앱에 자동으로 병합합니다. 앱에서 따로 선언할 필요가 없습니다.

---

## Step 2: SDK 초기화

앱 시작 시 1회 호출합니다. 키를 검증하고, 백엔드 도달 여부를 확인하고, 테넌트 설정을
받아옵니다.

```kotlin
import co.onecheck.ones1ght.android.OneS1ght

try {
    OneS1ght.initialize(
        context = applicationContext,
        sdkKey = "ock_sdk_…",
    )
} catch (e: Exception) {
    // E1002(키 무효) · E1003(측위 비활성) · E5001(네트워크) 등
}
```

```java
// Java
OneS1ght.initialize(context, "ock_sdk_…", new Callback<Void>() {
    @Override public void onSuccess(Void result) { /* 초기화 완료 */ }
    @Override public void onError(Throwable error) { /* E1002 · E1003 · E5001 */ }
});
```

> 넣는 키는 이것 하나뿐입니다. 측위와 지도에 필요한 나머지 키는 **콘솔이 내려줍니다** —
> 앱에 심을 필요가 없고, 값을 바꿔도 앱을 다시 배포하지 않아도 됩니다. 통합관리자가
> 콘솔에서 설정합니다.

⚠️ `initialize` 는 건물·층을 **조회하지 않습니다.** 공간 선택은 별도 단계(Step 5)입니다 —
어느 층을 쓸지는 앱만 알기 때문입니다.

**예상 로그**

```
[I1001] Initialized — tenant=itoku
```

### 기기 지원 여부 먼저 확인

```kotlin
when (OneS1ght.deviceAvailability) {
    DeviceAvailability.AVAILABLE            -> { }
    DeviceAvailability.OS_VERSION_TOO_LOW   -> showNotice("Android 17 이상이 필요합니다")
    DeviceAvailability.DEVICE_NOT_SUPPORTED -> showNotice("이 기기는 UWB 를 지원하지 않습니다")
}
```

throw 하지 않고 네트워크도 타지 않으며 기다리지도 않습니다. **`initialize` 다음에** 읽으세요 —
UWB 확인에 `initialize`(또는 `permissions(activity)`)가 넘겨주는 앱 Context 가 필요합니다.
그 전에 읽으면 판단할 수 없어 `DEVICE_NOT_SUPPORTED` 를 돌려줍니다(`onDebugLog` 에 WARN 이
남습니다).

기기에 UWB 칩이 있는지를 봅니다. 칩은 있지만 DL-TDoA 를 지원하지 않는 드문 기기는 측위를
시작할 때 걸러지고 `E2002` 로 남습니다.

---

## Step 3: 권한

안드로이드는 측위에 필요한 권한을 한 번에 요청합니다 — iOS 처럼 위치 권한을 앞서
따로 받는 별도 단계가 없습니다.

```kotlin
when (OneS1ght.permissions(activity)) {
    PermissionStatus.AUTHORIZED  -> { /* 측위 시작 가능 */ }
    PermissionStatus.DENIED      -> showSettingsGuide()      // 재요청 불가 — 설정 앱으로 안내
    PermissionStatus.UNSUPPORTED -> showUnsupportedNotice()
}
```

```java
// Java
OneS1ght.permissions(activity, new Callback<PermissionStatus>() {
    @Override public void onSuccess(PermissionStatus status) {
        if (status == PermissionStatus.AUTHORIZED) { /* 측위 시작 가능 */ }
    }
    @Override public void onError(Throwable error) { }
});
```

`permissions(activity)` 는 `ActivityResultRegistry` 로 `RANGING`(UWB) + `ACCESS_FINE_LOCATION`
+ `BLUETOOTH_SCAN`(근처 기기 — 엔진이 BLE 로 층을 찾습니다)을 함께 요청하므로, `onCreate` 이후
아무 때나 불러도 안전합니다. `ACCESS_COARSE_LOCATION` 도 같이 요청합니다 — Android 12 이상은
대략 위치를 함께 요청해야 정밀 위치를 고를 수 있게 해 주기 때문입니다.

⚠️ 측위에는 **정밀** 위치와 **근처 기기** 권한이 필요합니다. 사용자가 "대략적인 위치"를
고르거나 근처 기기를 거부하면 결과는 `DENIED` 입니다.

⚠️ `deviceAvailability != AVAILABLE` 이면 시스템 팝업 없이 곧바로 `UNSUPPORTED` 를
돌려줍니다. 셋 다 이미 허용돼 있으면 팝업 없이 `AUTHORIZED` 를 돌려줍니다. 그 외에는
30초 안에 응답이 없으면 `DENIED` 로 확정됩니다.

⚠️ 한 번 거부되면 시스템이 다시 팝업을 띄워 주지 않습니다 — 앱 설정 화면으로
안내하세요.

---

## Step 4: 프로필

서버가 `profileId` 를 발급합니다. **앱이 보관해 재사용해야 합니다** — 방문·동선
데이터가 이 키로 귀속됩니다.

```kotlin
// 최초 1회 — 발급받아 앱에 저장(SharedPreferences 등)
val profileId: String = savedProfileId ?: OneS1ght.createProfile(
    mapOf(
        "gender" to "F",
        "ageBand" to "20s",       // 정확한 나이가 아니라 연령대
    ),
)

// 매 실행 — 저장해 둔 값을 연결
OneS1ght.identify(profileId)
```

고객사 회원 ID는 OneS1ght에 오지 않습니다. `profileId` 만 오고, 그 매핑은 고객사만
보관합니다.

⚠️ 나이는 **연령대**로 넣기를 권합니다. 성별 + 정확한 나이 + 관심사 + 동선이 조합되면
재식별 가능성이 생깁니다.

⚠️ `identify` 는 `initialize` 뒤에 불러야 합니다. 더 먼저 부르면 값이 반영되지
않습니다.

| 함수 | 용도 |
|---|---|
| `createProfile(attrs)` | 생성 — `profileId` 반환 |
| `getProfile(id)` | 조회 |
| `putProfile(id, attrs)` | 속성 전체 교체 |
| `deleteProfile(id)` | 삭제 |
| `identify(profileId)` | 연결 — 측위 전에 필수 |

---

## Step 5: 공간 선택 (선택)

```kotlin
val buildings = OneS1ght.buildings()
val floors = OneS1ght.floors(buildings[0].id)

OneS1ght.setFloorMap(floors[0], buildingId = buildings[0].id)
```

측위 엔진은 iOS 와 같이 BLE 로 층을 스스로 찾습니다 — 층 없이 `begin()` 해도 좌표가
나옵니다(`E3001` WARN 이 한 번 남습니다. 정상 경로입니다). 20초 안에 층을 못 찾으면
`E3007` 이 남습니다.

`setFloorMap` 은 그 층의 로케이터·UWB 세션 ID·존을 받습니다. **구역 이벤트**를 쓰면 부르세요 —
엔진은 구역 진입·이탈을 영역 **이름**으로 알려주고, SDK 는 그 이름을 지정한 층의 콘솔 존에
맞춰 서버로 보낼 zone id 를 얻습니다(맞는 이름이 없으면 `E3009`). 엔진이 찾은 층이 지정한 층과
다르면 `E3008` 이 남습니다. 실행 중에 다시 호출하면 층이 전환되고 세션은 유지됩니다.

### 지도 그리기

```kotlin
// floors() 는 목록을 가볍게 유지하려고 image 가 비어 있습니다.
// 그릴 층 하나만 다시 조회합니다 — 캐시에서 나오므로 추가 요청이 없습니다.
val floor = OneS1ght.floor(buildingId, floorId)

mapView.setBackground(
    floor.image,          // ByteArray? — PNG 바이트
    minX = floor.minX, minY = floor.minY,
    maxX = floor.maxX, maxY = floor.maxY,
)
```

**예상 로그**

```
[I3001] Floor set — building=B1 floor=9f3a1c2e locators=4 zones=3
```

빠진 것이 있으면 코드가 대신 나옵니다.

```
[E3003] No UWB session on floor — floor=9f3a1c2e
```

---

## Step 6: 측위 시작

```kotlin
val session = OneS1ght.floorSession()

session.onZoneEnter = ZoneListener { zone -> showCoupon(zone) }
session.onZoneExit  = ZoneListener { zone -> hideCoupon(zone) }
session.onZoneDwell = DwellListener { zone, seconds -> logDwell(zone, seconds) }
session.onPosition  = PositionListener { coord -> mapView.moveMarker(coord) }
session.onTriggers  = TriggersListener { zoneId, triggers -> handle(triggers) }

session.begin()
…
OneS1ght.floorSession().end()
```

```java
// Java
FloorSession session = OneS1ght.floorSession();
session.setOnPosition(coord -> mapView.moveMarker(coord));
session.setOnZoneEnter(zone -> showCoupon(zone));

session.begin(new Callback<Void>() {
    @Override public void onSuccess(Void result) { /* 측위 시작 */ }
    @Override public void onError(Throwable error) {
        // SdkError 하위 클래스 — instanceof 로 분기
    }
});
```

`floorSession()` 은 항상 같은 인스턴스를 돌려줍니다 — UWB 라디오·측위 엔진·좌표
버퍼가 기기당 하나뿐이라 세션이 여럿이면 물리적으로 충돌합니다.

⚠️ **`begin()` 은 권한을 받은 뒤에 부르세요.** 권한 없이 부르면 `begin()` 은 던지지
않습니다 — 세션은 시작되지만 좌표가 나오지 않고 `E2003` 이 남습니다. 그 세션이 도는 동안
다시 부른 `begin()` 은 무시되므로, 사용자가 권한을 허용한 뒤에는 먼저 `end()` 를 부르고
`begin()` 을 다시 부르세요.

ℹ️ `begin(provider)`(테스트·데모용 커스텀·Mock 측위 소스)는 좌표만 공급합니다.
`onZoneEnter` · `onZoneExit` · `onZoneDwell` 은 SDK 내장 측위(`begin()`)에서만 오며, 커스텀
provider 로는 발화하지 않습니다.

ℹ️ **측위 엔진 상태를 직접 지켜보기(0.0.5~).** 지도 화면처럼 엔진 상태가 필요하면 내장 provider 를
직접 만들어 넣을 수 있습니다 — `begin()` 과 똑같이 다뤄지고(같은 기기 확인·구역 리스너·디버그 로그),
provider 에 단 앱 훅은 그대로 둡니다:

```kotlin
if (UwbPositioningProvider.isSupported(context)) {
    val provider = UwbPositioningProvider(context)
    provider.onFloorDetected = FloorDetectedListener { floorId -> /* 층 자동 선택 */ }
    OneS1ght.floorSession().begin(provider)
    provider.latestPositionFlow.collect { position -> /* 내 위치 그리기 */ }
}
```

`phase` · `isRunning` · `isPaused` · `latestPosition` · `detectedFloorId` · `measurementCount` · `log` 는
게터와 `StateFlow`(`…Flow`) 둘 다로 제공됩니다. Java 앱은 게터와 `setOnChange(…)` 를 씁니다.

### 일시정지는 종료가 아닙니다

```kotlin
session.pause()      // 좌표 표시·전송·구역 판정만 멈춘다 (엔진은 계속 돈다)
session.resume()     // 즉시 이어진다 — 로케이터를 다시 찾지 않는다
session.isPaused
```

| | `pause()` | `end()` |
|---|---|---|
| 위치 콜백 | 멈춤 | 멈춤 |
| 구역 진입/이탈 | 멈춤 | 멈춤 |
| 서버 전송 | 멈춤 | 잔여 전송 후 멈춤 |
| 엔진·층·로케이터 | **유지** | 해제 |
| 복귀 비용 | 즉시 | 층과 로케이터를 처음부터 다시 찾음 |

"잠깐 내 위치 표시를 끈다"에는 `pause()` 를, 공간을 떠날 때는 `end()` 를 씁니다.
재개하면 판정 상태가 초기화되므로, `resume()` 뒤 첫 구역 이벤트는 현재 위치를 새로
확정합니다.

**예상 로그**

```
[I4001] Positioning started — visitor=v-20260928-001
[I4002] Positioning ended — visitor=v-20260928-001 points=240
```

---

## 주요 API

| 구분 | API |
|---|---|
| 초기화 | `initialize(context, sdkKey, baseUrl)` · `permissions(activity)` · `reset()` |
| 프로필 | `createProfile(attrs)` · `getProfile(id)` · `putProfile(id, attrs)` · `deleteProfile(id)` · `identify(profileId)` |
| 공간 조회 | `buildings()` · `building(id)` · `floors(buildingId)` · `floor(b, f)` · `zones(b, f)` · `zone(b, f, z)` · `locators(b, f)` |
| 층 지정 | `setFloorMap(floor, buildingId)` · `refreshZones()` |
| 측위 | `floorSession()` → `begin()` · `end()` · `pause()` · `resume()` · `isPaused` |
| 세션 콜백 | `onZoneEnter` · `onZoneExit` · `onZoneDwell` · `onPosition` · `onTriggers` · `onConfigChanged` |
| 버퍼 | `send()`(전송) · `empty()`(폐기) |
| 조회 | `isInitialized` · `isDeviceAvailable` · `deviceAvailability` · `onDebugLog` · `setLanguage(code)` · `SDK_VERSION` |
| 콘솔 제공 값 | `googleMapKey` |

⚠️ `empty()` 는 쌓인 좌표를 **전송하지 않고 버립니다.** 전송은 `send()` 입니다.

⚠️ 앱이 직접 쓰는 콘솔 값은 `googleMapKey` 하나입니다. 측위 라이선스·공간 서비스
주소는 SDK 가 내부에서만 쓰므로 밖으로 내주지 않습니다.

### Java 와 Kotlin

비동기 공개 함수는 모두 같은 이름으로 두 벌입니다.

- Kotlin: `suspend fun foo(...): T` — 코루틴에서 부릅니다.
- Java: `fun foo(..., callback: Callback<T>)` — 콜백은 메인 스레드에서 호출됩니다.
  반환값이 없으면 `Callback<Void?>`(Java 에서는 `Callback<Void>`)를 써서
  `Unit.INSTANCE` 를 다룰 일이 없습니다.

ℹ️ Java 콜백 판을 메인 스레드에서 불렀고 그 호출이 기다릴 것 없이 끝나면(예: 곧바로 나는
`SdkError.NotInitialized`) 콜백이 **호출이 돌아오기 전에** 불릴 수 있습니다. 호출 다음 줄이
먼저 실행된다고 가정하지 마세요.

이벤트 콜백(`onZoneEnter`, `onPosition` 등)은 `fun interface` 라 Kotlin SAM 변환
(`ZoneListener { zone -> … }`)과 Java 람다(`session.setOnZoneEnter(zone -> …)`) 모두
자연스럽게 씁니다. `SdkError`·`ApiError` 는 `Exception` 하위 클래스라 Java 에서
`instanceof` 로 분기할 수 있습니다.

---

## 부록

### 데이터가 흐르는 경로

```
initialize ─→ begin ─→ [UWB 좌표] ─┬─→ onPosition            (앱)
                                    ├─→ 버퍼 → 서버          (배치)
                                    └─→ 구역 판정 ─┬─→ onZoneEnter/Exit
                                                   └─→ 서버 → onTriggers
```

`onZoneEnter` 는 온디바이스 판정 즉시 발화합니다. `onTriggers` 는 서버 응답 후에
도착하므로, 네트워크가 끊기면 앞의 것만 오고 뒤는 오지 않습니다.

### 배치 정책

| 트리거 | 값 |
|---|---|
| 건수 | 300건 |
| 주기 | 60초 |
| 백그라운드 전환 | 측위 정지 + 잔여 전송 |
| `end()` | 잔여 전송 |

⚠️ 안드로이드의 UWB 도 **포그라운드 전용**입니다. 백그라운드에서는 측위가 멈추고
복귀 시 재개됩니다 — iOS 와 같은 플랫폼 제약입니다.

⚠️ 버퍼는 인메모리입니다. 앱이 강제 종료되면 미전송 좌표는 유실됩니다.

---

## 트러블슈팅

모든 실패에는 코드가 붙습니다. 문의 시 함께 알려주세요.

| 증상 | 코드 | 첫 확인 |
|---|---|---|
| 앱은 도는데 좌표가 안 나온다 | `E3007` · `E2003` · `E4002` | BLE 로 층을 찾았는지(근처 기기 허용·블루투스 켬) → 로케이터 배치 |
| 존 이벤트가 안 뜬다 | `E3009` · `E3004` | 층 지정(`setFloorMap`) 여부, 콘솔 존 이름이 현장 영역과 맞는지 |
| 데이터가 다른 층에 쌓인다 | `E3008` | 앱에서 지정한 층과 엔진이 찾은 층 |
| 특정 기기에서만 안 된다 | `E2001` · `E2002` | Android 17 / UWB DL-TDoA 지원 기기인지 |
| 권한 팝업이 다시 안 뜬다 | `E2003` | 이미 거부됨 — 설정 앱 유도 |
| 연동 직후 401 | `E1002` | 키 상태·환경(production/development) |
| 콘솔에 데이터가 안 보인다 | `E5001` · `E5006` | 네트워크 → 배치 주기 |

| 코드 | 의미 |
|---|---|
| `E1001` | SDK 미초기화 |
| `E1002` | SDK 키 무효 또는 폐기 |
| `E1003` | 테넌트에서 측위 비활성 |
| `E1004` | 프로필 미연결 |
| `E1007` | 콘솔에서 측위 키를 못 구함 |
| `E2001` | Android 버전 미달 |
| `E2002` | UWB 미지원 기기 |
| `E2003` | 측위 권한 거부 |
| `E3001` | 층 미지정 — **WARN, 정상: 엔진이 BLE 로 층을 찾는다** |
| `E3002` | 층에 로케이터 없음 |
| `E3003` | 층에 UWB 세션 없음 |
| `E3004` | 층에 존 없음 |
| `E3006` | 로케이터 조회 실패 (지도는 정상) |
| `E3007` | 20초 안에 BLE 로 층을 못 찾음 — 기기가 BLE 스캔 시작 횟수를 제한할 때도 남음(문맥 `engine=13`, 잠시 뒤 다시 시작하면 풀림) |
| `E3008` | 엔진이 찾은 층이 앱에서 지정한 층과 다름 |
| `E3009` | 엔진 영역 이름에 맞는 콘솔 존이 없음 — 이벤트를 보내지 않음 |
| `E4001` | UWB 세션 실패 |
| `E4002` | 좌표 미산출 |
| `E4003` | 로케이터 일부 미수신 — **WARN, 측위는 계속됩니다** |
| `E4004` | 측위 엔진의 구역 판정 실패 |
| `E5001` | 네트워크 실패 |
| `E5002` | 서버 오류 |
| `E5003` | 요청 형식 불일치 |
| `E5004` | 권한 없는 자원 접근 |
| `E5005` | 응답 해석 실패 |
| `E5006` | 미전송 좌표 유실 |

에러는 콘솔 로그 분석기로도 올라가므로, 테넌트 관리자가 앱을 거치지 않고 확인할 수
있습니다.

### 개발 중 SDK 로그 보기

```kotlin
OneS1ght.onDebugLog = DebugLogListener { level, message -> Log.d("OneS1ght", "[$level] $message") }
```

⚠️ 운영에서는 등록하지 않는 것을 권합니다.

---

## 라이선스

OneS1ght SDK 는 OneCheck Inc. 와 서비스 계약을 맺은 OneS1ght 고객에게 사용이 허락되는
독점 소프트웨어입니다. 전체 조건은 [LICENSE](LICENSE) 를 보세요. SDK 에 포함되거나 SDK 가 쓰는
제3자 구성요소는 각자의 라이선스를 따릅니다.

---

## 문의

onesight-support@onecheck.co.kr
