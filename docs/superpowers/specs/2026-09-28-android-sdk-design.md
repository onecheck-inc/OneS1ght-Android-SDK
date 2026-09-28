# OneS1ght Android SDK 0.0.1 — 설계

- 작성: 2026-09-28
- 기준 사양: OneS1ght iOS SDK v0.1.23 (`onecheck-inc/OneS1ght-iOS-SDK` @ `da2abcb`, 이하 "iOS")
- 엔진 구조 참고: iOS `b804f3b` (gpi-dltdoa 시절 마지막 커밋)
- 측위 엔진: Geoplan `gpa-dltdoa` 2.1.0 (`kr.geoplan.android.lib:gpa-dltdoa`, Nexus 배포)

> 이 문서는 개발 내부 문서다. 고객이 읽는 README·Snippets·로그에는 공급사 이름을 쓰지 않는다(§9).

---

## 1. 목표와 범위

**목표**: iOS SDK 와 **같은 사양**을 가진 안드로이드 SDK. 사양이 같다는 것은 다음을 뜻한다.

- 공개 API 의 이름·인자·동작이 같다. 안드로이드라서 달라져야 하는 곳은 §3.1 에 따로 적는다.
- 서버 계약이 바이트 단위로 같다: 경로, 메서드, 헤더, JSON 필드 이름, null 생략 규칙.
- 오류·정보 코드(E/I)의 번호, 등급, 발생 조건이 같다.
- 수명주기 규칙(0.1.20~0.1.23 에서 고친 것 포함)이 같다.
- 연동 산출물을 같은 형식으로 둔다: README(ko/en/ja), `Snippets/android.json`, `Migrations/android.json`, CHANGELOG, 릴리스 스크립트.

**이번 범위 (0.0.1)**

- 라이브러리 코드, JVM 단위 테스트, 문서·스니펫·마이그레이션, 릴리스 스크립트.
- CI 워크플로 파일은 두되 **트리거를 꺼 둔다**. GitHub Actions 무료 한도의 90% 를 이미 썼다.

**범위 밖**

- 배포 채널 결정: 레포가 private 이라 JitPack·GitHub Packages 등을 따로 정해야 한다.
- 태그·릴리스: SDK 배포 보류 중이다.
- 콘솔 MCP 에서 android 를 켜는 작업: `SdkCodeCatalog.AVAILABLE`, `codes-android.json`.
- UWB 실기기 검증: 기기가 없다(§10).

## 2. iOS 와 엔진이 다르다 — 핵심 결정

iOS 가 쓰는 `gpi-ihub` 는 엔진 하나가 다음을 모두 한다.

- BLE 로 층 인식
- 자기 서버에서 앵커·지오펜스 조회
- UWB 세션 구동
- 좌표 산출
- 구역 IN/OUT 판정

반면 안드로이드 `gpa-dltdoa` 는 **"DL-TDoA 측정 블록 → 좌표" 계산만** 한다. Geoplan 에 안드로이드용 ihub·prm 은 없다(2026-09-28 org 레포 목록으로 확인).

따라서 안드로이드 SDK 는 **iOS `b804f3b` 의 엔진 주변 구조를 되살리고**, 그 위에 **현재 iOS 의 공개 API 와 수명주기 규칙**을 얹는다.

| 책임 | iOS 현재 (ihub) | Android (이 설계) |
|---|---|---|
| UWB 세션 | 엔진 | SDK — `android.ranging` `RangingSession` |
| 앵커 좌표·세션 번호 | 엔진이 자기 서버에서 받음 | SDK — 공간 서비스 `api/m/floors/{f}/anchors` |
| 층 선택 | BLE 자동 | 앱이 `setFloorMap` 으로 지정 (필수) |
| 좌표 계산 | 엔진 | gpa-dltdoa `DlTdoaPositioner` |
| 구역 IN/OUT | 엔진 (이름으로 보고) | SDK — `ZoneEngine` 코틀린 이식 (§6) |
| DWELL | SDK (1회) | SDK (1회), 같은 규칙 |
| 앵커별 수신 진단 | 불가 (`canAttributePerAnchor=false`) | 가능 → E4003 부활 |

