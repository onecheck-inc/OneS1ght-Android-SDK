package co.onecheck.ones1ght.android

//
//  OneS1ght.kt
//  공개 진입점 — 호스트 앱이 보는 유일한 표면.
//
//  설계 규칙: "문은 object, 부품은 인스턴스".
//  · 문(이 object) — 앱 전체에 하나뿐인 진입점. 멤버는 전부 @JvmStatic 이라 Java 에서도
//    `OneS1ght.buildings(cb)` 로 부른다. suspend 판은 @JvmSynthetic 으로 Java 에서 숨긴다
//    (Java 에는 Callback 판만 보여야 `OneS1ght.INSTANCE.buildings(continuation)` 같은 게 안 뜬다).
//    이 규칙은 JavaApiSurfaceTest 가 지킨다.
//  · 부품(coordinator·ApiClient·엔진) — 키 교체·reset 때 갈아끼우는 인스턴스. 밖에 안 보임.
//  하나만 존재해야 하는 이유: UWB 라디오·존 엔진·방문 ID·좌표 버퍼가 기기당 1개라
//  세션이 여럿이면 서로 충돌한다.
//
//  사용 (호스트 앱):
//    // ① 앱 시작 시 — 키 검증 + 테넌트 설정 수신 (기기 게이트는 여기 없다 — ④ begin() 이 담당)
//    OneS1ght.initialize(applicationContext, "ock_…")
//    // ② 공간 선택 — 필수. 이걸 안 하면 좌표가 나오지 않는다
//    val buildings = OneS1ght.buildings()
//    val floors = OneS1ght.floors(buildings[0].id)
//    OneS1ght.setFloorMap(floors[0], buildingId = buildings[0].id)
//    // ③ 프로필 연결 — createProfile 로 발급받아 앱이 보관한 값
//    OneS1ght.identify("pf_8a3c")
//    // ④ 매장 진입 시 — 측위 가동
//    val session = OneS1ght.floorSession()
//    session.onTriggers = TriggersListener { zoneId, triggers -> … }
//    session.begin()
//    session.end()                                       // 재시작 가능 (초기화 유지)
//
//  스레드: 코어 상태는 [dispatcher](운영: Dispatchers.Main.immediate) 한 곳에서만 바뀐다.
//  suspend 판은 어느 디스패처에서 불러도 안에서 코어 디스패처로 옮겨 탄다. Java 판은
//  [JavaBridge] 가 같은 디스패처에서 돌리고 결과를 메인에서 콜백으로 넘긴다.
//
//  포팅 원본: OneS1ght.swift.
//

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import co.onecheck.ones1ght.android.model.Building
import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.model.FloorLocators
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.network.ApiClient
import co.onecheck.ones1ght.android.network.ApiError
import co.onecheck.ones1ght.android.identity.IdentityStore
import co.onecheck.ones1ght.android.positioning.AndroidDeviceCapability
import co.onecheck.ones1ght.android.positioning.DeviceCapability
import co.onecheck.ones1ght.android.positioning.PositioningPermission
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.createBuiltInProvider
import co.onecheck.ones1ght.android.runtime.AndroidAppLifecycle
import co.onecheck.ones1ght.android.runtime.AndroidKeyValueStore
import co.onecheck.ones1ght.android.runtime.AppLifecycle
import co.onecheck.ones1ght.android.runtime.KeyValueStore
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import co.onecheck.ones1ght.android.runtime.SessionCoordinator
import co.onecheck.ones1ght.android.space.SpaceServiceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 측위 가능 여부 — 사유 포함. 앱이 사전 안내 UI 를 분기할 때 쓴다. */
public enum class DeviceAvailability {
    /** 측위 가능. */
    AVAILABLE,

    /** Android 17(API 37) 미만 — "OS 업데이트 후 사용 가능" 안내. */
    OS_VERSION_TOO_LOW,

    /** UWB(DL-TDoA) 미지원 기기. */
    DEVICE_NOT_SUPPORTED,
}

/** 측위 권한 상태. */
public enum class PermissionStatus {
    /** 사용 가능 — 측위를 시작할 수 있다. */
    AUTHORIZED,

    /** 사용자가 거부했다(30초 무응답 포함). 설정 앱으로 안내해야 한다. */
    DENIED,

