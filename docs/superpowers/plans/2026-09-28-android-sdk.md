# OneS1ght Android SDK 0.0.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 안드로이드 라이브러리(AAR)를 만든다. 공개 API·서버 계약·오류 코드·수명주기 규칙이 OneS1ght iOS SDK v0.1.23 과 같고, Java 와 Kotlin 양쪽에서 부를 수 있어야 한다.

**Architecture:**
- 라이브러리 모듈 `onesight` 하나가 전부를 담는다: 공개 파사드(`OneS1ght`, `FloorSession`), 코어(서버 통신·SSE·버퍼·판정·상태 기계), 엔진 어댑터(`android.ranging` + gpa-dltdoa).
- 안드로이드 플랫폼 의존(SharedPreferences, 생명주기, RangingManager)은 작은 인터페이스 뒤에 숨긴다. 코어는 전부 JVM 단위 테스트로 검증한다.
- gpa-dltdoa 는 Nexus 계정이 있으면 진짜 AAR 을 받아 우리 AAR 에 싣는다. 계정이 없으면 `engine-stub` 모듈(같은 시그니처의 컴파일 전용 스텁)로 컴파일만 한다.

**Tech Stack:** Kotlin 2.2 · AGP(compileSdk 37 지원 최신 안정판) · Gradle 9 wrapper · kotlinx-coroutines 1.10 · kotlinx-serialization-json 1.9 · OkHttp 4.12 · JUnit 4 · MockWebServer 4.12 · kotlinx-coroutines-test

**Spec:** `docs/superpowers/specs/2026-09-28-android-sdk-design.md` — 구현자는 이 문서와 함께 반드시 읽는다.

**iOS 원본 (포팅 정본):** `/private/tmp/claude-501/-Users-roy-onecheck-onesight/e71f148f-94d8-4fde-8601-1dfe9ff5d8a5/scratchpad/ios-sdk` (@ `da2abcb`, 이하 `$IOS`)
- 엔진 주변 옛 구조는 `git -C $IOS show b804f3b:<path>` 로 본다(읽기 전용, checkout 금지).
- 각 태스크의 "포팅 원본"에 적힌 Swift 파일을 **먼저 끝까지 읽고** 같은 동작·같은 문자열·같은 필드 이름으로 옮긴다.
- iOS 테스트 파일은 테스트 케이스 목록의 정본이다. 모든 케이스를 JUnit 으로 옮긴다.

## Global Constraints

- 패키지: `co.onecheck.ones1ght.android` (하위 패키지 `.model` `.network` `.space` `.runtime` `.zone` `.positioning` `.identity` `.internal`)
- 버전 단일 출처: `OneS1ght.SDK_VERSION = "0.0.1"` (`const val`)
- compileSdk 37 (`android-37.0` 설치됨), targetSdk 37, minSdk 27, JVM target 17
- 레포 루트: `/Users/roy/onecheck/onesight/OneS1ght-Android-SDK`, 브랜치 `feat/android-sdk-0.0.1`. **main 브랜치는 건드리지 않는다. push 는 하지 않는다.**
- 서버 JSON: `explicitNulls = false`, `ignoreUnknownKeys = true`, 필드 이름은 `@SerialName("snake_case")` 로 명시하고 iOS DTOs.swift 와 한 글자도 다르지 않게 한다.
- `platform_name` 은 항상 `"Android"`
- 기본 base URL: `https://console.ones1ght.com/api/sdk/v1`. 공간 서비스 host: `https://geospace.geoplan.io/`
- 헤더는 `X-SDK-Key` 이고, 키 원문을 접두어 없이 넣는다.
- 시각 형식: ISO-8601 UTC 밀리초 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`
- 공개 콜백은 메인 스레드에서 호출한다. 코어 상태는 주입된 `CoroutineDispatcher`(운영: `Dispatchers.Main.immediate`, 테스트: `StandardTestDispatcher`) 한 곳에서만 바꾼다.
- 코어 코드는 `android.*` 를 쓰지 않는다. 쓸 수 있는 곳은 `positioning/Uwb*`, `positioning/DlTdoaSessionConfig`, `positioning/AndroidDeviceCapability`, `positioning/PositioningPermission`, `runtime/AndroidPlatform.kt`, `OneS1ght.kt`, `FloorSession.kt` 뿐이다. Base64 는 `java.util.Base64` 를 쓴다.
- **공급사 이름 노출 금지**
  - 대상: 공개 API 이름, 로그 문구, README, Snippets
  - 금지어: `Geoplan`, `geoplan`, `gpa-`, `gpi-`, `GeoSpace`, `Geospace`, `ihub`
  - 예외: 빌드 스크립트의 저장소·좌표, 내부 URL 상수 1곳(`SpaceServiceClient.SPACE_HOST`), `engine-stub` 모듈
- Java 호환: 비동기 공개 함수는 suspend 판과 `Callback<T>` 판을 같은 이름으로 두 벌 둔다. 이벤트 콜백은 `fun interface` 로 한다. `object` 멤버에는 `@JvmStatic` 을, 기본 인자가 있는 함수에는 `@JvmOverloads` 를 붙인다.
- 커밋 메시지: 한국어 한 줄 요약(`feat:`/`test:`/`chore:`/`docs:`), 마지막 줄은 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`
- 각 태스크 끝에 `./gradlew :onesight:testDebugUnitTest` 가 전부 초록이어야 커밋한다.

## Review Focus

1. **서버가 모르는 필드·null·타입 뒤섞인 값**(`remote_config`, `Trigger.payload`, 숫자 `floorId`, 빠진 `name`/`has_plan`)을 보내도 초기화와 층 로드가 깨지지 않아야 한다 → Task 2·5 테스트
2. **stop 도중 start / 동시 stop 두 번 / 중복 start** → 결국 측위 중이고, flush 는 1회, visitorId 는 유지 → Task 10 `RestartAfterStopTest`
3. **네트워크 끊김 중 좌표 전송 실패** → 좌표를 잃지 않고 다음 flush 에 재시도. 존 이벤트는 네트워크 오류일 때 1회만 재시도 → Task 7·10
4. **경계선 위에서 흔들리는 좌표** → IN/OUT 이 튀지 않아야 한다(3회 연속 확정). pause 중에는 이벤트가 0개, resume 뒤 판정기는 초기화 → Task 8·12
5. **Java 앱에서 호출** → `JavaInteropTest.java` 가 컴파일되고 실행돼야 한다. Java 쪽에서 `Unit`/`Continuation` 을 다룰 일이 없어야 한다 → Task 11

---

## File Structure

```
settings.gradle.kts / build.gradle.kts / gradle.properties / gradlew* / gradle/wrapper/
gradle/libs.versions.toml
engine-stub/                                   # 컴파일 전용 스텁 (배포 안 됨)
  build.gradle.kts
  src/main/java/kr/geoplan/android/lib/dltdoa/{DlTdoaPositioner,DlBlockAccumulator,PositionCallback}.java
onesight/
  build.gradle.kts  consumer-rules.pro  proguard-rules.pro
  src/main/AndroidManifest.xml                 # RANGING, ACCESS_FINE_LOCATION, INTERNET
  src/main/resources/co/onecheck/ones1ght/android/i18n/SdkLocalization.json   # iOS 원본 그대로
  src/main/kotlin/co/onecheck/ones1ght/android/
    OneS1ght.kt            FloorSession.kt       Callbacks.kt        JavaBridge.kt
    model/SpaceModels.kt   model/Dtos.kt         model/ConfigChange.kt  model/ZoneModels.kt
    internal/SdkJson.kt    internal/LenientStringMap.kt  internal/Iso8601.kt
    network/ApiError.kt    network/ApiClient.kt
    space/SpaceServiceClient.kt  space/SpaceBuildingsResponse.kt
    runtime/LogLevel.kt    runtime/SdkErrorCode.kt  runtime/SdkLocalized.kt
    runtime/SdkLogBuffer.kt runtime/TrajectoryBuffer.kt
    runtime/SseFrameParser.kt runtime/LiveConfigStream.kt
    runtime/Platform.kt    runtime/AndroidPlatform.kt  runtime/SessionCoordinator.kt
    identity/IdentityStore.kt
    zone/ZoneEngine.kt
    positioning/PositioningProvider.kt  positioning/MockPositioningProvider.kt
    positioning/EngineStateMachine.kt   positioning/RangingErrorMapping.kt
    positioning/DeviceCapability.kt     positioning/AndroidDeviceCapability.kt
    positioning/PositioningPermission.kt
    positioning/DlTdoaSessionConfig.kt  positioning/UwbPositioningProvider.kt
  src/test/kotlin/co/onecheck/ones1ght/android/...   (태스크별)
  src/test/java/co/onecheck/ones1ght/android/JavaInteropTest.java
Snippets/android.json  Migrations/android.json  CHANGELOG.md
README.md  README.ko.md  README.ja.md
Scripts/{sdk-version.sh,check-release.sh,next-rc-tag.sh,verify-tag.sh,changelog-section.sh,release.sh}
.github/workflows/{test.yml,prerelease.yml,release.yml}   # workflow_dispatch 전용
```

---

### Task 1: Gradle 골격 · 엔진 의존 전환 · 버전 상수

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, gradle wrapper
- Create: `engine-stub/build.gradle.kts`, `engine-stub/src/main/java/kr/geoplan/android/lib/dltdoa/*.java`
- Create: `onesight/build.gradle.kts`, `onesight/consumer-rules.pro`, `onesight/proguard-rules.pro`, `onesight/src/main/AndroidManifest.xml`
- Create: `onesight/src/main/kotlin/co/onecheck/ones1ght/android/OneS1ght.kt` (이 태스크에서는 `SDK_VERSION` 만)
- Test: `onesight/src/test/kotlin/co/onecheck/ones1ght/android/SdkVersionTest.kt`