## 3. 공개 API

패키지는 `co.onecheck.ones1ght.android`다(iOS 의 SDK 식별자 `co.onecheck.ones1ght.sdk` 에서 끝만 바꿨다). 모든 공개 콜백은 **메인 스레드**에서 호출한다. iOS 의 `async` 함수는 `suspend fun` 으로 옮긴다.

### 3.0 Java · Kotlin 동시 지원

고객 앱이 Java 일 수도, Kotlin 일 수도 있으므로 공개 API 는 **양쪽에서 자연스럽게** 불려야 한다.

- **비동기 함수**: Kotlin 용 `suspend fun foo(...)` 와 Java 용 `fun foo(..., callback: Callback<T>)` 를 **같은 이름으로 두 벌** 제공한다.
  - `interface Callback<T> { fun onSuccess(result: T); fun onError(error: Throwable) }` 형태이고, 콜백은 메인 스레드에서 호출한다.
  - 반환값이 없으면 `Callback<Void?>` 를 쓴다(Java 에서 `Unit.INSTANCE` 를 다루지 않게).
  - Java 판은 SDK 내부 스코프(`SupervisorJob + Dispatchers.Main`)에서 suspend 판을 실행한다.
- **이벤트 콜백**: 함수 타입(`(Zone) -> Unit`) 대신 **`fun interface`** 로 둔다. 예: `ZoneListener`, `PositionListener`, `TriggersListener`, `ConfigChangeListener`, `DwellListener`, `DebugLogListener`.
  - Kotlin 에서는 SAM 변환(`session.onZoneEnter = ZoneListener { z -> ... }`)으로, Java 에서는 `session.setOnZoneEnter(z -> ...)` 로 쓴다.
- **정적 멤버**: `object OneS1ght` 의 멤버에 `@JvmStatic` 을 붙인다. 이렇게 하면 Java 에서 `OneS1ght.buildings(cb)` 로 부를 수 있다. 상수에는 `const`/`@JvmField` 를 쓴다.
- **기본 인자**: Java 용 기본 인자는 **Callback 판을 오버로드로 명시**해 제공한다(예: `initialize(ctx, key, cb)` · `initialize(ctx, key, baseUrl, cb)`, `setFloorMap(floor, cb)` · `setFloorMap(floor, buildingId, cb)`). suspend 판은 `@JvmSynthetic` 이라 Java 에 안 보이므로 `@JvmOverloads` 를 붙이지 않고 Kotlin 기본 인자만 쓴다. 생성자·모델 등 suspend 가 아닌 곳은 `@JvmOverloads` 를 쓴다.
- **오류**: `SdkError`/`ApiError` 는 `Exception` 하위 클래스라 Java 에서 `instanceof` 로 구분할 수 있다. Java 판에서는 이 예외를 `onError` 로 전달한다.
- **모델**: `data class` 로 두고, Java 에서는 getter 로 읽는다. 컬렉션은 읽기 전용 `List`/`Map` 이다.
- **검증**: `src/test/java/.../JavaInteropTest.java` 가 Java 로 initialize → identify → buildings → setFloorMap → floorSession().begin → 콜백 설정 → end 를 컴파일하고 실행한다.
- **표면 가드**: `JavaApiSurfaceTest` 가 컴파일된 공개 클래스를 훑어 (a) 매개변수가 같고 끝에 Callback 이 붙은 판이 없는 suspend(오버로드마다) (b) 람다 타입(`FunctionN`) (c) `Unit` 반환·`Callback<Unit>` (d) static 아닌 object 멤버 (e) api 가 아닌 의존 타입(androidx 는 api 인 androidx.activity 만)이 공개 시그니처에 나오는 것을 막는다. (b)~(e) 는 Java 가 보는 것 기준이라 Kotlin internal 이어도 JVM 에서 public 인 생성자·최상위 함수까지 본다 — 그런 건 private 생성자 + `@JvmSynthetic internal` 팩토리로 숨긴다. suspend 판은 `@JvmSynthetic` 으로 Java 에서 숨긴다.
- **고객 Kotlin 호환**: 언어·API 수준 2.0, `jvmDefault = ENABLE`, 자동 stdlib 의존 2.0.21. 런타임 의존도 stdlib 2.0 이하를 요구하는 판에 묶는다. `Scripts/consumer-compat-check.sh` 가 Kotlin 2.0.21·1.9.25 와 javac `--release 8` 로 실제 소비자 코드를 컴파일한다.

