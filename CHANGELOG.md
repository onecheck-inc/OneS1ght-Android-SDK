# Changelog

이 파일은 사람이 읽는 릴리스 기록입니다.
코딩 에이전트가 읽는 **버전 간 코드 수정 지침**은 [`Migrations/android.json`](Migrations/android.json)에 따로 있습니다.

버전은 [유의적 버전](https://semver.org/lang/ko/)을 따릅니다. `0.x` 동안은 마이너 판올림에도
깨지는 변경이 들어갈 수 있으며, 그때는 아래에 **Breaking** 으로 표시하고 마이그레이션 파일에
고치는 방법을 함께 싣습니다.

---

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