**Interfaces:**
- Produces: `object OneS1ght { const val SDK_VERSION = "0.0.1" }`
- Produces: Gradle 속성 `geoplanNexusUrl` / `geoplanNexusUser` / `geoplanNexusPassword`(`~/.gradle/gradle.properties`)
  - 셋 다 있으면 `kr.geoplan.android.lib:gpa-dltdoa:2.1.0` 을 받아 AAR 속 `classes.jar` 를 `onesight` 의 `implementation(files(...))` 로 싣는다. 그러면 AAR 의 `libs/` 로 패키징된다. 엔진 AAR 의 `proguard.txt` 가 있으면 consumer rules 에 합친다.
  - 없으면 `compileOnly(project(":engine-stub"))` 를 쓴다.
  - 전이 의존 `org.apache.commons:commons-math3`, `org.locationtech.jts:jts-core`, `org.slf4j:slf4j-api` 는 엔진 POM 에 적힌 버전으로 `implementation` 한다.
- Produces: 계정 없이 `:onesight:assembleRelease` 를 돌리면 실패한다(`"gpa engine missing: set geoplanNexus* in ~/.gradle/gradle.properties"`). 스텁 AAR 이 나가는 일을 막기 위해서다. Debug·테스트는 계정 없이도 돈다.

- [ ] **Step 1: AGP·의존 버전 확정**

Run:
```bash
curl -s https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml | grep -oE '<version>[0-9]+\.[0-9]+\.[0-9]+</version>' | tail -5
```
- compileSdk 37 을 지원하는 최신 **안정판**(rc/alpha 제외)을 고른다. AGP 릴리스 노트의 "maximum API level" 을 확인한다.
- AGP 9 이상이면 내장 Kotlin 지원을 쓴다(`org.jetbrains.kotlin.android` 플러그인 불필요). 8.x 면 `kotlin("android")` 2.2.x 를 쓴다.
- `libs.versions.toml` 에 적는다.

- [ ] **Step 2: 스텁 작성** (`engine-stub`, `java-library` 플러그인, `compileOnly(files(android.jar))`)

`engine-stub/build.gradle.kts`:
```kotlin
plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    compileOnly(files("${System.getenv("ANDROID_HOME") ?: System.getProperty("user.home") + "/Library/Android/sdk"}/platforms/android-37.0/android.jar"))
}
```
`PositionCallback.java`:
```java
package kr.geoplan.android.lib.dltdoa;
/** 컴파일 전용 스텁 — 실제 구현은 Nexus AAR. 배포물에 절대 실리지 않는다. */
public interface PositionCallback {
    void onPosition(double x, double y, double z);
    void onInvalidated(String error);
}
```
`DlTdoaPositioner.java`:
```java
package kr.geoplan.android.lib.dltdoa;
import android.ranging.DlTdoaMeasurement; import android.ranging.RangingDevice; import android.util.Pair;
import java.util.List; import java.util.Map;
public class DlTdoaPositioner {
    public DlTdoaPositioner(PositionCallback callback) { throw new UnsupportedOperationException("stub"); }
    public void applyAnchorCoordinates(Map<Integer, double[]> coords) { throw new UnsupportedOperationException("stub"); }
    public void setMinRssi(int minRssi) { throw new UnsupportedOperationException("stub"); }
    public void setAssumedTagZ(double z) { throw new UnsupportedOperationException("stub"); }
    public void setMaxSpeed(double metersPerSecond) { throw new UnsupportedOperationException("stub"); }
    public double[] update(List<Pair<RangingDevice, DlTdoaMeasurement>> block) { throw new UnsupportedOperationException("stub"); }
    public void reset() { throw new UnsupportedOperationException("stub"); }
}
```
`DlBlockAccumulator.java`:
```java
package kr.geoplan.android.lib.dltdoa;
import android.ranging.DlTdoaMeasurement; import android.ranging.RangingDevice; import android.util.Pair;
import java.util.List;
public class DlBlockAccumulator {
    public interface BlockReadyListener { void onBlockReady(List<Pair<RangingDevice, DlTdoaMeasurement>> block); }
    public DlBlockAccumulator(long timeoutMillis, BlockReadyListener listener) { throw new UnsupportedOperationException("stub"); }
    public void add(RangingDevice peer, DlTdoaMeasurement m) { throw new UnsupportedOperationException("stub"); }
    public void reset() { throw new UnsupportedOperationException("stub"); }
}
```

- [ ] **Step 3: 실패하는 테스트**
```kotlin
package co.onecheck.ones1ght.android
import org.junit.Assert.assertTrue
import org.junit.Test
class SdkVersionTest {
    @Test fun sdkVersionIsSemver() {
        assertTrue(Regex("""^\d+\.\d+\.\d+$""").matches(OneS1ght.SDK_VERSION))
    }
}
```

- [ ] **Step 4: onesight 모듈 설정**
- `android { namespace = "co.onecheck.ones1ght.android"; compileSdk = 37; defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") } }`
- `testOptions.unitTests.isReturnDefaultValues = true`
- `kotlin { explicitApi() }` — 공개 범위를 명시해 공개 API 누출을 막는다.
- kotlinx-serialization 플러그인, 소스 폴더 `src/main/kotlin` · `src/test/kotlin` · `src/test/java`
- Manifest:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RANGING" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
</manifest>
```
- `OneS1ght.kt`: `public object OneS1ght { public const val SDK_VERSION: String = "0.0.1" }`
- `gradle wrapper --gradle-version <Step1 에서 AGP 가 요구하는 버전>` 을 실행한다.

- [ ] **Step 5: 확인**

Run: `./gradlew :onesight:testDebugUnitTest :onesight:assembleDebug`
Expected: BUILD SUCCESSFUL, 테스트 1개 통과

Run: `./gradlew :onesight:assembleRelease` (계정 없음)
Expected: 위에서 정한 메시지로 FAIL

- [ ] **Step 6: 커밋** — `chore: Gradle 골격 — onesight 라이브러리·엔진 스텁·버전 0.0.1`

---

### Task 2: 모델 · DTO · JSON

**포팅 원본:** `$IOS/Sources/OneS1ght/Models/{DTOs.swift,SpaceModels.swift}`, `Zone/ZoneEngine.swift`(Position·Zone·ZoneEvent 부분만), `Runtime/ConfigChange.swift`(enum 만, 파싱은 Task 6)
**테스트 원본:** `$IOS/Tests/OneS1ghtTests/DTOsTests.swift`

**Files:**
- Create: `internal/SdkJson.kt`, `internal/LenientStringMap.kt`, `internal/Iso8601.kt`, `model/Dtos.kt`, `model/SpaceModels.kt`, `model/ZoneModels.kt`, `model/ConfigChange.kt`
- Test: `model/DtosTest.kt`, `internal/LenientStringMapTest.kt`, `model/ZoneModelsTest.kt`

**Interfaces (Produces — 이름·타입 고정):**
```kotlin
// internal
internal val SdkJson: Json  // Json { explicitNulls = false; ignoreUnknownKeys = true; encodeDefaults = true }
internal object LenientStringMapSerializer : KSerializer<Map<String, String>>
internal object Iso8601 { fun format(epochMillis: Long): String; fun nowString(clock: () -> Long): String }

// model — 공개
public data class Coordinates(val x: Double, val y: Double, val z: Double)          // @Serializable
public enum class ZoneEventStatus(public val wire: String) { ENTER("IN"), DWELL("DWELL"), EXIT("OUT") }
public data class Trigger(val triggerId: String, val type: String, val payload: Map<String, String>? = null) // @SerialName trigger_id…
public data class Building(val id: String, val name: String, val floorCount: Int? = null)
public data class Floor(val id: String, val name: String, val image: ByteArray? = null, val hasPlan: Boolean = false,
                        val originX: Double = 0.0, val originY: Double = 0.0, val widthM: Double = 0.0, val heightM: Double = 0.0) {
    val minX: Double; val minY: Double; val maxX: Double; val maxY: Double   // equals/hashCode 는 image 내용 비교
}
public data class Locator(val address: Int, val x: Double, val y: Double, val z: Double, val isPlaced: Boolean = true)
public data class FloorLocators(val locators: List<Locator>, val sessionId: Int?) { val positioningReady: Boolean }
internal data class FloorState(val buildingId: String, val floorId: String, val sessionId: Int?, val locators: List<Locator>,
                               var zones: List<Zone>, val hasPlan: Boolean, val locatorsFetchFailed: Boolean)
public data class Position(val x: Double, val y: Double)
public data class Zone(val id: String, val name: String, val polygon: List<Position>,
                       val inDist: Double = 3.0, val inCount: Int = 0, val inCountInterval: Int = 0, val outPeriod: Int = 0,
                       val priority: Int = 1, val callInout: Boolean = true, val dwellSeconds: Int? = null) {
    public fun contains(p: Position): Boolean   // ray casting, 꼭짓점 < 3 이면 false
}
public sealed class ZoneEvent { abstract val zone: Zone; abstract val at: Long /*epochMillis*/; abstract val id: String; abstract val label: String
    data class Enter(...) ; data class Exit(...) ; data class Dwell(val zone: Zone, val seconds: Double, val at: Long) }
    // id: "in-<zoneId>-<epochSec>" / "out-…" / "dw-<zoneId>-<Int(seconds)>-<epochSec>"  label: "IN  · name" / "OUT · name" / "DWELL · name (Ns)"
public sealed class ConfigChange { data class ZonesChanged(val floorId: String?); data class PlanChanged(val floorId: String?);
    data class RulesChanged(val zoneId: String?); data class SdkConfigChanged(val rateHz: Int?, val logLevel: String?); object ResyncNeeded }
public object SdkDefaults { const val POSITION_RATE_HZ = 4; const val MIN_RATE_HZ = 1; const val MAX_RATE_HZ = 100 }