### 3.1 안드로이드라서 달라지는 곳 (전부)

| iOS | Android | 이유 |
|---|---|---|
| `initialize(sdkKey:baseURL:)` | `initialize(context: Context, sdkKey: String, baseUrl: String = DEFAULT_BASE_URL)` | SharedPreferences, 앱 id(packageName), 생명주기 관찰에 Context 가 필요하다. applicationContext 만 보관한다. |
| `permissions()` | `permissions(activity: ComponentActivity): PermissionStatus` | 시스템 팝업을 띄우려면 Activity 가 필요하다. 요청 권한은 `RANGING` + `ACCESS_FINE_LOCATION` 이다. `ActivityResultRegistry.register(key, …)` 를 쓰므로 onCreate 이후에 불러도 된다. 타임아웃 30초면 `denied`. |
| `Floor.image: Data?` | `Floor.image: ByteArray?` | 같은 PNG 바이트다. |
| `onZoneDwell: (Zone, TimeInterval)` | `onZoneDwell: ((Zone, Double) -> Unit)?` | 단위는 초. |
| `deviceAvailability` OS 기준 iOS 27 | API 37 (Android 17) | |
| `platform_name: "iOS"` | `"Android"` | 콘솔은 자유 문자열로 저장한다(`SdkRuntimeController`). |
| `ReqVerify.app_id` = bundleId | packageName | |

그 밖의 이름·인자 순서·기본값·동작은 iOS 와 같다.

### 3.2 `object OneS1ght`

iOS `Sources/OneS1ght/OneS1ght.swift` 의 멤버를 1:1 로 옮긴다.

- **상태·설정**: `SDK_VERSION`, `onDebugLog: ((LogLevel, String) -> Unit)?`, `isInitialized`, `deviceAvailability: DeviceAvailability { AVAILABLE, OS_VERSION_TOO_LOW, DEVICE_NOT_SUPPORTED }`, `isDeviceAvailable`, `googleMapKey`
- **초기화·권한·언어**: `permissions(activity)`, `setLanguage(code)`, `initialize(...)`, `reset()`
- **공간 조회**: `buildings()`, `building(id)`, `floors(buildingId)`, `floor(b, f)`, `zones(b, f)`, `zone(b, f, z)`, `locators(b, f)`
- **층·측위**: `setFloorMap(floor, buildingId = null)`, `refreshZones()`, `floorSession()`
- **프로필**: `createProfile(attrs)`, `getProfile(id)`, `putProfile(id, attrs)`, `deleteProfile(id)`, `identify(profileId)`
- **전송**: `send()`, `empty()`

iOS 동작상 주의점 — `identify` 를 initialize 보다 먼저 부르면 값이 전달되지 않는다 — 도 그대로 재현한다. 사양 동일성이 우선이다. 고칠 때는 두 플랫폼을 함께 고친다.

`deviceAvailability` 판정 순서:
1. `Build.VERSION.SDK_INT < 37` → `OS_VERSION_TOO_LOW`
2. `RangingManager` 가 없거나 DL-TDoA 를 지원하지 않음 → `DEVICE_NOT_SUPPORTED`
3. 그 외 → `AVAILABLE`

`initialize` 는 기기 조건으로 막지 않는다. 기기를 막는 곳은 `begin` 하나뿐이다(iOS SdkGateTests).

### 3.3 `class FloorSession` (싱글턴)

