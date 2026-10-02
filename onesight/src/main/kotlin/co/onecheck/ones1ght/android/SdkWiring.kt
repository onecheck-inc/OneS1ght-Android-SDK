package co.onecheck.ones1ght.android

//
//  SdkWiring.kt
//  부품 조립과 테스트 주입 자리 — 공개 파사드(OneS1ght)에서 떼어 냈다(감사 SF-C6).
//
//  OneS1ght 는 고객이 보는 유일한 문이라 「무엇을 하는가」만 남기고, 「무엇으로 만드는가」(디스패처·기기 판정·
//  저장소·생명주기·내장 provider·공간 조회 주소)와 코디네이터 조립은 여기 둔다. 운영은 기본값 그대로이고,
//  테스트만 갈아 끼운 뒤 [restoreDefaults] 로 되돌린다.
//

import android.content.Context
import android.os.Build
import co.onecheck.ones1ght.android.network.ApiClient
import co.onecheck.ones1ght.android.positioning.AndroidDeviceCapability
import co.onecheck.ones1ght.android.positioning.DeviceCapability
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.createBuiltInProvider
import co.onecheck.ones1ght.android.runtime.AndroidAppLifecycle
import co.onecheck.ones1ght.android.runtime.AndroidKeyValueStore
import co.onecheck.ones1ght.android.runtime.AppLifecycle
import co.onecheck.ones1ght.android.runtime.SessionCoordinator
import co.onecheck.ones1ght.android.space.SpaceServiceClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal object SdkWiring {

    // MARK: - 조립

    /**
     * 키 하나로 세션 부품을 조립한다 — 코디네이터와 그 타이머가 사는 스코프. 공개 훅(FloorSession·onDebugLog)으로
     * 가는 길을 잇고, 앱이 이미 넘긴 프로필을 새 세션에 잇는다(initialize 전 identify·reset·키 교체 뒤에도 —
     * SF-A7 · iOS S12).
     */
    fun makeCoordinator(app: Context, sdkKey: String, baseUrl: String, profileId: String?): Pair<SessionCoordinator, CoroutineScope> {
        val (store, lifecycle) = platformFactory(app)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val api = ApiClient.create(sdkKey, baseUrl)
        val endpoints = spaceEndpointsOverride
        val c = SessionCoordinator(
            api = api,
            identity = co.onecheck.ones1ght.android.identity.IdentityStore(store),
            appId = app.packageName,
            scope = scope,
            lifecycle = lifecycle,
            spaceClientFactory = { sdk, space, spaceHost ->
                if (endpoints == null) {
                    // 콘솔 공간 조회도 initialize 의 baseUrl 로(SF-A9 · iOS S15), 공간 서비스는 콘솔 geo_base_url 로(SF-A11).
                    SpaceServiceClient(sdk, space, api.http, consoleBase = baseUrl, spaceHost = spaceHost)
                } else {
                    SpaceServiceClient(sdk, space, api.http, consoleBase = endpoints.first, spaceHost = endpoints.second)
                }
            },
        )
        c.onTriggers = { zoneId, triggers -> FloorSession.shared.onTriggers?.onTriggers(zoneId, triggers) }
        c.onPosition = { coord -> FloorSession.shared.onPosition?.onPosition(coord) }
        c.onConfigChange = { change -> FloorSession.shared.onConfigChanged?.onConfigChanged(change) }
        c.onSessionClosed = { reason -> FloorSession.shared.onStopped?.onStopped(reason) }
        c.onFloorDetected = { floorId -> FloorSession.shared.onFloorDetected?.onFloorDetected(floorId) }
        c.onZoneEvent = { event -> FloorSession.shared.dispatch(event) }
        c.onLog = { level, line -> OneS1ght.onDebugLog?.onLog(level, line) }
        c.identify(profileId)
        return c to scope
    }

    // MARK: - 주입 자리 (테스트 전용 — 운영은 기본값)

    @Volatile
    private var dispatcherOverride: CoroutineDispatcher? = null

    /**
     * 코어 디스패처. 운영은 `Dispatchers.Main.immediate`, 테스트는 StandardTestDispatcher.
     * 기본값을 지연 조회하는 이유: JVM 테스트에는 메인 루퍼가 없어 미리 읽으면 터진다.
     */
    var dispatcher: CoroutineDispatcher
        get() = dispatcherOverride ?: Dispatchers.Main.immediate
        set(value) {
            dispatcherOverride = value
        }

    private val defaultDeviceCapability: DeviceCapability by lazy {
        AndroidDeviceCapability(contextProvider = { OneS1ght.appContext })
    }

    @Volatile
    private var deviceCapabilityOverride: DeviceCapability? = null

    /** 기기 판정. 운영은 OS 버전 + 측위 엔진의 UWB 하드웨어 조회, 테스트는 가짜. */
    var deviceCapability: DeviceCapability
        get() = deviceCapabilityOverride ?: defaultDeviceCapability
        set(value) {
            deviceCapabilityOverride = value
        }

    private val defaultPlatformFactory: (Context) -> Pair<co.onecheck.ones1ght.android.runtime.KeyValueStore, AppLifecycle?> =
        { ctx -> AndroidKeyValueStore(ctx) to AndroidAppLifecycle() }

    /** 영속 저장소·앱 생명주기. 운영은 SharedPreferences·ProcessLifecycleOwner. */
    var platformFactory: (Context) -> Pair<co.onecheck.ones1ght.android.runtime.KeyValueStore, AppLifecycle?> =
        defaultPlatformFactory

    /**
     * 내장 provider(측위 엔진) 생성. begin() 이 이미 OS 를 걸렀지만 그 판정은 주입 가능한 [deviceCapability]
     * 를 거친다 — 엔진 클래스를 로드하는 이 자리에서는 실제 `SDK_INT` 로 한 번 더 막는다(API 37 미만에서
     * 엔진을 건드리면 android.ranging 이 없어 NoClassDefFoundError 다).
     */
    private val defaultBuiltInProviderFactory: (Context) -> PositioningProvider = { ctx ->
        if (Build.VERSION.SDK_INT >= MIN_POSITIONING_SDK) {
            createBuiltInProvider(ctx, dispatcher, System::currentTimeMillis)
        } else {
            throw SdkError.OsVersionTooLow()
        }
    }

    /**
     * 공간 조회의 (콘솔 주소, 공간 서비스 주소). 운영은 null — 콘솔 공간 조회는 initialize 의 baseUrl,
     * 공간 서비스는 콘솔 `/config` 의 geo_base_url 로 나간다. 테스트가 스텁 서버로 돌린다.
     */
    @Volatile
    var spaceEndpointsOverride: Pair<String, String>? = null

    /** FloorSession.begin() 이 한 번만 만드는 내장 provider. 테스트는 Mock 을 넣는다. */
    var builtInProviderFactory: (Context) -> PositioningProvider = defaultBuiltInProviderFactory

    /** 테스트가 갈아 끼운 자리를 운영 기본값으로 되돌린다. */
    fun restoreDefaults() {
        dispatcherOverride = null
        deviceCapabilityOverride = null
        platformFactory = defaultPlatformFactory
        builtInProviderFactory = defaultBuiltInProviderFactory
        spaceEndpointsOverride = null
    }
}