    /** 이 기기·OS 에서는 측위 자체가 불가 — 물어볼 것도 없다. */
    UNSUPPORTED,
}

/** 측위(DL-TDoA 레인징)에 필요한 최소 API 레벨 — Android 17. */
internal const val MIN_POSITIONING_SDK: Int = 37

public object OneS1ght {

    /** SDK 버전 (verify 등 서버 요청에 실림). */
    public const val SDK_VERSION: String = "0.0.1"

    // MARK: - 콜백

    /**
     * SDK 내부 활동 로그 (디버그용) — 등급과 글자가 함께 온다. 메인 스레드에서 불린다.
     * initialize 보다 먼저 등록해야 초기화 단계 로그를 놓치지 않는다. 운영에선 미등록 권장.
     */
    @JvmStatic
    @Volatile
    public var onDebugLog: DebugLogListener? = null

    // MARK: - 상태

    /**
     * 세션 가능 상태인가 (initialize 성공 = 키 유효 + 설정 로드됨). 기기 지원 여부는 별개 —
     * [deviceAvailability] 로 물어보거나 begin() 의 예외로 확인한다.
     */
    @JvmStatic
    public val isInitialized: Boolean
        get() = coordinator?.isPrepared ?: false

    /**
     * 이 기기에서 측위가 가능한가 + 불가 사유. 던지지 않고 네트워크를 타지 않는다.
     * OS 버전을 먼저 본다 — 구 OS 에 칩 미지원을 잘못 알리지 않기 위해서다.
     *
     * **initialize() 다음에 읽는다.** 칩 조회에는 앱 Context 가 필요한데, SDK 가 그것을 받는 곳은
     * initialize(또는 permissions(activity)) 뿐이다. 그 전에 읽으면 API 37 미만은 그대로
     * [DeviceAvailability.OS_VERSION_TOO_LOW], 그 이상은 판단할 수 없어
     * [DeviceAvailability.DEVICE_NOT_SUPPORTED] 이고 onDebugLog 에 WARN 이 한 번 남는다.
     *
     * 칩 조회는 initialize() 가 미리 띄워 두고(permissions() 는 그 조회를 직접 기다려 받는다), 답(시간 초과 = 미지원 포함)은 프로세스
     * 수명 동안 기억한다. 그래서 보통은 기다리지 않는다. 예열이 끝나기 전에 읽은 첫 한 번만 답을
     * 기다린다(최대 5초 — 그동안 부른 스레드가 멈춘다).
     */
    @JvmStatic
    public val deviceAvailability: DeviceAvailability
        get() {
            val capability = deviceCapability
            if (capability.sdkInt < MIN_POSITIONING_SDK) return DeviceAvailability.OS_VERSION_TOO_LOW
            if (appContext == null) return unknownBeforeInitialize()
            // 아는 답이 있으면 막지 않는다. ⚠️ 진행 중인 예열을 여기서 기다리면 안 된다 — 예열은 코어
            // (메인) 디스패처 위에서 도는데 메인을 runBlocking 으로 막고 그걸 기다리면 교착이다.
            val supported = capability.cachedDlTdoa ?: runBlocking { capability.supportsDlTdoa() }
            return if (supported) DeviceAvailability.AVAILABLE else DeviceAvailability.DEVICE_NOT_SUPPORTED
        }