// DTO — internal, @Serializable, 필드명 iOS 와 동일
internal data class ReqVerify(@SerialName("platform_name") val platformName: String, @SerialName("app_id") val appId: String? = null, val client: ClientInfo? = null)
internal data class ClientInfo(...8필드...)
internal data class ResVerify(val valid: Boolean, @SerialName("positioning_enabled") val positioningEnabled: Boolean,
    @SerialName("tenant_code") val tenantCode: String? = null, @SerialName("position_rate_hz") val positionRateHz: Int? = null,
    @SerialName("remote_config") @Serializable(LenientStringMapSerializer::class) val remoteConfig: Map<String,String>? = null)
internal data class ResSdkConfig(tenant_code, google_map_key, geo_sdk_key, geo_partner_key, geo_base_url — 전부 String?)
internal data class ReqZoneEvent(profile_id, visitor_id, floor_id, zone_id, status, occurred_at, platform_name)
internal data class PositionPoint(floor_id, coordinates: Coordinates, captured_at)
internal data class ReqPositionBulk(profile_id, visitor_id, platform_name, points)
internal data class ReqProfile(attributes: Map<String,String>)
internal data class SdkLogEntry(code, level, message, at)
internal data class ReqSdkLogs(profile_id, platform_name, sdk_version, entries)
internal data class ResBuildings / ResFloorConfig / ZoneMeta / ResZoneEvent(accepted, event_id, triggers) / ResPositionBulk(accepted_count)
internal data class ResProfileCreate(profile_id) / ResProfile(profile_id, attributes?) / ResProfileDelete(deleted) / ResSdkLogs(accepted_count)
```
- `ResVerify` 의 `tenant_code`·`position_rate_hz` 는 타입이 어긋나도 null 로 떨어져야 한다(iOS `try?`). 커스텀 `KSerializer` 로 JsonObject 를 받아 개별 필드를 `runCatching` 한다.

- [ ] **Step 1: 실패하는 테스트** — DTOsTests.swift 의 모든 케이스를 옮긴다. 반드시 들어갈 것:
```kotlin
@Test fun zoneEventEncodesExactWireJson() {
    val s = SdkJson.encodeToString(ReqZoneEvent("p1","v-20260820-001","14","z9","DWELL","2026-07-18T08:00:00.123Z","Android"))
    assertEquals("""{"profile_id":"p1","visitor_id":"v-20260820-001","floor_id":"14","zone_id":"z9","status":"DWELL","occurred_at":"2026-07-18T08:00:00.123Z","platform_name":"Android"}""", s)
}
@Test fun nullFieldsAreOmitted() {
    assertEquals("""{"platform_name":"Android"}""", SdkJson.encodeToString(ReqVerify("Android")))
}
@Test fun verifyToleratesMixedRemoteConfig() {
    val r = SdkJson.decodeFromString<ResVerify>("""{"valid":true,"positioning_enabled":true,"tenant_code":5,"remote_config":{"a":true,"b":3,"c":1.5,"d":"x","e":{"n":1},"f":[1]}}""")
    assertNull(r.tenantCode); assertEquals(mapOf("a" to "true","b" to "3","c" to "1.5","d" to "x"), r.remoteConfig)
}
@Test fun remoteConfigNotObjectBecomesEmpty() { /* "remote_config":"oops" → emptyMap() */ }
@Test fun triggerPayloadLenient() { /* payload {"k":1,"n":null,"o":{}} → {"k":"1"} , trigger 유지 */ }
@Test fun iso8601HasMillisAndZ() { assertEquals("1970-01-01T00:00:00.123Z", Iso8601.format(123)) }
```
- `ZoneModelsTest`: contains 정사각형 안/밖, 꼭짓점 2개면 false, ZoneEvent id/label 형식(IN 뒤 공백 2칸)
- `Floor` 의 max 계산 속성

- [ ] **Step 2:** `./gradlew :onesight:testDebugUnitTest --tests '*Dtos*' --tests '*Lenient*' --tests '*ZoneModels*'` → FAIL(컴파일 오류)
- [ ] **Step 3:** 구현 (위 Interfaces 그대로)
- [ ] **Step 4:** 같은 명령 → PASS, 이어서 전체 테스트 PASS
- [ ] **Step 5: 커밋** — `feat: 모델·DTO — iOS 와 같은 JSON 계약`

---

### Task 3: 오류 코드 · 로그 등급 · 다국어

**포팅 원본:** `$IOS/Sources/OneS1ght/Runtime/{SdkErrorCode.swift,LogLevel.swift,SdkLocalized.swift}`, `Resources/i18n/SdkLocalization.json`
**테스트 원본:** `$IOS/Tests/OneS1ghtTests/SdkLogLevelPolicyTests.swift`

**Files:**
- Create: `runtime/LogLevel.kt`, `runtime/SdkErrorCode.kt`, `runtime/SdkLocalized.kt`, `src/main/resources/co/onecheck/ones1ght/android/i18n/SdkLocalization.json`(`cp $IOS/Sources/OneS1ght/Resources/i18n/SdkLocalization.json` — 바이트 동일)
- Test: `runtime/SdkLogLevelPolicyTest.kt`, `runtime/SdkLocalizedTest.kt`

**Interfaces (Produces):**
```kotlin
public enum class LogLevel(public val raw: String) { LOG("log"), INFO("info"), WARN("warn"), ERROR("error") }  // ordinal 순 비교
public enum class SdkLogLevel(public val wire: String) { ERROR("ERROR"), WARN("WARN"), INFO("INFO") }
public interface SdkCode { public val code: String; public val level: SdkLogLevel; public val summary: String }
public enum class SdkErrorCode(override val code: String, override val level: SdkLogLevel, override val summary: String) : SdkCode {
    NOT_INITIALIZED("E1001", ERROR, "SDK 가 초기화되지 않음"), INVALID_KEY("E1002", …), POSITIONING_DISABLED("E1003", WARN, …),
    NOT_IDENTIFIED("E1004", …), KEY_UNAVAILABLE("E1007", …), OS_VERSION_TOO_LOW("E2001", WARN, "Android 버전 미달"),
    DEVICE_NOT_SUPPORTED("E2002", …), PERMISSION_DENIED("E2003", …), FLOOR_NOT_SET("E3001", …), LOCATORS_MISSING("E3002", …),
    SESSION_ID_MISSING("E3003", …), ZONES_EMPTY("E3004", …), LOCATORS_FETCH_FAILED("E3006", …), FLOOR_NOT_DETECTED("E3007", …),
    FLOOR_ID_MISMATCH("E3008", …), ZONE_MAPPING_FAILED("E3009", …), UWB_SESSION_FAILED("E4001", …), NO_POSITION_FIX("E4002", …),
    LOCATOR_NOT_RECEIVED("E4003", …), AREA_JUDGE_FAILED("E4004", …), NETWORK("E5001", …), SERVER("E5002", …),
    UNPROCESSABLE("E5003", …), FORBIDDEN("E5004", …), DECODING("E5005", …), PENDING_DROPPED("E5006", WARN, …)
}   // 26개 — 등급·문구는 iOS SdkErrorCode.swift 에서 그대로 복사(E2001 문구만 "Android 버전 미달")
public enum class SdkInfoCode(...) : SdkCode { INITIALIZED("I1001"), IDENTIFIED("I1002"), FLOOR_SET("I3001"), PLAN_MISSING("I3002"),
    POSITIONING_ON("I4001"), POSITIONING_OFF("I4002"), RATE_APPLIED("I5001") }