- **콜백**: `onZoneEnter`, `onZoneExit`, `onZoneDwell`, `onPosition(Coordinates)`, `onTriggers(zoneId, List<Trigger>)`, `onConfigChanged(ConfigChange)`
- **상태**: `floor`, `isRunning`, `isPaused`
- **제어**: `suspend begin()`, `suspend begin(provider)`, `pause()`, `resume()`, `suspend end()`
- `begin()` 순서:
  1. API 37 미만 → `SdkError.OsVersionTooLow`
  2. 기기 미지원 → `SdkError.DeviceNotSupported`
  3. 내장 UWB provider 를 재사용한다.
  4. `!isPrepared` 면 `prepare()`, 준비돼 있으면 키 재조회를 재시도한다.
  5. `coordinator.start(provider)` 를 부른다.

### 3.4 오류·모델

- **`sealed class SdkError : Exception`**: `NotInitialized`, `NotIdentified`, `PositioningDisabled`, `DeviceNotSupported`, `OsVersionTooLow`. 각각 `.code: SdkErrorCode` 를 가진다.
- **`sealed class ApiError : Exception`**: `InvalidKey(detail)`, `Forbidden`, `NotFound`, `Unprocessable`, `Server(status, detail)`, `Network(cause: IOException)`, `Decoding(detail)`. 코드 매핑은 iOS 와 같다(`NotFound` → E5003).
- **모델**: `Building`, `Floor`(`minX/minY/maxX/maxY` 계산 속성), `Locator`, `FloorLocators`(`positioningReady`), `Position`, `Zone`(판정 파라미터 기본값 포함, `contains()`), `ZoneEvent`(`id`·`label` 형식 동일), `Coordinates`, `Trigger`, `ConfigChange`, `PermissionStatus`, `LogLevel`(`LOG < INFO < WARN < ERROR`)
- **확장 지점**: `PositioningProvider` / `PositioningProviderDelegate` / `PositioningConfig` / `PositioningDiagnostic` 인터페이스, `MockPositioningProvider`
- **공개하는 내부 부품**: `ApiClient`, `IdentityStore` 는 iOS 처럼 public 으로 둔다.

## 4. 서버 계약

iOS 와 **바이트 단위로 같다**. 필드 목록의 정본은 iOS `Models/DTOs.swift`, `Networking/ApiClient.swift`, `SpaceService/SpaceServiceClient.swift` 이며, 테스트(§8)가 JSON 원문으로 대조한다.

### 4.1 공통 규칙

- **기본 base**: `https://console.ones1ght.com/api/sdk/v1`
- **헤더**: `X-SDK-Key: <키 원문>`, `Content-Type: application/json`. SDK 버전 헤더는 없다.
- **타임아웃**: 콘솔 10초, 공간 서비스 20초
- **직렬화**: kotlinx.serialization. `explicitNulls = false` 로 null 필드는 키째 생략하고, `ignoreUnknownKeys = true`, 필드 이름은 `@SerialName` 으로 snake_case 를 명시한다.
- **상태 코드**: 401 → InvalidKey, 403 → Forbidden, 404 → NotFound, 422 → Unprocessable, 그 외 → Server. 오류 본문 `{"detail": "..."}` 에서 detail 을 뽑는다.
- **시각**: ISO-8601 UTC 밀리초. 예: `2026-07-18T08:00:00.123Z`
- **관대한 디코딩**: `remote_config` 와 `Trigger.payload` 는 LenientStringMap 으로 읽는다. 스칼라는 문자열로 바꾸고, 중첩 객체·배열은 건너뛴다.

### 4.2 엔드포인트

**콘솔 (`X-SDK-Key` = SDK 키)**

| 메서드 | 경로 | 비고 |
|---|---|---|
| POST | `/auth/verify` | 본문 `{"platform_name":"Android","app_id":"<pkg>"}`. `client` 는 생략(iOS 와 같음) |
| GET | `/config` | `geo_sdk_key`(공간 서비스 키), `google_map_key` 등 |
| GET | `/positioning/buildings` | `sim-` 로 시작하는 건물은 제외. 비면 공간 서비스로 폴백 |
| GET | `/positioning/buildings/{b}/floors` | 비었거나 실패하면 폴백 |
| GET | `/positioning/buildings/{b}/floor/{f}/plan` | 경로의 `floor` 는 **단수**. 층별로 캐시 |
| GET | `/positioning/buildings/{b}/floor/{f}/zones` | 필터: 활성 && 꼭짓점 3개 이상 && 이름 중복 제거. 404 → 빈 목록 |
| GET | `/positioning/floors/{f}` | 레거시 lazy 호출(결과는 쓰지 않음). 404 는 빈 설정으로 캐시 |
| POST | `/events/zone` | IN/OUT 만 보낸다. 네트워크 오류일 때만 1회 재시도 |
| POST | `/positioning/logs` | 요청당 최대 500 점 |
| POST/GET/PUT/DELETE | `/profiles`, `/profiles/{id}` | |
| POST | `/logs` | 요청당 최대 500 항목 |
| GET (SSE) | `/stream?buildingId=&floorId=` | `Accept: text/event-stream` |

