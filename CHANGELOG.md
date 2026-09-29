# Changelog

이 파일은 사람이 읽는 릴리스 기록입니다.
코딩 에이전트가 읽는 **버전 간 코드 수정 지침**은 [`Migrations/android.json`](Migrations/android.json)에 따로 있습니다.

버전은 [유의적 버전](https://semver.org/lang/ko/)을 따릅니다. `0.x` 동안은 마이너 판올림에도
깨지는 변경이 들어갈 수 있으며, 그때는 아래에 **Breaking** 으로 표시하고 마이그레이션 파일에
고치는 방법을 함께 싣습니다.

---

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

[0.0.2]: https://github.com/onecheck-inc/OneS1ght-Android-SDK/releases/tag/v0.0.2
[0.0.1]: https://github.com/onecheck-inc/OneS1ght-Android-SDK/releases/tag/v0.0.1