internal object SdkLocalized {
    var language: String?                      // "ko"|"ja"|"en"|null(기기 언어)
    var deviceLanguage: () -> String           // 기본: Locale.getDefault().language → ja/en/그 외 ko
    fun t(key: String, vararg args: Any?): String   // 현재 언어 → ko → key, "%@"→"%s" 치환 후 String.format(Locale.ROOT)
}
```

- [ ] **Step 1: 실패하는 테스트** — SdkLogLevelPolicyTests 의 모든 케이스, 그리고:
```kotlin
@Test fun has26ErrorCodesAnd7Info() { assertEquals(26, SdkErrorCode.entries.size); assertEquals(7, SdkInfoCode.entries.size) }
@Test fun retiredNumbersNeverReused() { val c = SdkErrorCode.entries.map { it.code }; listOf("E1005","E1006","E3005").forEach { assertFalse(it in c) } }
@Test fun codesAndLevelsMatchIos() {
    // iOS SdkErrorCode.swift 의 (code, level) 26쌍을 그대로 옮긴 표 — 사양 동일성 가드
    val ios = mapOf("E1001" to "ERROR", /* … 26개 전부 … */ "E5006" to "WARN")
    assertEquals(ios, SdkErrorCode.entries.associate { it.code to it.level.wire })
}
```
  - 표의 26쌍은 구현자가 iOS 파일에서 직접 채운다. 테스트가 레포 밖 파일을 읽으면 안 된다.
- `SdkLocalizedTest`
  - `t("coord.stopFlush", 3)` 를 ko/en/ja 로 각각 확인한다.
  - 없는 언어 키면 ko 로, 없는 키면 키 문자열 그대로 나와야 한다.
  - `%@` 인자가 치환되는지 확인한다.
  - JSON 이 108키 × 3언어인지 확인한다.
- [ ] **Step 2:** 실행 → FAIL
- [ ] **Step 3:** 구현. JSON 은 `SdkLocalized::class.java.getResourceAsStream("/co/onecheck/ones1ght/android/i18n/SdkLocalization.json")` 로 1회 lazy 로드한다.
- [ ] **Step 4:** PASS
- [ ] **Step 5: 커밋** — `feat: 오류·정보 코드 33종과 로그 다국어`

---

### Task 4: ApiClient · ApiError

**포팅 원본:** `$IOS/Sources/OneS1ght/Networking/ApiClient.swift`, `Runtime/SdkErrorCode.swift`(ApiError→code 매핑 243-253행)
**테스트 원본:** `ApiClientTests.swift`, `SdkConfigFetchTests.swift`, `StubURLProtocol.swift`(→ MockWebServer)

**Files:**
- Create: `network/ApiError.kt`, `network/ApiClient.kt`
- Test: `network/ApiClientTest.kt`, `network/SdkConfigFetchTest.kt`

**Interfaces (Produces):**
```kotlin
public sealed class ApiError(message: String) : Exception(message) {
    public class InvalidKey(public val detail: String?) : ApiError(…)      // 401, message = "SDK 키가 무효하거나 폐기됨" + (" — $detail")
    public class Forbidden(public val detail: String?) : ApiError(…)       // 403
    public class NotFound(public val detail: String?) : ApiError(…)        // 404
    public class Unprocessable(public val detail: String?) : ApiError(…)   // 422
    public class Server(public val status: Int, public val detail: String?) : ApiError(…)
    public class Network(public override val cause: java.io.IOException) : ApiError(…)
    public class Decoding(public val detail: String?) : ApiError(…)
    public val code: SdkErrorCode   // InvalidKey→E1002, Forbidden→E5004, NotFound→E5003, Unprocessable→E5003, Server→E5002, Network→E5001, Decoding→E5005
}   // equals: 같은 하위 타입·같은 필드면 같음 (iOS Equatable)
public class ApiClient @JvmOverloads constructor(
    public val apiKey: String, public val baseUrl: String = DEFAULT_BASE_URL, http: OkHttpClient = defaultHttp()) {
    public companion object { public const val DEFAULT_BASE_URL: String = "https://console.ones1ght.com/api/sdk/v1" }
    internal suspend fun verify(req: ReqVerify): ResVerify                 // POST /auth/verify
    internal suspend fun config(): ResSdkConfig                            // GET /config
    internal suspend fun buildings(): ResBuildings                         // GET /positioning/buildings
    internal suspend fun floorConfig(floorId: String): ResFloorConfig      // GET /positioning/floors/{id}
    internal suspend fun sendZoneEvent(req: ReqZoneEvent): ResZoneEvent   // POST /events/zone
    internal suspend fun sendPositionLogs(req: ReqPositionBulk): ResPositionBulk  // POST /positioning/logs
    internal suspend fun createProfile(attrs: Map<String,String>): ResProfileCreate // POST /profiles
    internal suspend fun getProfile(id: String): ResProfile                // GET /profiles/{id}
    internal suspend fun putProfile(id: String, attrs: Map<String,String>): ResProfile // PUT
    internal suspend fun deleteProfile(id: String): ResProfileDelete       // DELETE (본문 없음, Content-Type 헤더는 붙임)
    internal suspend fun sendLogs(req: ReqSdkLogs): ResSdkLogs             // POST /logs
    internal val http: OkHttpClient                                        // SSE 가 재사용 (Task 6)
}
```
- URL 조립: `baseUrl.trimEnd('/') + path`, 헤더 `X-SDK-Key`, `Content-Type: application/json`, 타임아웃 10초
- OkHttp 호출은 `suspendCancellableCoroutine` + `enqueue` 로 한다. `IOException` 은 `ApiError.Network` 로, 역직렬화 실패는 `ApiError.Decoding(detail = 필드 경로를 담은 예외 메시지)` 로 바꾼다.
- 오류 본문은 `{"detail": "..."}` 이면 detail 을 쓰고, 파싱에 실패하면 null 이다.

- [ ] **Step 1: 실패하는 테스트** — ApiClientTests·SdkConfigFetchTests 의 모든 케이스. 대표 예시:
```kotlin
@get:Rule val server = MockWebServer()
private fun client() = ApiClient("ock_test", server.url("/api/sdk/v1").toString().trimEnd('/'))
@Test fun verifyPostsToExactPathWithKeyHeader() = runTest {
    server.enqueue(MockResponse().setBody("""{"valid":true,"positioning_enabled":true}"""))
    client().verify(ReqVerify("Android", "com.example"))
    val r = server.takeRequest()
    assertEquals("POST", r.method); assertEquals("/api/sdk/v1/auth/verify", r.path)
    assertEquals("ock_test", r.getHeader("X-SDK-Key"))
    assertEquals("""{"platform_name":"Android","app_id":"com.example"}""", r.body.readUtf8())
}
@Test fun status404IsNotFoundWithDetail() = runTest {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"floor x"}"""))
    val e = assertThrows(ApiError.NotFound::class.java) { runBlocking { client().floorConfig("x") } }
    assertEquals("floor x", e.detail)
}
@Test fun connectionFailureIsNetwork() = runTest { server.shutdown(); /* → ApiError.Network */ }
```
- [ ] **Step 2:** FAIL → **Step 3:** 구현 → **Step 4:** PASS
- [ ] **Step 5: 커밋** — `feat: ApiClient — 콘솔 SDK API 11종`

---

### Task 5: SpaceServiceClient (층·도면·앵커·구역)

**포팅 원본:** `$IOS/Sources/OneS1ght/SpaceService/{SpaceServiceClient.swift,SpaceBuildingsResponse.swift}`
**테스트 원본:** `SpaceAnchorFetchFailureTests`, `SpaceFloorIdDecodingTests`, `SpaceFloorListTests`, `SpaceFloorWithoutPlanTests`

**Files:**
- Create: `space/SpaceServiceClient.kt`, `space/SpaceBuildingsResponse.kt`
- Test: 위 4개를 `space/*Test.kt` 로

**Interfaces (Produces):**
```kotlin
internal class SpaceServiceClient(
    sdkKey: String, spaceKey: String, http: OkHttpClient,
    consoleBase: String = ApiClient.DEFAULT_BASE_URL,   // iOS 와 같이 initialize 의 baseUrl 을 쓰지 않는다 (spec §4.2)
    spaceHost: String = SPACE_HOST, clock: () -> Long = System::currentTimeMillis) {
    companion object { const val SPACE_HOST = "https://geospace.geoplan.io/"; const val ANCHOR_TTL_MS = 180_000L }
    suspend fun buildings(): List<Building>
    suspend fun floors(buildingId: String): List<Floor>                 // image=null
    suspend fun loadFloor(buildingId: String, floorId: String): Floor   // 도면 포함, 실패 시 name=floorId.take(8)
    suspend fun loadZones(buildingId: String, floorId: String): List<Zone>   // 404 → emptyList
    suspend fun loadLocators(floorId: String): FloorLocators?           // 실패 → null (던지지 않음)
    suspend fun loadFloorState(buildingId: String, floorId: String): FloorState  // plan·anchors·zones 를 coroutineScope{async} 로 병렬
}
```
- 공간 서비스 요청 헤더: `X-SDK-Key: <spaceKey>`, `Connection: close`. 콘솔 요청은 `X-SDK-Key: <sdkKey>` 만 붙인다. 타임아웃은 둘 다 20초.
- 콘솔 응답은 snake_case, 공간 서비스 응답은 camelCase 다. DTO 를 따로 두고 `@SerialName` 을 명시한다.
- `floorId` 는 JsonPrimitive 로 받는다. 문자열이거나 정수면 문자열로 바꾸고, 그 외 타입이면 Decoding 오류로 던진다.
- 존 정규화와 필터: spec §4.2 그대로 옮긴다(이름 중복은 먼저 온 것 유지).
- 도면 PNG 는 `data_url` 의 콤마 뒤를 `java.util.Base64.getDecoder()` 로 디코드한다.

- [ ] **Step 1: 실패하는 테스트** — iOS 4개 파일의 모든 케이스. MockWebServer 한 대로 두 호스트를 모두 흉내 낸다(`consoleBase=server.url("/api/sdk/v1")`, `spaceHost=server.url("/")`). `Dispatcher` 로 경로별 응답을 준다. 반드시 들어갈 것:
  - 앵커 경로가 404 거나 연결이 실패해도 `loadFloorState` 는 성공하고 `locatorsFetchFailed=true` 여야 한다.
  - `uwbMac="AA:BB:0B:4B"` 면 `address == 0x0B4B` 이다.
  - `clusterStatus` 가 `auto_done` 이면 isPlaced=true, `apply_failed` 면 false, 없으면 true 다.
  - 픽셀 폴리곤 변환: widthM=10, imgW=1000, imgH=500, origin(1,2), 점 (500,250) → (6.0, 4.5)
  - 콘솔이 `has_plan:false` 를 주면 공간 서비스 plan 을 부르지 않는다(요청 기록으로 확인).
  - 층 목록은 요청 1회로 끝난다(plan 을 부르지 않음).
- [ ] **Step 2~4:** FAIL → 구현 → PASS
- [ ] **Step 5: 커밋** — `feat: 공간 조회 — 건물·층·도면·로케이터·구역`

---

### Task 6: SSE (SseFrameParser · ConfigChange 파싱 · LiveConfigStream)

**포팅 원본:** `$IOS/Sources/OneS1ght/Runtime/{SseFrameParser.swift,ConfigChange.swift,LiveConfigStream.swift}`
**테스트 원본:** `SseFrameParserTests`, `ConfigChangeTests`, `LiveConfigStreamGapTests`, `LiveConfigStreamIngestTests`

**Files:**
- Create: `runtime/SseFrameParser.kt`, `runtime/LiveConfigStream.kt`, 그리고 `model/ConfigChange.kt` 에 `internal fun ConfigChange.Companion.parse(event: String, data: String): Pair<Int?, ConfigChange?>?` 추가
- Test: 위 4개

**Interfaces (Produces):**
```kotlin
internal class SseFrameParser { fun feedLine(line: String): SseFrame? }   // data class SseFrame(val event: String, val data: String)
internal class LiveConfigStream(
    private val http: OkHttpClient, private val baseUrl: String, private val apiKey: String,
    private val scope: CoroutineScope, private val onChange: (ConfigChange) -> Unit, private val log: (LogLevel, String) -> Unit,
    private val random: () -> Double = Math::random, private val delayFn: suspend (Long) -> Unit = { delay(it) }) {
    fun start(buildingId: String?, floorId: String?)
    fun stop()
    internal fun ingest(frame: SseFrame)          // 테스트에서 직접 부른다: seq 갭 검사 + onChange
    internal fun onConnected()                    // lastSeq=null, onChange(ResyncNeeded)
}
```
- 연결: `GET {baseUrl}/stream?buildingId=&floorId=`(null 파라미터는 생략), 헤더 `X-SDK-Key`, `Accept: text/event-stream`. OkHttp `readTimeout(60s)` 를 쓰고, `response.body.source()` 를 `readUtf8Line()` 으로 한 줄씩 읽는다. 빈 줄도 파서에 넘긴다.
- 백오프 규칙과 로그 문구(한국어 하드코딩: "live: 연결됨", "live: 연결 거절 <code>" 등)는 iOS 와 같다.

- [ ] **Step 1: 실패하는 테스트** — 4개 파일의 모든 케이스를 옮긴다. `LiveConfigStreamIngestTest` 는 iOS 테스트에 박힌 prod 실응답 바이트를 그대로 가져온다. MockWebServer 로 한 번 연결하는 통합 케이스도 1개 추가한다: 연결되면 `[ResyncNeeded, ZonesChanged("14"), RulesChanged("264")]` 순서로 와야 한다.
- [ ] **Step 2~4:** FAIL → 구현 → PASS
- [ ] **Step 5: 커밋** — `feat: 설정 변경 실시간 수신(SSE)`

---

### Task 7: IdentityStore · TrajectoryBuffer · SdkLogBuffer · 플랫폼 인터페이스

**포팅 원본:** `$IOS/Sources/OneS1ght/Identity/IdentityStore.swift`, `Runtime/{TrajectoryBuffer.swift,SdkLogBuffer.swift}`
**테스트 원본:** `IdentityStoreTests`, `TrajectoryBufferTests`

**Files:**
- Create: `runtime/Platform.kt`, `runtime/AndroidPlatform.kt`, `identity/IdentityStore.kt`, `runtime/TrajectoryBuffer.kt`, `runtime/SdkLogBuffer.kt`
- Test: `identity/IdentityStoreTest.kt`, `runtime/TrajectoryBufferTest.kt`, `runtime/SdkLogBufferTest.kt`

**Interfaces (Produces):**
```kotlin
public interface KeyValueStore { public fun getString(key: String): String?; public fun putString(key: String, value: String?);
                                 public fun getInt(key: String, default: Int): Int; public fun putInt(key: String, value: Int) }
public class InMemoryKeyValueStore : KeyValueStore
internal interface AppLifecycle { fun observe(onBackground: () -> Unit, onForeground: () -> Unit); fun stopObserving() }
internal class AndroidKeyValueStore(context: Context) : KeyValueStore     // SharedPreferences "co.onecheck.ones1ght.android"
internal class AndroidAppLifecycle : AppLifecycle                         // ProcessLifecycleOwner ON_STOP/ON_START
public class IdentityStore @JvmOverloads constructor(private val store: KeyValueStore,
        private val today: () -> String = { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }) {
    public fun newVisitorId(): String     // "v-%s-%03d", 키 onesight.visitor.date / onesight.visitor.seq
}
internal class TrajectoryBuffer(private val maxBatch: Int = 500) {
    val count: Int
    fun append(p: PositionPoint)
    suspend fun flush(send: suspend (List<PositionPoint>) -> Boolean): Boolean  // 오래된 것부터 500건씩, 실패하면 남기고 중단, 재진입이면 false
    fun clear()
}
internal class SdkLogBuffer(threshold: Int = 50, maxBatch: Int = 500, hardLimit: Int = 2000,
                            private val send: suspend (List<SdkLogEntry>) -> Boolean, private val scope: CoroutineScope) {
    fun append(e: SdkLogEntry)      // ERROR 면 즉시 flush 를 예약, 50건 이상이면 flush 예약, 2000건 넘으면 오래된 것부터 버림
    suspend fun flush()             // 떼어 낸 뒤 보내고, 실패하면 버린다
}
```
- `androidx.lifecycle:lifecycle-process` 의존을 추가한다.

- [ ] **Step 1: 실패하는 테스트**
  - iOS 두 파일의 모든 케이스
  - `SdkLogBufferTest`: ERROR 1건 → 즉시 1회 전송, 49건 → 전송 없음, 2001건 → 가장 오래된 1건 버림, 전송 실패 → 배치 폐기
  - `TrajectoryBufferTest`: 1200건 → 500/500/200. 두 번째 배치가 실패하면 700건이 남는다.
- [ ] **Step 2~4:** FAIL → 구현 → PASS
- [ ] **Step 5: 커밋** — `feat: 방문 ID·좌표 버퍼·로그 버퍼`

---

### Task 8: ZoneEngine (SDK 자체 구역 판정)

**포팅 원본:** `$IOS/Sources/OneS1ght/Zone/ZoneEngine.swift`(판정 루프), `Zone/UwbAreaJudge.swift`(DWELL 1회 규칙·reset·paramsIgnored 경고)

**Files:**
- Create: `zone/ZoneEngine.kt`
- Test: `zone/ZoneEngineTest.kt`

**Interfaces (Produces):**
```kotlin
public class ZoneEngine @JvmOverloads constructor(
    public val sampleIntervalMs: Long = 1_000, public val confirmCount: Int = 3,
    private val scheduler: DwellScheduler = CoroutineDwellScheduler(...)) {
    public var onEvent: ((ZoneEvent) -> Unit)?       // internal 호출자는 SessionCoordinator/Provider
    public var onLog: ((LogLevel, String) -> Unit)?
    public val zones: List<Zone>
    public val activeZoneId: String?
    public fun apply(zones: List<Zone>)              // reset + 존 교체, 존이 있으면 WARN uwb.paramsIgnored(count) 1회
    public fun ingest(p: Position, nowMs: Long)      // 직전 판단에서 sampleIntervalMs 가 안 지났으면 무시
    public fun reset()                               // streak·후보·활성 존 비우고 dwell 대기 취소
}
public fun interface DwellScheduler { public fun schedule(delayMs: Long, action: () -> Unit): Cancellable }
public fun interface Cancellable { public fun cancel() }
```
- 판정 규칙은 spec §6 그대로다. DWELL 은 IN 확정 시각부터 `dwellSeconds*1000` 뒤에 여전히 같은 활성 존이면 1회 발행한다. `seconds = dwellSeconds.toDouble()` 이다.

- [ ] **Step 1: 실패하는 테스트** (가짜 스케줄러로 즉시 제어)
```kotlin
private val sq = listOf(Position(0.0,0.0), Position(10.0,0.0), Position(10.0,10.0), Position(0.0,10.0))
private val a = Zone("za","A", sq, dwellSeconds = 5)
@Test fun enterNeedsThreeConsecutiveSamples() {
    val ev = mutableListOf<ZoneEvent>(); val e = ZoneEngine(scheduler = fake).apply { onEvent = { ev += it }; apply(listOf(a)) }
    e.ingest(Position(5.0,5.0), 0); e.ingest(Position(5.0,5.0), 1000)
    assertTrue(ev.isEmpty())
    e.ingest(Position(5.0,5.0), 2000)
    assertEquals(listOf("in-za-2"), ev.map { it.id })
}
@Test fun samplesInsideOneSecondAreIgnored() { /* 0,100,200,…900ms 로 10번 → 판단 1회 → IN 없음 */ }
@Test fun boundaryJitterDoesNotFlap() { /* IN 뒤에 안/밖/안/밖 반복 → OUT 없음 */ }
@Test fun exitAfterThreeOutsideSamples() { /* IN 뒤 밖 3회 → OUT 1개 */ }
@Test fun dwellFiresOnceAfterDwellSeconds() { /* IN, fake.advance(5000) → DWELL 1개, advance(10000) → 추가 없음 */ }
@Test fun exitCancelsPendingDwell() { }
@Test fun applyResets() { }
@Test fun resetClearsActiveZone() { }
@Test fun firstMatchingZoneWinsIgnoringPriority() { }
@Test fun noDwellWhenDwellSecondsNullOrZero() { }
@Test fun paramsIgnoredWarnedOncePerApplyWithZones() { }
```
- [ ] **Step 2~4:** FAIL → 구현 → PASS
- [ ] **Step 5: 커밋** — `feat: 구역 판정 엔진 — 1초 샘플·3회 확정·DWELL 1회`

---

### Task 9: PositioningProvider 계약 · Mock · 엔진 상태 기계 · 오류 매핑

**포팅 원본:** `$IOS/Sources/OneS1ght/Positioning/{PositioningProvider.swift,MockPositioningProvider.swift}`, `UwbPositioningProvider.swift` 의 phase·startAfterStop·pause 로직(약 480-605행), `git show b804f3b:Sources/OneS1ght/Positioning/UwbPositioningProvider.swift`(세션 흐름)
**테스트 원본:** `PositioningPauseTests`, `UwbEngineErrorMappingTests`(→ 레인징 사유 매핑)

**Files:**
- Create: `positioning/PositioningProvider.kt`, `positioning/MockPositioningProvider.kt`, `positioning/EngineStateMachine.kt`, `positioning/RangingErrorMapping.kt`, `positioning/DeviceCapability.kt`
- Test: `positioning/EngineStateMachineTest.kt`(PositioningPause 케이스 포함), `positioning/RangingErrorMappingTest.kt`, `positioning/MockPositioningProviderTest.kt`

**Interfaces (Produces):**
```kotlin
public data class PositioningConfig @JvmOverloads constructor(
    val anchors: Map<Int, DoubleArray> = emptyMap(), val sessionId: Int? = null, val zones: List<Zone> = emptyList())
public data class PositioningDiagnostic(val registeredCount: Int, val receivedCount: Int, val matchedCount: Int,
    val missingAddresses: List<Int>, val hasFix: Boolean, val canAttributePerAnchor: Boolean = true) {
    val missingLabel: String   // "0x%04X" 콤마 연결
}
public interface PositioningProvider {
    public var delegate: PositioningProviderDelegate?
    public fun start(); public fun stop()
    public val positioningDiagnostic: PositioningDiagnostic? get() = null
    public fun apply(buildingId: String, floorId: String) {}
    public fun apply(config: PositioningConfig) {}
    public fun reloadGeofences() {}
    public val isPaused: Boolean get() = false
    public fun pause() {}; public fun resume() {}
}
public interface PositioningProviderDelegate {
    public fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long)
    public fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long)
    public fun onEnter(provider: PositioningProvider, buildingId: String)
    public fun onReport(provider: PositioningProvider, code: SdkErrorCode, context: String) {}
}
public class MockPositioningProvider : PositioningProvider {
    public var isRunning: Boolean; public var appliedBuildingId: String?; public var appliedFloorId: String?
    public var appliedConfig: PositioningConfig?; public var reloadGeofencesCount: Int
    public fun simulateEnter(buildingId: String); public fun simulatePosition(c: Coordinates, floorId: String?, atMs: Long)
    public fun simulateZone(zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long)
}
internal enum class Phase { IDLE, STARTING, SEARCHING, TRACKING, STOPPING }
internal class EngineStateMachine(private val openSession: () -> Unit, private val closeSession: () -> Unit,
                                  private val log: (LogLevel, String) -> Unit) {
    val phase: Phase; val isRunning: Boolean; val isPaused: Boolean
    fun start()          // STOPPING 이면 startAfterStop=true + uwb.startQueued, IDLE 이면 openSession, 그다음 running=true, paused=false
    fun stop()           // startAfterStop=false, closeSession, phase=STOPPING
    fun onOpened()       // STARTING→SEARCHING
    fun onFirstFix()     // →TRACKING
    fun onClosed()       // →IDLE, startAfterStop 이면 start()
    fun pause(); fun resume(): Boolean   // resume 이 true 를 돌려주면 호출자가 판정기를 reset 한다
    fun acceptsPosition(): Boolean       // isRunning && !isPaused
}
internal object RangingErrorMapping { fun code(reason: Int, security: Boolean): SdkErrorCode? }
    // security → E2003; REASON_UNSUPPORTED → E2002; REASON_SYSTEM_POLICY → E2003; REASON_NO_PEERS_FOUND → E4002;
    // REASON_UNKNOWN/REASON_REMOTE_REQUEST → E4001; REASON_LOCAL_REQUEST → null(정상 종료)
    // reason 상수는 android.ranging 값을 쓰지 않고 같은 정수 상수를 내부에 복제한다(JVM 테스트용). 값은 javap 로 확인해 적는다.
internal interface DeviceCapability { val sdkInt: Int; suspend fun supportsDlTdoa(): Boolean }
```

- [ ] **Step 1: 실패하는 테스트**
  - PositioningPauseTests 의 모든 케이스를 `EngineStateMachine` 대상으로 옮긴다.
  - 경합 케이스:
    - `stop()` 직후 `start()` → `openSession` 은 `onClosed()` 이후에 1회
    - `stop(); start(); stop(); onClosed()` → 다시 열지 않음
  - 오류 매핑 표 전부
  - Mock 이 delegate 로 이벤트를 넘기는지
- [ ] **Step 2~4:** FAIL → 구현 → PASS
- [ ] **Step 5: 커밋** — `feat: 측위 제공자 계약·Mock·엔진 상태 기계`

---

### Task 10: SessionCoordinator

**포팅 원본:** `$IOS/Sources/OneS1ght/Runtime/SessionCoordinator.swift` 전체(668행) — 줄 단위로 대응시킨다. 옛 수신 점검·존 전송 흐름은 `b804f3b` 판도 참고한다.
**테스트 원본:** `SessionCoordinatorTests`, `SessionCoordinatorLiveTests`, `RestartAfterStopTests`, `SdkConfigResolutionTests`, `ReceptionCheckTests`, `GeofenceReloadTests`(id 집합 비교 부분)

**Files:**
- Create: `runtime/SessionCoordinator.kt`
- Test: 위 6개 → `runtime/*Test.kt`

**Interfaces:**
- Consumes: Task 2~9 전부
- Produces:
```kotlin
internal class SessionCoordinator(
    val api: ApiClient, private val identity: IdentityStore, private val appId: String,
    private val scope: CoroutineScope,                 // 주입된 메인 디스패처 스코프
    private val lifecycle: AppLifecycle?, private val clock: () -> Long = System::currentTimeMillis,
    private val spaceClientFactory: (sdkKey: String, spaceKey: String) -> SpaceServiceClient = { s, k -> SpaceServiceClient(s, k, api.http) },
    private val liveFactory: ((ConfigChange) -> Unit) -> LiveConfigStream? = { … }) : PositioningProviderDelegate {
    var profileId: String?
    var onTriggers: ((String, List<Trigger>) -> Unit)?; var onPosition: ((Coordinates) -> Unit)?
    var onConfigChange: ((ConfigChange) -> Unit)?;     var onLog: ((LogLevel, String) -> Unit)?
    val isPrepared: Boolean; val isRunning: Boolean
    var positionRateHz: Int; var googleMapKey: String?; var spaceClient: SpaceServiceClient?
    val currentFloor: Floor?; val currentBuildingId: String?; val floorState: FloorState?
    suspend fun prepare()                         // verify → /config → positioningDisabled 검사 (iOS 순서)
    suspend fun retryKeyResolutionIfNeeded()
    suspend fun setFloorMap(floor: Floor?, buildingId: String?)
    suspend fun refreshZones(): List<Zone>
    suspend fun start(provider: PositioningProvider)
    suspend fun stop()                            // stopInFlight: Deferred<Unit>? 로 합류
    suspend fun flush()                           // OneS1ght.send()
    fun empty()
    suspend fun teardown()
    fun report(code: SdkCode, ctx: String)
    internal fun geofencesChanged(old: List<Zone>, new: List<Zone>): Boolean
    internal fun floorFilterChanged(...): Boolean; internal val liveStreamWanted: Boolean
}
```
- 모든 로그 키와 report ctx 문자열(예: `"building=<b> floor=<f> locators=<n> zones=<m>"`, `"points=N"`, `"zone=<id> status=<IN|OUT> dropped"`)은 iOS 원문 그대로다.
- 수신 점검은 `scope.launch { delay(7_000); … }` 로 하고 stop 때 취소한다. flush 타이머는 60초 반복이다.

- [ ] **Step 1: 실패하는 테스트** — 6개 파일의 **모든** 케이스. `runTest` + `StandardTestDispatcher`, MockWebServer, `MockPositioningProvider` 를 쓴다. 반드시 들어갈 것:
```kotlin
@Test fun startDuringStopEndsRunning() = runTest {          // RestartAfterStop
    val c = prepared(); val p = MockPositioningProvider(); c.profileId = "p"
    c.start(p); val stopJob = launch { c.stop() }; runCurrent()
    c.start(p); stopJob.join(); advanceUntilIdle()
    assertTrue(c.isRunning); assertTrue(p.isRunning)
}
@Test fun concurrentStopsUploadOnce() = runTest { /* 좌표 3개 쌓고 stop 두 번 동시 → /positioning/logs 요청 1회 */ }
@Test fun duplicateStartKeepsVisitorId() = runTest { }
@Test fun zoneEventRetriesOnceOnNetworkError() = runTest { /* 1회차 연결 끊김(SocketPolicy.DISCONNECT_AT_START), 2회차 200 → onTriggers 1회 */ }
@Test fun downsampleAt4HzKeeps4to6of20() = runTest { }
@Test fun rateClamp9999To100() = runTest { }
@Test fun receptionCheckReportsMissingAnchors() = runTest { /* diag missing=[0x0B4B] → E4003 ctx 에 "0x0B4B" */ }
@Test fun configFailureDoesNotBlockInit() = runTest { /* /config 500 → prepare 성공, E1007 reason=config_failed */ }
@Test fun positioningDisabledStillStoresConsoleKeys() = runTest { }
```
- [ ] **Step 2~4:** FAIL → 구현 → PASS
- [ ] **Step 5: 커밋** — `feat: 세션 조정자 — 초기화·층·측위·전송·수신 점검`

---

### Task 11: 공개 파사드 (OneS1ght · FloorSession · Java 브리지 · 권한 · 기기 판정)

**포팅 원본:** `$IOS/Sources/OneS1ght/OneS1ght.swift`, `Runtime/FloorSession.swift`, `Positioning/PositioningPermission.swift`
**테스트 원본:** `OneS1ghtTests`, `SdkGateTests`

**Files:**
- Modify: `OneS1ght.kt`
- Create: `FloorSession.kt`, `Callbacks.kt`, `JavaBridge.kt`, `positioning/AndroidDeviceCapability.kt`, `positioning/PositioningPermission.kt`
- Test: `OneS1ghtTest.kt`, `SdkGateTest.kt`, `src/test/java/co/onecheck/ones1ght/android/JavaInteropTest.java`

**Interfaces (Produces):**
```kotlin
public interface Callback<T> { public fun onSuccess(result: T); public fun onError(error: Throwable) }
public fun interface ZoneListener { public fun onZone(zone: Zone) }
public fun interface DwellListener { public fun onDwell(zone: Zone, seconds: Double) }
public fun interface PositionListener { public fun onPosition(coordinates: Coordinates) }
public fun interface TriggersListener { public fun onTriggers(zoneId: String, triggers: List<Trigger>) }
public fun interface ConfigChangeListener { public fun onConfigChanged(change: ConfigChange) }
public fun interface DebugLogListener { public fun onLog(level: LogLevel, message: String) }
public enum class DeviceAvailability { AVAILABLE, OS_VERSION_TOO_LOW, DEVICE_NOT_SUPPORTED }
public enum class PermissionStatus { AUTHORIZED, DENIED, UNSUPPORTED }
public sealed class SdkError(message: String) : Exception(message) {
    public object NotInitialized; NotIdentified; PositioningDisabled; DeviceNotSupported; OsVersionTooLow   // 각각 : SdkError(...)
    public val code: SdkErrorCode
}
public object OneS1ght {
    public const val SDK_VERSION: String = "0.0.1"
    @JvmStatic public var onDebugLog: DebugLogListener?
    @JvmStatic public val isInitialized: Boolean
    @JvmStatic public val deviceAvailability: DeviceAvailability
    @JvmStatic public val isDeviceAvailable: Boolean
    @JvmStatic public val googleMapKey: String?
    @JvmStatic public fun setLanguage(code: String?)
    public suspend fun permissions(activity: ComponentActivity): PermissionStatus
    @JvmStatic public fun permissions(activity: ComponentActivity, callback: Callback<PermissionStatus>)
    @JvmOverloads public suspend fun initialize(context: Context, sdkKey: String, baseUrl: String = ApiClient.DEFAULT_BASE_URL)
    @JvmStatic @JvmOverloads public fun initialize(context: Context, sdkKey: String, baseUrl: String = ApiClient.DEFAULT_BASE_URL, callback: Callback<Void?>)
    //  ↑ 기본 인자 뒤에 callback 을 두면 Java 오버로드가 어긋난다. Java 판은 (context, sdkKey, callback) 과 (context, sdkKey, baseUrl, callback) 두 개를 손으로 둔다.
    public suspend fun reset();                                    @JvmStatic public fun reset(callback: Callback<Void?>)
    public suspend fun buildings(): List<Building>;                @JvmStatic public fun buildings(callback: Callback<List<Building>>)
    public suspend fun building(buildingId: String): Building;     @JvmStatic public fun building(buildingId: String, callback: Callback<Building>)
    public suspend fun floors(buildingId: String): List<Floor>;    …
    public suspend fun floor(buildingId: String, floorId: String): Floor; …
    public suspend fun zones(buildingId: String, floorId: String): List<Zone>; …
    public suspend fun zone(buildingId: String, floorId: String, zoneId: String): Zone; …
    public suspend fun locators(buildingId: String, floorId: String): FloorLocators; …
    public suspend fun setFloorMap(floor: Floor?, buildingId: String? = null); (Java: (floor, cb), (floor, buildingId, cb))
    public suspend fun refreshZones(): List<Zone>; …
    @JvmStatic public fun floorSession(): FloorSession              // 초기화 전이면 SdkError.NotInitialized 를 던진다
    public suspend fun createProfile(attributes: Map<String,String>): String; …
    public suspend fun getProfile(profileId: String): Map<String,String>; …
    public suspend fun putProfile(profileId: String, attributes: Map<String,String>); …
    public suspend fun deleteProfile(profileId: String); …
    public suspend fun send(); @JvmStatic public fun send(callback: Callback<Void?>)
    @JvmStatic public fun empty()
    @JvmStatic public fun identify(profileId: String?)
    // 테스트 전용 주입
    internal var deviceCapability: DeviceCapability; internal var dispatcher: CoroutineDispatcher
    internal var platformFactory: (Context) -> Pair<KeyValueStore, AppLifecycle?>
}
public class FloorSession internal constructor() {
    public var onZoneEnter: ZoneListener?; public var onZoneExit: ZoneListener?; public var onZoneDwell: DwellListener?
    public var onPosition: PositionListener?; public var onTriggers: TriggersListener?; public var onConfigChanged: ConfigChangeListener?
    public val floor: Floor?; public val isRunning: Boolean; public val isPaused: Boolean
    public suspend fun begin();                                  public fun begin(callback: Callback<Void?>)
    public suspend fun begin(provider: PositioningProvider);     public fun begin(provider: PositioningProvider, callback: Callback<Void?>)
    public fun pause(); public fun resume()
    public suspend fun end();                                    public fun end(callback: Callback<Void?>)
}
internal object JavaBridge { fun <T> run(callback: Callback<T>, block: suspend () -> T) }  // 메인 스코프에서 실행, 결과·예외를 메인에서 전달
```
- `permissions(activity)`
  - `deviceAvailability != AVAILABLE` 면 `UNSUPPORTED`
  - 이미 둘 다 허용됐으면 `AUTHORIZED`
  - 아니면 `activity.activityResultRegistry.register("onesight.permissions", RequestMultiplePermissions())` 로 요청한다. 30초 타임아웃이면 `DENIED` 다.
- `AndroidDeviceCapability.supportsDlTdoa()`: `SDK_INT >= 37` 이면 `context.getSystemService(RangingManager::class.java)` 에 `registerCapabilitiesCallback` 을 걸고, 첫 콜백의 `uwbCapabilities?.isDlTdoaSupported == true` 를 본다. 5초 타임아웃이면 false.
- `FloorSession.begin()` 이 만드는 내장 provider 는 Task 12 의 `UwbPositioningProvider` 다. 이 태스크에서는 `internal var builtInProviderFactory: (Context) -> PositioningProvider` 자리만 만든다(기본값은 Task 12 에서 연결). 테스트는 Mock 을 주입한다.

- [ ] **Step 1: 실패하는 테스트**
  - `OneS1ghtTest`, `SdkGateTest` 의 모든 케이스(Context 는 `mock` 대신 테스트용 `platformFactory` 주입으로 피한다. `ContextWrapper(null)` 을 쓴다)
  - `JavaInteropTest.java`:
```java
package co.onecheck.ones1ght.android;
import org.junit.Test; import static org.junit.Assert.*;
import java.util.List; import java.util.Collections;
public class JavaInteropTest {
    @Test public void fullFlowCompilesAndRunsFromJava() throws Exception {
        JavaInteropHarness h = JavaInteropHarness.start();   // Kotlin 테스트 헬퍼: MockWebServer + Main 디스패처 대체 + Mock provider
        final Object[] got = new Object[1];
        OneS1ght.initialize(h.context(), "ock_test", h.baseUrl(), new Callback<Void>() {
            @Override public void onSuccess(Void r) { got[0] = "ok"; }
            @Override public void onError(Throwable e) { got[0] = e; } });
        h.drain(); assertEquals("ok", got[0]);
        OneS1ght.identify("p1");
        OneS1ght.buildings(new Callback<List<Building>>() {
            @Override public void onSuccess(List<Building> r) { got[0] = r; }
            @Override public void onError(Throwable e) { got[0] = e; } });
        h.drain(); assertTrue(got[0] instanceof List);
        FloorSession s = OneS1ght.floorSession();
        s.setOnZoneEnter(zone -> {}); s.setOnPosition(c -> {}); s.setOnZoneDwell((zone, sec) -> {});
        OneS1ght.setOnDebugLog((level, msg) -> {});
        s.begin(h.mockProvider(), new Callback<Void>() { @Override public void onSuccess(Void r) {} @Override public void onError(Throwable e) { fail(e.toString()); } });
        h.drain(); assertTrue(s.isRunning());
        s.end(new Callback<Void>() { @Override public void onSuccess(Void r) {} @Override public void onError(Throwable e) {} });
        h.drain(); assertFalse(s.isRunning());
        try { throw new SdkError.NotInitialized(); } catch (SdkError e) { assertEquals("E1001", e.getCode().getCode()); }
        h.close();
    }
}
```
  - `SdkError.NotInitialized` 가 Java 에서 `new` 로 만들어지지 않는 설계라면(object) `SdkError.NotInitialized.INSTANCE` 로 테스트를 바꾼다. **어느 쪽이든 Java 에서 자연스럽게 읽히는지가 기준이다.** object 를 쓰면 `catch (SdkError e)` + `e == SdkError.NotInitialized.INSTANCE` 로 쓴다.
- [ ] **Step 2~4:** FAIL → 구현 → PASS
- [ ] **Step 5: 커밋** — `feat: 공개 API — OneS1ght·FloorSession, Java 콜백 판 포함`

---

### Task 12: 엔진 어댑터 (UwbPositioningProvider · DlTdoaSessionConfig)

**포팅 원본:**
- `git -C $IOS show b804f3b:Sources/OneS1ght/Positioning/UwbPositioningProvider.swift` — 세션 흐름·진단·좌표 전달
- `git -C $IOS show b804f3b:Sources/OneS1ght/Zone/PrmZoneEngine.swift` — 판정 입력 규칙
- `$IOS/Sources/OneS1ght/Positioning/UwbPositioningProvider.swift` — 현재 판: pause·phase·로그 키

**Files:**
- Create: `positioning/DlTdoaSessionConfig.kt`, `positioning/UwbPositioningProvider.kt`
- Modify: `FloorSession.kt`(기본 `builtInProviderFactory` 연결)
- Test: `positioning/UwbProviderLogicTest.kt`(안드로이드 객체를 쓰지 않는 부분만)

**Interfaces (Produces):**
```kotlin
/** 세션 설정값을 모두 이 파일에서 만든다. sessionId 외 값은 미결(spec §10-1). */
internal object DlTdoaSessionConfig {
    @RequiresApi(37) fun preference(sessionId: Int): RangingPreference =
        RangingPreference.Builder(RangingPreference.DEVICE_ROLE_DT_TAG,
            RawDtTagRangingConfig.Builder(
                RawRangingDevice.Builder()
                    .setRangingDevice(RangingDevice.Builder().build())
                    .setDlTdoaRangingParams(DlTdoaRangingParams.Builder(sessionId).build())
                    .build()).build())
            .setSessionConfig(SessionConfig.Builder().build())
            .build()
    const val BLOCK_TIMEOUT_MS = 260L
    const val MIN_RSSI = -90
}
public class UwbPositioningProvider internal constructor(
    private val context: Context, private val zoneEngine: ZoneEngine,
    private val main: CoroutineDispatcher, private val clock: () -> Long) : PositioningProvider {
    override var delegate: PositioningProviderDelegate?
    public var onZoneEvent: ((ZoneEvent) -> Unit)?; public var onLog: ((LogLevel, String) -> Unit)?
    override fun apply(buildingId: String, floorId: String); override fun apply(config: PositioningConfig)
    override fun start(); override fun stop(); override fun pause(); override fun resume(); override val isPaused: Boolean
    override val positioningDiagnostic: PositioningDiagnostic?
}
internal class AnchorTracker { fun register(addresses: Set<Int>); fun seen(address: Int); fun diagnostic(hasFix: Boolean): PositioningDiagnostic }
```
- 흐름: spec §5 그림 그대로.
  - `onDlTdoaResults(peer, m)` → `peer.dlTdoaUwbAddress` 의 하위 2바이트로 `AnchorTracker.seen` → `accumulator.add`
  - 블록 콜백(엔진 스레드) → `positioner.update(block)` → null 이 아니면 `withContext(main)` 로 넘긴다.
  - 메인 스레드에서 `acceptsPosition()` 이면 `delegate.onPosition(Coordinates(x,y,z), floorId, now)` 와 `zoneEngine.ingest(Position(x,y), now)`
- `zoneEngine.onEvent`
  - IN/OUT → `delegate.onZone(zone.id, status, floorId, at)` + `onZoneEvent`
  - DWELL → `onZoneEvent` 만
  - pause 중에는 차단한다.
- `apply(config)`
  - anchors → `positioner.applyAnchorCoordinates` + `AnchorTracker.register`
  - zones → `zoneEngine.apply`
  - sessionId 가 바뀌었고 측위 중이면 세션을 다시 연다.
- sessionId 가 null 이면 start 를 거부하고, E3003 로그를 남긴 뒤 phase 는 IDLE 을 유지한다.
- `onOpenFailed(reason)`/`onClosed(reason)` 는 `RangingErrorMapping` 으로 코드를 구해 `delegate.onReport` 로 보내고, 상태 기계 `onClosed()` 를 부른다. `start()` 가 던지는 `SecurityException` 은 E2003 이다.
- 첫 좌표 이후 5초에 진단 로그 1회를 남긴다(b804f3b 판).
- `resume()` 이 true 면 `zoneEngine.reset()`.
- 로그 키는 SdkLocalization.json 에 있는 `uwb.*` 키만 쓴다. 새 키가 필요하면 ko/ja/en 3개 언어를 모두 추가한다.

- [ ] **Step 1: 실패하는 테스트** — `UwbProviderLogicTest`: `AnchorTracker` 진단(등록 3, 수신 2 → missing 1개, label `0x0B4B`), 주소 하위 2바이트 추출 함수(`bytes [0x0B,0x4B]` → `0x0B4B`, 8바이트 확장 주소 → 마지막 2바이트).
- [ ] **Step 2~4:** FAIL → 구현 → PASS
- [ ] **Step 5: 컴파일 확인** — `./gradlew :onesight:compileDebugKotlin`(스텁으로 컴파일). 계정이 있으면 `-PgeoplanNexus*` 경로로도 한 번.
- [ ] **Step 6: 커밋** — `feat: UWB 측위 어댑터 — 레인징 세션·블록 누적·좌표 산출`

---

### Task 13: 연동 산출물 (README · Snippets · Migrations · CHANGELOG · Scripts · CI)

**포팅 원본:** `$IOS/README*.md`, `$IOS/Snippets/ios.json`, `$IOS/Migrations/ios.json`, `$IOS/CHANGELOG.md`(형식), `$IOS/Scripts/*`, `$IOS/.github/workflows/*`, `$IOS/RELEASING.md`
**테스트 원본:** `SnippetsTests`, `MigrationsTests`

**Files:**
- Create: `README.md`(영어, 기존 대체), `README.ko.md`, `README.ja.md`, `Snippets/android.json`, `Migrations/android.json`, `CHANGELOG.md`, `RELEASING.md`, `Scripts/*.sh`, `.github/workflows/{test,prerelease,release}.yml`
- Test: `onesight/src/test/kotlin/.../artifacts/SnippetsTest.kt`, `MigrationsTest.kt` — 레포 루트 파일은 `File(System.getProperty("user.dir")).parentFile` 로 찾는다(모듈 디렉터리 기준).

**내용 규칙:**
- **Snippets**
  - 스키마·step id 12개·순서는 iOS 와 같다. `platform:"android"`, `language:"kotlin"`, `sdkVersion:"0.0.1"`.
  - `requirements`: `positioning.os="Android 17 (API 37)+"`, `positioning.device="UWB DL-TDoA 지원 기기"`, `package.os="Android 8.1 (API 27)+"`, `build.compileSdk="37"`
  - `install` step 은 Gradle 의존 한 줄과 Manifest 병합 안내다. 배포 채널 미정이라 `co.onecheck.ones1ght:android:0.0.1` 좌표만 쓰고, `notes` 에 "배포 저장소는 담당자 안내"라고 적는다.
  - `permission` step 은 `OneS1ght.permissions(activity)`.
  - Java 예시는 `files[]` 에 `language:"java"` 로 initialize·begin 두 개를 넣는다.
- **SnippetsTest**: iOS SnippetsTests 규칙을 전부 적용한다. 금지어 목록은 Global Constraints 의 것에 iOS 의 폐기 API 이름을 더한다. 핵심 API 문자열 검사는 `OneS1ght.initialize(`, `createProfile(`, `identify(`, `buildings(`, `floors(`, `setFloorMap(`, `floorSession(`, `.begin(`, `OneS1ght.deviceAvailability` 이다.
- **Migrations**: `{"platform":"android","currentVersion":"0.0.1","_readme":[…],"migrations":[]}`. 첫 버전이므로 빈 체인을 허용하도록 테스트를 조정한다(비어 있으면 체인 검사를 건너뛰고 currentVersion 만 검사).
- **README**
  - 흐름: 설치 → Manifest 권한 → initialize → deviceAvailability → permissions → createProfile/identify → buildings/floors/setFloorMap(**필수**) → floorSession().begin → end
  - Kotlin·Java 예시를 둘 다 넣는다. 배치 정책, 제약(포그라운드 전용, 버퍼는 메모리)을 적는다.
  - 문의: onesight-support@onecheck.co.kr. 공급사 이름은 쓰지 않는다.
- **CHANGELOG**: `## [0.0.1] — 2026-09-28` 에 첫 공개 사양 요약과 iOS 와 다른 점 3가지(Context 인자, permissions(activity), 층 지정 필수)를 적는다.
- **Scripts**: iOS 스크립트를 옮긴다.
  - `sdk-version.sh` 는 `OneS1ght.kt` 에서 `SDK_VERSION` 을 grep 한다.
  - `check-release.sh` 는 `swift test` 대신 `./gradlew :onesight:testDebugUnitTest` 를 돌린다.
  - `verify-tag.sh` 는 `OneS1ght-Android-SDK/<tag>/Snippets/android.json` 을 확인한다.
- **CI**: iOS 워크플로를 옮긴다. `on:` 은 **`workflow_dispatch:` 만** 둔다(주석: "Actions 한도 보호 — 켜려면 push/pull_request 추가"). 러너는 `ubuntu-latest`, `setup-java 17`, `android-actions/setup-android`, `sdkmanager "platforms;android-37.0"`. Nexus 비밀값은 `secrets.GEOPLAN_NEXUS_*` 로 받는다.

- [ ] **Step 1:** SnippetsTest·MigrationsTest 작성 → FAIL
- [ ] **Step 2:** 파일 작성 → PASS
- [ ] **Step 3:** `bash Scripts/check-release.sh 0.0.1` → 통과(테스트 포함)
- [ ] **Step 4: 커밋** — `docs: README·스니펫·마이그레이션·릴리스 스크립트`

---

### Task 14: 전체 검증

- [ ] **Step 1:** `./gradlew clean :onesight:testDebugUnitTest :onesight:assembleDebug :onesight:lintDebug` → 전부 초록. 경고 중 공개 API 관련(`explicitApi`)은 0 이어야 한다.
- [ ] **Step 2:** 공급사 이름 누출 검사
```bash
grep -rnE 'Geoplan|geoplan|gpa-|gpi-|GeoSpace|Geospace|ihub' onesight/src/main README*.md Snippets | grep -v 'SPACE_HOST'
```
Expected: 출력 없음
- [ ] **Step 3:** AAR 내용 확인 — `unzip -l onesight/build/outputs/aar/onesight-debug.aar` 에 `engine-stub` 클래스(`kr/geoplan/...`)가 **없어야** 한다(compileOnly).
- [ ] **Step 4:** 계정이 있으면 `./gradlew :onesight:assembleRelease` 후 AAR `libs/` 에 엔진 jar 가 들어 있는지 확인한다. 없으면 이 단계를 건너뛰고 보고에 적는다.
- [ ] **Step 5:** 결과 요약을 `docs/superpowers/plans/2026-09-28-android-sdk.md` 끝의 "실행 기록" 절에 적고 커밋한다 — `chore: 0.0.1 검증 기록`