iOS 는 공간 조회 쪽 콘솔 호출이 initialize 의 baseURL 을 무시하고 기본 base 를 쓴다. 안드로이드도 **같게 둔다**. 고칠 때는 두 플랫폼을 함께 고친다.

**공간 서비스 (`https://geospace.geoplan.io/`, `X-SDK-Key` = `/config` 의 `geo_sdk_key`, `Connection: close`, camelCase)**

- `api/m/buildings` — `floorId` 가 숫자로 와도 문자열로 정규화한다.
- `api/m/floors/{f}/plan` — 콘솔 plan 조회 자체가 실패했을 때만 쓰는 폴백이다.
- `api/m/floors/{f}/anchors` — 180초 TTL 캐시.
  - `address = uwbMac 끝 4 hex & 0xFFFF`, `z = 0`
  - `isPlaced = clusterStatus == "auto_done"` (필드가 없으면 true)
  - `sessionId` 는 첫 앵커 값을 쓴다.
  - 조회에 실패해도 던지지 않는다(`locatorsFetchFailed`).

**존 폴리곤 정규화**: 도면이 없으면 미터 그대로 쓴다. 도면이 있고 점 하나라도 `x > widthM*1.5` 또는 `y > heightM*1.5` 면 픽셀로 보고 변환한다.
- `scale = imgW / widthM`
- `x = px/scale + originX`
- `y = (imgH - py)/scale + originY`

### 4.3 좌표 배치 · 로그 버퍼 · SSE

- **좌표 배치 flush 조건**: 300건 / 60초 타이머(측위 중에만) / stop / 백그라운드 / `send()`
  - 오래된 것부터 500건씩 보낸다. 실패하면 나머지를 유지한다.
  - 서버 전송분만 `minGap = 0.9 / positionRateHz` 로 다운샘플한다. 판정과 `onPosition` 은 원래 속도 그대로다.
- **로그 버퍼**: 임계 50건, 요청당 500건, 최대 2000건(넘치면 오래된 것부터 버림). ERROR 가 들어오면 즉시 flush, 나머지는 stop 때 flush 한다. 실패한 배치는 버린다.
- **SSE**
  - 바이트를 읽어 `\n` 단위로 파서에 넘긴다. **빈 줄을 보존해야 한다** — OkHttp `BufferedSource.readUtf8Line` 은 빈 줄을 돌려주므로 괜찮다.
  - 재연결 백오프는 1초에서 두 배씩 최대 30초, 지터 ±20%. 연결에 성공하면 1초로 되돌린다.
  - 2xx 로 연결되면 즉시 `ResyncNeeded` 를 보낸다. seq 에 갭이 있거나 역행하면 `ResyncNeeded`.
  - 연결 유지 조건: `층이 지정됨 || 측위 중`
  - 무응답 60초면 끊고 재연결한다.
- **visitorId**: 형식 `v-yyyyMMdd-NNN`(기기 로컬 날짜, 날짜가 바뀌면 리셋). SharedPreferences 키 `onesight.visitor.date`, `onesight.visitor.seq`.

## 5. 측위 파이프라인 (엔진 어댑터)