    /**
     * 칩 조회를 미리 띄운다(기다리지 않는다) — 뒤의 동기 [deviceAvailability] 가 메인을 막지 않게.
     * 코어 디스패처 위에서 돌며, 조회는 시스템 콜백을 기다리는 동안 스레드를 놓아 준다.
     */
    private fun warmDeviceCapability() {
        val capability = deviceCapability
        if (capability.sdkInt < MIN_POSITIONING_SDK || appContext == null) return
        if (capability.cachedDlTdoa != null) return
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            try {
                capability.supportsDlTdoa()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 예열 실패는 무시한다 — 다음 판정이 다시 묻는다.
            }
        }
    }

    @Volatile
    private var warnedEarlyAvailability = false

    /** Context 를 받기 전 — 칩을 물을 수 없다. 거짓 AVAILABLE 은 내지 않는다(Ruling 10). */
    private fun unknownBeforeInitialize(): DeviceAvailability {
        if (!warnedEarlyAvailability) {
            warnedEarlyAvailability = true
            onDebugLog?.onLog(LogLevel.WARN, "deviceAvailability read before initialize()")
        }
        return DeviceAvailability.DEVICE_NOT_SUPPORTED
    }

    /** 이 기기에서 측위가 가능한가 (요약형). 사유가 필요하면 [deviceAvailability]. */
    @JvmStatic
    public val isDeviceAvailable: Boolean
        get() = deviceAvailability == DeviceAvailability.AVAILABLE

    /** suspend 경로용 판정 — 칩 조회를 막지 않고 기다린다. */
    internal suspend fun availability(): DeviceAvailability {
        val capability = deviceCapability
        if (capability.sdkInt < MIN_POSITIONING_SDK) return DeviceAvailability.OS_VERSION_TOO_LOW
        if (appContext == null) return unknownBeforeInitialize()
        val supported = capability.cachedDlTdoa ?: capability.supportsDlTdoa()
        return if (supported) DeviceAvailability.AVAILABLE else DeviceAvailability.DEVICE_NOT_SUPPORTED
    }

    // MARK: - 콘솔 제공 값

    /**
     * 콘솔이 내려준 Google Maps 키 — 앱이 자체 지도를 그릴 때 쓴다.
     * 측위에 필요한 나머지 키는 SDK 안에서만 쓰여 여기 없다. initialize 성공 전에는 `null`.
     */
    @JvmStatic
    public val googleMapKey: String?
        get() = coordinator?.googleMapKey

    // MARK: - 권한

    /**
     * 측위 권한(RANGING + ACCESS_FINE_LOCATION)을 한 번에 확인·요청한다.
     *
     * - 이 기기에서 측위가 불가하면 팝업 없이 [PermissionStatus.UNSUPPORTED].
     * - 이미 둘 다 허용돼 있으면 팝업 없이 [PermissionStatus.AUTHORIZED].
     * - 아니면 시스템 팝업을 띄운다. 30초 안에 답이 없으면 보수적으로 [PermissionStatus.DENIED].
     *
     * `activityResultRegistry` 를 쓰므로 onCreate 이후 아무 때나 불러도 된다.
     * initialize 를 부르지 않았어도 호출할 수 있다.
     */
    @JvmSynthetic
    public suspend fun permissions(activity: ComponentActivity): PermissionStatus {
        if (appContext == null) appContext = activity.applicationContext
        // 칩 조회 예열은 따로 띄우지 않는다 — 아래 판정(availability)이 바로 그 조회를 기다려(막지
        // 않고) 받고, 답은 capability 가 기억한다. 따로 띄우면 같은 조회가 두 번 나간다.
        return permissionsWith { PositioningPermission.request(activity) }
    }

    /** [permissions] 의 Java 판. */
    @JvmStatic
    public fun permissions(activity: ComponentActivity, callback: Callback<PermissionStatus>) {
        JavaBridge.run(callback) { permissions(activity) }
    }

    /** 기기 판정 → 요청. 요청 자체는 주입받는다(JVM 테스트는 Activity 를 만들 수 없다). */
    internal suspend fun permissionsWith(request: suspend () -> PermissionStatus): PermissionStatus = onCore {
        if (availability() != DeviceAvailability.AVAILABLE) PermissionStatus.UNSUPPORTED else request()
    }

    /**
     * SDK 가 로그·안내 문구에 쓸 언어 — `"ko"` · `"ja"` · `"en"`. `null` 이면 기기 언어(기본값).
     * 언제든, 몇 번이든 부를 수 있다 — 다음에 만들어지는 문구부터 바뀐다.
     */
    @JvmStatic
    public fun setLanguage(code: String?) {
        SdkLocalized.language = code
    }

    // MARK: - 생명주기

    /**
     * 초기화 (앱 시작 시 1회) — 키 검증 + 테넌트 SDK 설정 수신.
     *
     * **기기 지원 여부는 여기서 보지 않는다** — UWB 없는 기기에서도 통과해야 도면·존 조회가
     * 열린다. 막는 곳은 `FloorSession.begin()` 하나뿐이다.
     * 실패 시 재호출 = 재시도 · 성공 후 재호출 = 무시(멱등) · 다른 키로 재호출 = 세션 재구성.
     * ⚠️ 건물·층은 조회하지 않는다 — 공간 선택은 buildings()/setFloorMap() 의 책임이다.
     *
     * @param context applicationContext 만 보관한다.
     * @param sdkKey OneS1ght 콘솔 발급 키 (ock_). 측위에 필요한 나머지 키는 콘솔에서 받는다.
     * @param baseUrl 자체 서버를 구축한 고객만.
     * @throws ApiError 키 무효(E1002)·네트워크(E5001) 등
     * @throws SdkError.PositioningDisabled 테넌트에서 측위가 꺼져 있다(E1003)
     */
    @JvmSynthetic
    public suspend fun initialize(
        context: Context,
        sdkKey: String,
        baseUrl: String = ApiClient.DEFAULT_BASE_URL,
    ): Unit = onCore {
        // 기기 게이트는 여기 두지 않는다 — initialize 는 "키·설정" 이고 begin() 이 "측위" 다.
        val app = context.applicationContext ?: context
        appContext = app
        warmDeviceCapability() // 기다리지 않는다 — 뒤의 동기 deviceAvailability 가 메인을 막지 않게

        // ① 키가 바뀌었으면 세션 재구성 — "새 키로 initialize = 새 키로 시작".
        val stored = storedKey
        if (stored != null && stored != sdkKey) {
            discardCoordinator()
        }

        // ② 세션 구성 (최초 또는 재구성 후 1회)
        if (coordinator == null) {
            coordinator = makeCoordinator(app, sdkKey, baseUrl)
            storedKey = sdkKey
        }

        // ③ 키 검증 + 설정 프리페치 (실패 시 throw — 재호출이 곧 재시도)
        coordinator?.prepare()
    }

    /**
     * [initialize] 의 Java 판(기본 주소).
     *
     * 기본 인자 뒤에 callback 을 두면 `@JvmOverloads` 가 오버로드를 어긋나게 만들어서
     * (context, sdkKey, callback) · (context, sdkKey, baseUrl, callback) 두 개를 손으로 둔다.
     */
    @JvmStatic
    public fun initialize(context: Context, sdkKey: String, callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            initialize(context, sdkKey)
            null
        }
    }

    /** [initialize] 의 Java 판(주소 지정). */
    @JvmStatic
    public fun initialize(context: Context, sdkKey: String, baseUrl: String, callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            initialize(context, sdkKey, baseUrl)
            null
        }
    }

    /** 초기화 리셋 — 세션을 버린다. 이후 다른 키로 재초기화할 수 있다(런타임 키 교체용). */
    @JvmSynthetic
    public suspend fun reset(): Unit = onCore {
        discardCoordinator()
        storedKey = null
    }

    /** [reset] 의 Java 판. */
    @JvmStatic
    public fun reset(callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            reset()
            null
        }
    }

    // MARK: - 공간 조회 (엔드포인트 하나당 메서드 하나 · 목록 ↔ 단건)

    /**
     * 건물 목록. 층은 floors() 로 따로.
     * ⚠️ 빈 목록이 "건물이 없다" 는 뜻만은 아니다 — 콘솔에서 측위 키를 받지 못했을 때도
     *    빈 목록이 온다. 구분하려면 onDebugLog 나 콘솔 로그 분석기에서 E1007 을 본다.
     */
    @JvmSynthetic
    public suspend fun buildings(): List<Building> = onCore { requireCoordinator().buildings() }

    @JvmStatic
    public fun buildings(callback: Callback<List<Building>>) {
        JavaBridge.run(callback) { buildings() }
    }

    /** 건물 단건. 없으면 [ApiError.NotFound]. */
    @JvmSynthetic
    public suspend fun building(buildingId: String): Building =
        buildings().firstOrNull { it.id == buildingId } ?: throw ApiError.NotFound(buildingId)

    @JvmStatic
    public fun building(buildingId: String, callback: Callback<Building>) {
        JavaBridge.run(callback) { building(buildingId) }
    }

    /** 층 목록 — 이름·치수는 채워지고 **도면 이미지는 비어 있다**(목록 경량화). */
    @JvmSynthetic
    public suspend fun floors(buildingId: String): List<Floor> = onCore { requireCoordinator().floors(buildingId) }

    @JvmStatic
    public fun floors(buildingId: String, callback: Callback<List<Floor>>) {
        JavaBridge.run(callback) { floors(buildingId) }
    }

    /** 층 단건 — 도면 이미지 포함 (floors() 가 캐시를 데워 두면 추가 왕복 없음). */
    @JvmSynthetic
    public suspend fun floor(buildingId: String, floorId: String): Floor =
        onCore { requireCoordinator().floor(buildingId, floorId) }

    @JvmStatic
    public fun floor(buildingId: String, floorId: String, callback: Callback<Floor>) {
        JavaBridge.run(callback) { floor(buildingId, floorId) }
    }

    /** 존 목록 (판정 파라미터 포함). */
    @JvmSynthetic
    public suspend fun zones(buildingId: String, floorId: String): List<Zone> =
        onCore { requireCoordinator().zones(buildingId, floorId) }

    @JvmStatic
    public fun zones(buildingId: String, floorId: String, callback: Callback<List<Zone>>) {
        JavaBridge.run(callback) { zones(buildingId, floorId) }
    }

    /** 존 단건. 없으면 [ApiError.NotFound]. */
    @JvmSynthetic
    public suspend fun zone(buildingId: String, floorId: String, zoneId: String): Zone =
        zones(buildingId, floorId).firstOrNull { it.id == zoneId } ?: throw ApiError.NotFound(zoneId)

    @JvmStatic
    public fun zone(buildingId: String, floorId: String, zoneId: String, callback: Callback<Zone>) {
        JavaBridge.run(callback) { zone(buildingId, floorId, zoneId) }
    }

    /** 로케이터 + 세션ID — sessionId 는 별도 API 가 아니라 이 응답에 함께 실려 온다. */
    @JvmSynthetic
    public suspend fun locators(buildingId: String, floorId: String): FloorLocators =
        onCore { requireCoordinator().locators(buildingId, floorId) }

    @JvmStatic
    public fun locators(buildingId: String, floorId: String, callback: Callback<FloorLocators>) {
        JavaBridge.run(callback) { locators(buildingId, floorId) }
    }

    // MARK: - 층 지정

    /**
     * 측위·판정에 쓸 층을 지정한다. 호출할 때마다 갱신되고, null 이면 비운다.
     * 로케이터·sessionId·존을 받아 엔진에 주입한다 — 가동 중이면 즉시 층 전환.
     * [buildingId] 를 생략하면 직전에 지정한 건물을 쓴다.
     */
    @JvmSynthetic
    public suspend fun setFloorMap(floor: Floor?, buildingId: String? = null): Unit = onCore {
        val c = requireCoordinator()
        c.setFloorMap(floor, buildingId ?: currentBuildingId)
        currentBuildingId = if (floor == null) null else (buildingId ?: currentBuildingId)
    }

    /** [setFloorMap] 의 Java 판(직전 건물). */
    @JvmStatic
    public fun setFloorMap(floor: Floor?, callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            setFloorMap(floor)
            null
        }
    }

    /** [setFloorMap] 의 Java 판(건물 지정). */
    @JvmStatic
    public fun setFloorMap(floor: Floor?, buildingId: String?, callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            setFloorMap(floor, buildingId)
            null
        }
    }

    /**
     * 현재 층의 존만 재조회 (경량 — 도면 재다운로드 없음). 받은 존은 판정 엔진에도 즉시 반영된다.
     * 초기화 전이거나 실패하면 던지지 않고 지금 가진 존(없으면 빈 목록)을 돌려준다.
     */
    @JvmSynthetic
    public suspend fun refreshZones(): List<Zone> = onCore { coordinator?.refreshZones() ?: emptyList() }

    @JvmStatic
    public fun refreshZones(callback: Callback<List<Zone>>) {
        JavaBridge.run(callback) { refreshZones() }
    }

    // MARK: - 측위 세션

    /**
     * 측위 세션. 항상 같은 인스턴스를 돌려준다(싱글턴) — UWB 라디오·판정 엔진·좌표 버퍼가
     * 기기당 하나뿐이라 세션이 여럿이면 물리적으로 충돌한다.
     *
     * @throws SdkError.NotInitialized initialize 를 한 번도 부르지 않았다(실패한 initialize 는 괜찮다).
     */
    @JvmStatic
    public fun floorSession(): FloorSession {
        if (coordinator == null) throw SdkError.NotInitialized()
        return FloorSession.shared
    }

    // MARK: - 프로필

    /**
     * 프로필 생성 — 서버가 발급한 profileId 를 돌려준다. **앱이 보관해 재사용해야 한다.**
     * ⚠️ 나이는 정확값 대신 연령대("20s")로 넣기를 권한다.
     */
    @JvmSynthetic
    public suspend fun createProfile(attributes: Map<String, String>): String =
        onCore { requireCoordinator().createProfile(attributes) }

    @JvmStatic
    public fun createProfile(attributes: Map<String, String>, callback: Callback<String>) {
        JavaBridge.run(callback) { createProfile(attributes) }
    }

    /** 프로필 조회. */
    @JvmSynthetic
    public suspend fun getProfile(profileId: String): Map<String, String> =
        onCore { requireCoordinator().getProfile(profileId) }

    @JvmStatic
    public fun getProfile(profileId: String, callback: Callback<Map<String, String>>) {
        JavaBridge.run(callback) { getProfile(profileId) }
    }

    /** 프로필 속성 전체 교체. */
    @JvmSynthetic
    public suspend fun putProfile(profileId: String, attributes: Map<String, String>): Unit =
        onCore { requireCoordinator().putProfile(profileId, attributes) }

    @JvmStatic
    public fun putProfile(profileId: String, attributes: Map<String, String>, callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            putProfile(profileId, attributes)
            null
        }
    }

    /** 프로필 삭제. */
    @JvmSynthetic
    public suspend fun deleteProfile(profileId: String): Unit = onCore { requireCoordinator().deleteProfile(profileId) }

    @JvmStatic
    public fun deleteProfile(profileId: String, callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            deleteProfile(profileId)
            null
        }
    }

    // MARK: - 버퍼

    /** 쌓인 좌표를 지금 서버로 전송 (300건/60초를 기다리지 않고 앞당김). 초기화 전이면 아무 일 없음. */
    @JvmSynthetic
    public suspend fun send(): Unit = onCore { coordinator?.flush() }

    @JvmStatic
    public fun send(callback: Callback<Void?>) {
        JavaBridge.run(callback) {
            send()
            null
        }
    }

    /**
     * 쌓인 좌표를 **전송하지 않고 폐기**. 메인 스레드에서 부른다.
     * ⚠️ flush 가 아니라 empty 인 이유 — flush 는 보통 "목적지로 밀어낸다"(전송)는 뜻이라,
     *    폐기에 그 이름을 쓰면 전송으로 오해한 호출에 데이터가 조용히 사라진다.
     */
    @JvmStatic
    @MainThread
    public fun empty() {
        coordinator?.empty()
    }

    // MARK: - 사용자

    /**
     * 프로필 연결 — 좌표·존 이벤트가 이 ID 로 귀속된다(해제는 null). 메인 스레드에서 부른다.
     * begin() 전에 반드시 호출해야 한다 (없으면 NotIdentified · E1004).
     * ⚠️ initialize 보다 먼저 부르면 값이 전달되지 않는다(iOS 와 같은 동작 — 두 플랫폼을 함께 고친다).
     */
    @JvmStatic
    @MainThread
    public fun identify(profileId: String?) {
        this.profileId = profileId
        coordinator?.identify(profileId)
    }

    // MARK: - 내부 부품 (인스턴스 — 키 교체·reset 때 갈아끼움)

    @Volatile
    private var coordinator: SessionCoordinator? = null
    private var coordinatorScope: CoroutineScope? = null
    private var storedKey: String? = null // 키 교체 감지용
    private var profileId: String? = null
    private var currentBuildingId: String? = null // setFloorMap 의 건물 문맥

    /** applicationContext — 내장 provider·기기 판정이 쓴다. */
    @Volatile
    internal var appContext: Context? = null

    /** FloorSession 이 코디네이터에 닿는 통로 (같은 모듈 내부 전용). */
    internal val coordinatorRef: SessionCoordinator? get() = coordinator

    private fun requireCoordinator(): SessionCoordinator = coordinator ?: throw SdkError.NotInitialized()

    private fun makeCoordinator(app: Context, sdkKey: String, baseUrl: String): SessionCoordinator {
        val (store, lifecycle) = platformFactory(app)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val api = ApiClient(sdkKey, baseUrl)
        val endpoints = spaceEndpointsOverride
        val c = SessionCoordinator(
            api = api,
            identity = IdentityStore(store),
            appId = app.packageName,
            scope = scope,
            lifecycle = lifecycle,
            spaceClientFactory = { sdk, space ->
                if (endpoints == null) {
                    SpaceServiceClient(sdk, space, api.http)
                } else {
                    SpaceServiceClient(sdk, space, api.http, consoleBase = endpoints.first, spaceHost = endpoints.second)
                }
            },
        )
        c.onTriggers = { zoneId, triggers -> FloorSession.shared.onTriggers?.onTriggers(zoneId, triggers) }
        c.onPosition = { coord -> FloorSession.shared.onPosition?.onPosition(coord) }
        c.onConfigChange = { change -> FloorSession.shared.onConfigChanged?.onConfigChanged(change) }
        c.onLog = { level, line -> onDebugLog?.onLog(level, line) }
        coordinatorScope = scope
        return c
    }

    /** 세션을 버린다 — 측위 정지 → 스트림·관찰자 정리 → 타이머 스코프 취소. */
    private suspend fun discardCoordinator() {
        val c = coordinator ?: return
        c.stop()
        c.teardown() // stop 은 층이 남아 있으면 스트림을 살려 둔다 — 참조를 놓기 전에 끊는다
        coordinatorScope?.cancel()
        coordinatorScope = null
        coordinator = null
    }

    /** 코어 디스패처로 옮겨 탄다 — 코디네이터 상태는 여기서만 바뀐다. */
    internal suspend fun <T> onCore(block: suspend () -> T): T = withContext(dispatcher) { block() }

    // MARK: - 주입 자리 (테스트 전용 — 운영은 기본값)

    @Volatile
    private var dispatcherOverride: CoroutineDispatcher? = null

    /**
     * 코어 디스패처. 운영은 `Dispatchers.Main.immediate`, 테스트는 StandardTestDispatcher.
     * 기본값을 지연 조회하는 이유: JVM 테스트에는 메인 루퍼가 없어 미리 읽으면 터진다.
     */
    internal var dispatcher: CoroutineDispatcher
        get() = dispatcherOverride ?: Dispatchers.Main.immediate
        set(value) {
            dispatcherOverride = value
        }

    private val defaultDeviceCapability: DeviceCapability by lazy { AndroidDeviceCapability { appContext } }

    @Volatile
    private var deviceCapabilityOverride: DeviceCapability? = null

    /** 기기 판정. 운영은 RangingManager 조회, 테스트는 가짜. */
    internal var deviceCapability: DeviceCapability
        get() = deviceCapabilityOverride ?: defaultDeviceCapability
        set(value) {
            deviceCapabilityOverride = value
        }

    private val defaultPlatformFactory: (Context) -> Pair<KeyValueStore, AppLifecycle?> =
        { ctx -> AndroidKeyValueStore(ctx) to AndroidAppLifecycle() }

    /** 영속 저장소·앱 생명주기. 운영은 SharedPreferences·ProcessLifecycleOwner. */
    internal var platformFactory: (Context) -> Pair<KeyValueStore, AppLifecycle?> = defaultPlatformFactory

    private val defaultBuiltInProviderFactory: (Context) -> PositioningProvider =
        { ctx -> createBuiltInProvider(ctx, dispatcher, System::currentTimeMillis) }

    /**
     * 공간 조회의 (콘솔 주소, 공간 서비스 주소). 운영은 null — 공간 조회는 기본 주소로 나간다
     * (initialize 의 baseUrl 을 쓰지 않는다, 사양서 §4.2). 테스트가 스텁 서버로 돌린다.
     */
    @Volatile
    internal var spaceEndpointsOverride: Pair<String, String>? = null

    /** FloorSession.begin() 이 한 번만 만드는 내장 provider. 테스트는 Mock 을 넣는다. */
    internal var builtInProviderFactory: (Context) -> PositioningProvider = defaultBuiltInProviderFactory

    /** 테스트가 갈아 끼운 자리를 운영 기본값으로 되돌린다. */
    internal fun restoreDefaultsForTest() {
        dispatcherOverride = null
        deviceCapabilityOverride = null
        platformFactory = defaultPlatformFactory
        builtInProviderFactory = defaultBuiltInProviderFactory
        spaceEndpointsOverride = null
        appContext = null
        warnedEarlyAvailability = false
        currentBuildingId = null
        profileId = null
    }
}