```
setFloorMap ─► plan·anchors·zones 병렬 조회 ─► FloorState
begin ─► UwbPositioningProvider.apply(config: anchors, sessionId, zones)
        └─► RangingSession(DL-TDoA, networkIdentifier = sessionId)
              onDlTdoaResults(peer, m) ─► DlBlockAccumulator(260ms)
                 ─► DlTdoaPositioner.update(block) ─► (x,y,z) ─► 메인 스레드
                      ├─► delegate.didUpdate → 좌표 버퍼 / onPosition
                      └─► ZoneEngine.ingest(Position)
```

- **`DlTdoaPositioner` 설정**
  - `applyAnchorCoordinates(Map<Int, DoubleArray>)` — 층을 바꾸거나 앵커가 달라지면 다시 주입한다.
  - `setMinRssi(-90)`. 튜닝 노브(`setAssumedTagZ`, `setMaxSpeed`)는 엔진 기본값 그대로 둔다(YAGNI).
- **세션 설정 격리**: `RangingPreference`/DL-TDoA 파라미터 조립은 **`DlTdoaSessionConfig` 한 파일**에 둔다.
  - Geoplan README 는 "세팅값은 앵커 하드웨어 제조사와 합의된 값"이라고 적고 있다.
  - iOS 는 `networkIdentifier`(= anchors 의 `sessionId`) 하나로 충분했다.
  - 안드로이드에 추가로 필요한 값은 **Geoplan 에 확인할 미결 사항**이다(§10). 그때까지는 sessionId 만 넣고, 나머지는 플랫폼 기본값을 쓴다.
- **수명주기**
  - `RangingSession.Callback` 의 `onOpenFailed`/`onClosed` 를 다음과 같이 나눈다.
    - `SecurityException` 또는 권한 사유 → E2003
    - 그 외 → E4001
  - 두 경우 모두 stop 으로 정리한다.
- **상태 전환 (iOS 0.1.22~0.1.23 이식)**
  - phase 는 `IDLE / STARTING / SEARCHING / TRACKING / STOPPING` 다섯 가지다.
  - `STOPPING` 중에 들어온 start 는 `startAfterStop` 으로 예약하고, 정지 콜백을 받은 뒤 이어서 켠다.
  - Coordinator 쪽에서는 `stopInFlight` 를 기다렸다가 start 한다. stop 을 동시에 두 번 불러도 flush 는 1회다. 이미 측위 중이면 WARN 을 남기고 넘긴다(visitorId 도 유지).
  - `isRunning = false` 는 performStop 의 **맨 마지막**에 둔다.
- **pause / resume**
  - pause 중에는 엔진은 계속 돌고, 좌표·구역 판정·전송 소비만 막는다.
  - resume 하면 판정기를 reset 한다.
  - start 와 stop 은 둘 다 pause 를 해제한다.
- **백그라운드**
  - `ProcessLifecycleOwner` 로 ON_STOP / ON_START 를 받는다.
  - ON_STOP: provider.stop, 좌표 flush, SSE stop
  - ON_START: provider.start, SSE 재연결(`ResyncNeeded`)
- **층 지정 없이 begin**: 파이프라인은 돌고 E3001(WARN) 을 남긴다. 좌표는 나오지 않는다.
- **수신 점검 (시작 7초 뒤 1회)**
  - 등록 앵커와 수신 주소를 비교한다.
  - 빠진 앵커가 있으면 E4003(WARN, `missing=0xABCD,…`)
  - 좌표가 없는데 matched 가 3대 이상이면 E4002(ERROR)
  - `canAttributePerAnchor = true`

## 6. 구역 판정 (SDK 자체)

iOS `Zone/ZoneEngine.swift` 를 코틀린으로 옮기고, **이벤트 계약은 iOS `UwbAreaJudge` 와 맞춘다.**

- **판정 입력**: 원래 속도의 좌표를 받되, 판단은 `sampleInterval = 1초` 에 1번만 한다.
- **진입**: 같은 존이 `confirmCount = 3` 번 연속 맞으면 IN.
- **이탈**: 활성 존이 3번 연속 안 맞으면 OUT. 그 직후 같은 샘플로 새 존 진입 카운트를 시작한다.
- **존 선택**: `zones.firstOrNull { it.contains(p) }` — 목록 순서상 첫 존이다. priority 는 쓰지 않는다(iOS ZoneEngine 과 같음).
- **DWELL**: `dwellSeconds > 0` 인 존에서 진입 후 그 시간이 지났을 때 여전히 안에 있으면 **1회**. iOS 계약을 따른다. ZoneEngine 의 5초 반복은 쓰지 않는다.
- **서버 전송**
  - IN/OUT → `/events/zone` 즉시. `floor_id` 는 콘솔 floorId 다(엔진 층 번호가 없다).
  - DWELL 은 앱 콜백만.
- **reset 시점**: `apply(zones)`, start, resume, 층 변경. reset 은 dwell 대기 취소와 활성 존 비우기를 포함한다.
- **콘솔 판정 파라미터**: `in_dist`, `in_count` 등은 iOS 와 마찬가지로 **쓰지 않는다**. `uwb.paramsIgnored` WARN 을 1회 남긴다.
- **refreshZones**: 존 목록을 판정기에 곧바로 다시 주입한다. 엔진 재시작(iOS `reloadGeofences`)은 필요 없으므로 no-op 이다.
- **pause 중**: 판정기에 좌표를 넣지 않는다.

## 7. 로그 · 코드 · 다국어

- `SdkErrorCode` 26개, `SdkInfoCode` 7개. 번호·등급·요약 문구를 iOS `Runtime/SdkErrorCode.swift` 와 같게 둔다.
  - 폐기 번호 E1005·E1006·E3005 는 재사용하지 않는다.
  - E3007·E3008(BLE 층)은 enum 에만 두고 발생시키지 않는다.
  - E2001 문구 "iOS 버전 미달"은 안드로이드에서 "Android 버전 미달"로 한다. 코드는 같다.
- `report(code, ctx)` 는 `onDebugLog(LogLevel.LOG, "[E1234] 요약 — ctx")` 를 호출하고 로그 버퍼에 쌓는다. 요약 문구는 서버로 보내지 않는다.
- **다국어**: iOS `Resources/i18n/SdkLocalization.json`(ko/ja/en, 108키)을 **원본 그대로** 리소스로 싣는다.
  - 로드할 때 `%@` → `%s` 로 바꾼다.
  - 폴백 순서: 현재 언어 → ko → 키
  - 기기 언어는 `LocaleList` 첫 항목으로 판정한다.
  - iOS 에서 한국어로 하드코딩된 문구(SSE 로그, `ApiError` 설명)도 같게 둔다.

## 8. 테스트 (JVM, JUnit + MockWebServer + kotlinx-coroutines-test)

iOS `Tests/OneS1ghtTests/` 의 테스트 파일을 **파일 단위로 대응**시킨다.

| 영역 | 옮길 iOS 테스트 |
|---|---|
| 서버 통신 | ApiClient, DTOs, SdkConfigFetch, SdkConfigResolution |
| 공간 서비스 | SpaceAnchorFetchFailure, SpaceFloorIdDecoding, SpaceFloorList, SpaceFloorWithoutPlan |
| SSE | ConfigChange, SseFrameParser, LiveConfigStreamGap, LiveConfigStreamIngest |
| 상태 기계 | SessionCoordinator, SessionCoordinatorLive, RestartAfterStop, PositioningPause, SdkGate |
| 식별자·버퍼 | IdentityStore, TrajectoryBuffer |
| 로그·진단 | SdkLogLevelPolicy, ReceptionCheck |
| 파사드·산출물 | OneS1ght, Snippets, Migrations |

- iOS 의 GeofenceReloadTests·UwbAreaJudgeTests 는 **ZoneEngineTests** 로 대체한다: 진입·이탈 확정, 경계 흔들림, DWELL 1회, reset.
- 엔진 전용 매핑 테스트(UwbEngineErrorMapping)는 **RangingErrorMappingTests** 로 대체한다.
- 안드로이드 API 가 필요한 부분(`RangingManager`, `ProcessLifecycleOwner`, SharedPreferences)은 인터페이스 뒤로 숨겨서 JVM 테스트에서 가짜로 바꾼다. Robolectric 은 쓰지 않는다.
- 빌드 검증: `./gradlew :onesight:testDebugUnitTest :onesight:assembleRelease`. 에뮬레이터 스모크(initialize → buildings → setFloorMap → begin 거절 `DEVICE_NOT_SUPPORTED`)는 가능한 범위에서 한다.

## 9. 빌드 · 배포 · 산출물

- **레포 구조**: 루트 Gradle(Kotlin DSL, wrapper), 라이브러리 모듈 `onesight/`, 그리고 `Snippets/`, `Migrations/`, `Scripts/`, `docs/`.
- **SDK 레벨**: compileSdk·targetSdk 37, minSdk 27(gpa-dltdoa 와 같음). 측위 호출은 `SDK_INT >= 37` 가드를 건다.
- **Maven 좌표 (배포 채널 결정 전 임시)**: `co.onecheck.ones1ght:android:0.0.1`
- **gpa-dltdoa 포함 방식**
  - Nexus 계정은 `~/.gradle/gradle.properties` 의 `geoplanNexusUrl`, `geoplanNexusUser`, `geoplanNexusPassword` 에서 읽는다. 레포에는 커밋하지 않는다.
  - 받은 AAR 의 classes 를 우리 AAR 에 합친다(fat-aar 방식). 전이 의존성(commons-math3, jts-core, slf4j-api)은 우리 POM 의존성으로 노출한다.
  - 고객 빌드 설정에는 Geoplan 저장소가 나오지 않는다. Geoplan 재배포 허락이 필요할 수 있다(§10).
  - 엔진의 ProGuard 규칙을 consumer rules 로 전달한다.
- **Manifest**: 라이브러리가 `RANGING`, `ACCESS_FINE_LOCATION`, `INTERNET` 을 선언해 앱에 병합되게 한다.
- **버전**: `OneS1ght.SDK_VERSION = "0.0.1"` 이 단일 출처다. `Snippets/android.json` 의 `sdkVersion` 과 `Migrations/android.json` 의 `currentVersion` 이 이 값과 같아야 한다. `Scripts/check-release.sh` 와 테스트가 검사한다.
- **Snippets/android.json**: iOS 와 같은 스키마·step id 12개, `language: "kotlin"`, `platform: "android"`. 공급사 이름(Geoplan, geoplan, gpa-, gpi-, GeoSpace 등)을 금지하는 테스트를 둔다.
- **CHANGELOG** `## [0.0.1]` 을 작성한다. **Migrations** 는 첫 버전이라 빈 체인이다.
- **CI**: `.github/workflows/` 에 test·prerelease·release 를 iOS 형식으로 둔다. 트리거는 `workflow_dispatch` 만 둔다(한도 보호).

## 10. 미결 · 위험

1. **DL-TDoA 세션 설정값**(Geoplan 확인 필요): 안드로이드 `RangingPreference` 에 sessionId 말고 무엇이 필요한가(채널, 프리앰블, 설정 id 등). 실기기가 생길 때까지 검증할 수 없다.
2. **gpa-dltdoa 재배포 허락**: 우리 AAR 안에 넣어 배포해도 되는지 Geoplan 확인이 필요하다.
3. **Nexus 계정**: 사용자가 `~/.gradle/gradle.properties` 에 넣기 전에는 엔진 어댑터(`UwbPositioningProvider`, `DlTdoaSessionConfig`)를 컴파일할 수 없다. 나머지(서버 통신·판정·상태 기계·산출물)는 `PositioningProvider` 인터페이스 뒤라 계정 없이 먼저 만든다. 엔진 어댑터 작업의 착수 조건은 계정이다.
4. **API 37 플랫폼 설치**: 로컬에는 35·36 만 있다. `sdkmanager "platforms;android-37"` 가 필요하다(수백 MB, 디스크 여유 74GB).
5. **판정 감도 차이**: iOS(엔진 판정)와 안드로이드(SDK 판정)의 IN/OUT 타이밍이 다를 수 있다. 실기기 검증 항목이다.
6. **배포 채널**: private 레포라 JitPack(유료)·GitHub Packages(토큰 필요)·Maven Central 중에서 정해야 한다.
