package co.onecheck.ones1ght.android.runtime

//
//  SessionCoordinator.kt
//  라이프사이클 상태기계 (사양서 §5) — SDK의 두뇌
//
//  prepare: verify(키검증) → /config(측위 키 등 — 실패해도 초기화는 성공, begin 에서 재시도)
//  start:   provider 가동 (identify 가 앞에 있어야 한다 — 인증 게이팅)
//    ├ onEnter(빌딩)          → 통지만 (건물·층은 호스트 앱의 몫)
//    ├ onPosition(좌표)       → 다운샘플 후 버퍼 적재 → 300건/60초/종료/백그라운드에 벌크 전송(실패 뒤 임계값 전송은 backoff)
//    ├ onZone(IN/OUT)         → events/zone 즉시 전송 (+network 1회 재시도) → triggers 호스트 전달. DWELL 은 안 보낸다
//    ├ onFloorDetected·onEmit → FloorSession 콜백으로 그대로(어느 provider 든 같은 길 — iOS K14)
//    ├ onReport(코드)         → 화면 로그 한 줄 + 서버 로그
//    └ onStoppedUnexpectedly  → 다시 켜 보거나(3·10·30초) 세션을 닫는다(FloorSession.onStopped(ENGINE_FAILED))
//  · 실시간 수신(SSE): 층이 정해졌거나 측위가 도는 동안만 붙어 있다
//
//  여기는 **순서를 정하는 자리**다. 일은 부품이 한다(감사 SP-C2 · iOS K7 — 한 타입에 책임 열 가지 넘게였다):
//    SdkReporter          화면 로그 + 서버 로그(코드)
//    UploadPipeline       다운샘플 · 좌표 버퍼 · 300건/60초 전송 · 실패 뒤 backoff
//    LiveStreamController 실시간 연결의 수명·층 필터
//    EngineSupervisor     엔진이 스스로 꺼졌을 때 다시 켜기/포기
//
//  · 인증 게이팅: profileId 없이 start 하면 수집 미시작 (서버는 기록만 하므로 클라가 막음)
//  · 백그라운드: UWB 포그라운드 전용 → provider.stop + flush, 복귀 시 재개
//  · ⚠️ 모든 상태는 주입된 [scope] 의 디스패처 한 곳에서만 바꾼다(운영: Main.immediate).
//    iOS 의 `@MainActor` 자리다 — 잠금이 없는 대신 다른 스레드에서 부르면 안 된다.
//
//  포팅 원본: SessionCoordinator.swift (줄 단위 대응). pause/resume 은 provider 의 몫이라
//  여기 없다(iOS 와 같다 — 상태를 두 벌 두면 어긋난다).
//

import co.onecheck.ones1ght.android.FloorSession
import co.onecheck.ones1ght.android.SdkError
import co.onecheck.ones1ght.android.internal.Iso8601
import co.onecheck.ones1ght.android.model.Building
import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.model.FloorLocators
import co.onecheck.ones1ght.android.model.FloorState
import co.onecheck.ones1ght.android.model.ReqVerify
import co.onecheck.ones1ght.android.model.ReqZoneEvent
import co.onecheck.ones1ght.android.model.ResSdkConfig
import co.onecheck.ones1ght.android.model.ResZoneEvent
import co.onecheck.ones1ght.android.model.Trigger
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.model.ZoneEventStatus
import co.onecheck.ones1ght.android.network.ApiClient
import co.onecheck.ones1ght.android.network.ApiError
import co.onecheck.ones1ght.android.positioning.PositioningConfig
import co.onecheck.ones1ght.android.positioning.PositioningProvider
import co.onecheck.ones1ght.android.positioning.PositioningProviderDelegate
import co.onecheck.ones1ght.android.positioning.UwbPositioningProvider
import co.onecheck.ones1ght.android.space.SpaceServiceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class SessionCoordinator(
    val api: ApiClient,
    private val identity: co.onecheck.ones1ght.android.identity.IdentityStore,
    /** verify 의 app_id — 안드로이드는 packageName (iOS bundleIdentifier 자리). */
    private val appId: String?,
    /** 코어 상태가 사는 곳. 운영은 `SupervisorJob + Dispatchers.Main.immediate`. */
    private val scope: CoroutineScope,
    private val lifecycle: AppLifecycle?,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 콘솔 키로 SpaceServiceClient 를 만드는 자리 — 테스트는 스텁 서버를 가리키게 주입한다.
     * 이게 없으면 콘솔 키로 만든 클라이언트가 실제 서비스로 나간다(iOS I3).
     * [spaceHost] 는 콘솔 `geo_base_url` 을 [SpaceServiceClient.spaceHostFor] 로 검사한 값이다(SF-A11).
     * 기본 조립은 콘솔 공간 조회도 initialize 의 baseUrl 로 보낸다(SF-A9 · iOS S15).
     */
    private val spaceClientFactory: (sdkKey: String, spaceKey: String, spaceHost: String) -> SpaceServiceClient =
        { sdk, space, host ->
            SpaceServiceClient(sdk, space, api.http, consoleBase = api.base, spaceHost = host, clock = clock)
        },
    /**
     * 실시간 수신 스트림을 만드는 자리. 스트림은 [scope] 위에서 onChange·onLog 를 부른다
     * (Task 6 계약) — 여기서 다시 스레드를 옮기지 않는다. null 을 돌려주면 스트림 없이 돈다.
     */
    liveFactory: (
        onChange: (ConfigChange) -> Unit,
        onLog: (LogLevel, String) -> Unit,
    ) -> LiveConfigStream? = { onChange, onLog ->
        LiveConfigStream(api.http, api.base, api.key, scope, onChange, onLog)
    },
    // 배치 정책 (사양서 §6.8 은 100건/5분 "권장" — 2026-08-20 300건/60초로 조정.
    // 4Hz 에서는 300건(=75초)보다 60초 타이머가 먼저 걸려 실질 60초·240건 주기가 된다.)
    flushThreshold: Int = 300,
    flushIntervalMs: Long = 60_000,
    maxPerRequest: Int = SdkLimits.MAX_PER_REQUEST,
    /** 수신 진단 1회 확인 — 측위 시작 후 이 시간 뒤에 본다. 7초는 현장에서 쓰던 값이다. */
    private val receptionCheckDelayMs: Long = 7_000,
    /**
     * 엔진이 **스스로** 꺼졌을 때 다시 켜 보는 간격 — 이만큼 해도 안 되면 세션을 닫는다(iOS #54 와 같은 값).
     *
     * 닫는 이유: 세션을 "측위 중" 으로 둔 채 엔진만 죽어 있으면 앱의 `begin()` 이 "이미 측위 중" 으로
     * 삼켜져, 앱을 껐다 켜기 전엔 측위가 안 돌아왔다(감사 SP-B1). 닫으면 `FloorSession.isRunning` 이
     * false 가 되어 앱이 알고(onStopped) 다시 연다.
     */
    engineRestartDelaysMs: List<Long> = listOf(3_000L, 10_000L, 30_000L),
) : PositioningProviderDelegate {

    // MARK: - 부품 — 일은 이쪽이 하고 코디네이터는 순서만 정한다(파일 머리말)

    private val reporter = SdkReporter(api, scope, clock, profileId = { profileId })

    private val uploads = UploadPipeline(
        api = api,
        reporter = reporter,
        scope = scope,
        clock = clock,
        flushThreshold = flushThreshold,
        flushIntervalMs = flushIntervalMs,
        maxPerRequest = maxPerRequest,
        owner = { profileId?.let { UploadPipeline.Owner(it, visitorId) } },
    )

    private val liveStream = LiveStreamController(
        factory = liveFactory,
        onChange = { change -> deliverConfigChange(change) },
        onLog = { level, line -> log(level, line) },
    )

    private val supervisor = EngineSupervisor(scope, engineRestartDelaysMs, reporter)

    /**
     * nil 로 시작해 resolveKeysFromConsole() 이 콘솔 키로 채운다. 콘솔이 키를 못 주면
     * 계속 null 이다: buildings()/floors()/zones() 는 빈 목록으로, floor()/locators()/
     * setFloorMap() 은 NotInitialized 로 떨어진다(isPrepared 는 그래도 true — I1 참고).
     */
    var spaceClient: SpaceServiceClient? = null
        private set

    private var provider: PositioningProvider? = null

    /**
     * 지금 물려 있는 프로바이더 — FloorSession 의 pause/resume 이 읽는다.
     * 코어는 일시정지를 알 필요가 없다(좌표가 안 올라오면 그만이다).
     */
    val activeProvider: PositioningProvider? get() = provider

    /** 측위 엔진에 물릴 라이선스 — 콘솔이 유일한 출처다. */
    var positioningLicense: String? = null
        private set

    /** 콘솔이 내려준 나머지 — 호스트 앱이 지도·도면에 쓴다. */
    var googleMapKey: String? = null
        private set
    var spaceServiceBaseUrl: String? = null
        private set

    /**
     * 가장 최근 `/config` 호출이 실패했는가(네트워크·서버). 성공하면(geo_sdk_key 가 null 이어도)
     * false 로 돌아온다: 그건 재시도로 해결될 문제가 아니다.
     */
    var keyResolutionFailed: Boolean = false
        private set

    /** 전송 대기 좌표 수. */
    val pendingCount: Int get() = uploads.pendingCount

    // 상태
    var isPrepared: Boolean = false // initialize(=prepare) 성공 여부 = "세션 가능"
        private set
    var isRunning: Boolean = false
        private set
    var visitorId: String = ""
        private set

    /** 앱이 넘긴 프로필 ID — 좌표·존 이벤트의 귀속 키. [identify] 로만 바꾼다. */
    var profileId: String? = null
        private set

    /** 서버가 verify 로 내려준 좌표 전송 주기(Hz). */
    val positionRateHz: Int get() = uploads.positionRateHz

    /** setFloorMap 결과 — start 시 provider 에 주입. */
    var floorState: FloorState? = null
        private set

    /** setFloorMap 이 받은 Floor (floorSession 노출용). */
    var currentFloor: Floor? = null
        private set

    val currentBuildingId: String? get() = floorState?.buildingId

    private var receptionCheckJob: Job? = null

    /**
     * 엔진이 다시 켜지지 않아 세션을 닫는 중인가 — 닫기(stop)는 flush 왕복을 기다리므로, 그 사이에 같은
     * 세션을 다시 켜거나(재시도·포그라운드) 타이머를 거는 일이 없게 동기로 먼저 세운다.
     */
    private var closingAfterEngineStop = false

    /**
     * 앱이 화면에 떠 있는가 — 생명주기 통지로만 바꾼다(관찰 전엔 begin 을 부른 화면이 떠 있다고 본다).
     * 안드로이드는 ProcessLifecycleOwner onStart/onStop 이라 알림창·전화 배너로는 바뀌지 않는다
     * (iOS S7 의 「비활성」 함정이 없다) — 배경에서 건너뛴 재시도는 포그라운드 복귀가 대신 켠다.
     */
    private var appInForeground = true
    private var observingLifecycle = false

    /** 존 이벤트 응답의 개인화 액션 → 호스트 전달 (zoneId, triggers) */
    var onTriggers: ((String, List<Trigger>) -> Unit)? = null

    /**
     * 측위 세션이 닫혔다 → 호스트 전달(FloorSession.onStopped) — 앱의 end()·reset()·키 교체면 ENDED, 엔진이 다시 켜지지
     * 않아 SDK 가 닫았으면 ENGINE_FAILED(iOS `onSessionClosed`).
     */
    var onSessionClosed: ((FloorSession.StopReason) -> Unit)? = null

    /** 엔진이 층을 잡았다(층 ID)/잃었다(null) → 호스트 전달(FloorSession.onFloorDetected). */
    var onFloorDetected: ((String?) -> Unit)? = null

    /** 앱에 보일 구역 이벤트 → 호스트 전달(FloorSession.onZoneEnter/Exit/Dwell). */
    var onZoneEvent: ((ZoneEvent) -> Unit)? = null

    /** 실시간 좌표 → 호스트 전달 (지도에 내 위치 찍기용 — 도면 로컬 미터) */
    var onPosition: ((Coordinates) -> Unit)? = null

    /** SDK 내부 활동 로그 (디버그) — 등급은 부르는 쪽이 정한다. 안 적으면 LOG. */
    var onLog: ((LogLevel, String) -> Unit)?
        get() = reporter.onLog
        set(value) {
            reporter.onLog = value
        }

    private fun log(msg: String) = reporter.log(msg)

    private fun log(level: LogLevel, msg: String) = reporter.log(level, msg)

    /** 콘솔 변경 → 고객사 전달. SDK 는 이 신호로 아무것도 하지 않는다. */
    var onConfigChange: ((ConfigChange) -> Unit)? = null

    /** 스트림이 붙어 있는가 (테스트·진단용). */
    val hasLiveStream: Boolean get() = liveStream.isAttached

    // MARK: - 서버 로그 (콘솔 로그 분석기) — SdkReporter 로 넘긴다

    /** 코드 붙은 사건을 남긴다 — 화면 로그 한 줄 + 서버 로그. 규칙은 [SdkReporter.report]. */
    fun report(code: SdkCode, ctx: String = "", message: String? = null) {
        reporter.report(code, ctx, message)
    }

    /** 서버 통신 실패를 코드로 옮겨 남긴다. ApiError 가 아니면 network 로 본다. */
    fun reportApi(error: Throwable, ctx: String = "", message: String? = null) {
        reporter.reportApi(error, ctx, message)
    }

    // MARK: - 라이프사이클

    /**
     * 초기화(앱 시작 시 1회) — 키 검증 + 테넌트 SDK 설정 수신. 이게 전부다.
     * 통과 = "세션 가능" 확정. 실패 사유는 throw (ApiError / SdkError.PositioningDisabled).
     *
     * 건물·층은 여기서 건드리지 않는다 — 어느 층을 쓸지는 호스트 앱만 안다.
     */
    suspend fun prepare() {
        if (isPrepared) return // 멱등

        // 키 검증 + 클라 등록. verify 성공 = 키 유효 + 백엔드 도달 가능.
        val verified = api.verify(makeVerifyRequest())

        // 관련 키(특히 Google Maps 키)는 측위와 무관하다 — positioning_enabled 가드보다
        // 먼저 받아 둔다. 뒤에 두면 측위가 꺼진 테넌트는 지도 키조차 못 받는다(I5).
        resolveKeysFromConsole()

        if (!verified.valid || !verified.positioningEnabled) throw SdkError.PositioningDisabled()

        // 테넌트 설정 반영 — 범위 밖·미회신은 기본값(4Hz)으로 접는다
        val hz = verified.positionRateHz ?: PositionRate.DEFAULT_HZ
        uploads.positionRateHz = hz.coerceIn(PositionRate.MIN_HZ, PositionRate.MAX_HZ)
        report(
            SdkInfoCode.INITIALIZED,
            "tenant=${verified.tenantCode ?: "?"}",
            message = SdkLocalized.t("coord.verifyPass", verified.tenantCode ?: "?"),
        )
        if (positionRateHz != PositionRate.DEFAULT_HZ) {
            report(SdkInfoCode.RATE_APPLIED, "rate=$positionRateHz", message = SdkLocalized.t("coord.rateApplied", positionRateHz))
        }
        isPrepared = true
    }

    /**
     * FloorSession.begin() 의 순단 회복이 부른다 — `/config` 가 실패했던 경우에만 재시도.
     * isPrepared 는 건드리지 않는다(멱등 유지, I1).
     */
    suspend fun retryKeyResolutionIfNeeded() {
        if (!isPrepared || !keyResolutionFailed) return
        resolveKeysFromConsole()
    }

    /**
     * 관련 키를 콘솔에서 받아 정본으로 삼는다. **초기화를 막지 않는다.**
     * ⚠️ 못 받으면 그 사실을 크게 남긴다(E1007) — 증상이 조용하기 때문이다.
     */
    private suspend fun resolveKeysFromConsole() {
        val cfg: ResSdkConfig
        try {
            cfg = api.config()
            keyResolutionFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            keyResolutionFailed = true
            reportKeyUnavailable("config_failed")
            return
        }

        googleMapKey = cfg.googleMapKey
        spaceServiceBaseUrl = cfg.geoBaseUrl

        val key = cfg.geoSdkKey
        if (key.isNullOrEmpty()) {
            // 통신은 됐고 값이 없다 — 재시도로 풀릴 문제가 아니라 콘솔 설정 문제다.
            reportKeyUnavailable("console_no_key")
            return
        }

        positioningLicense = key
        val spaceHost = SpaceServiceClient.spaceHostFor(cfg.geoBaseUrl)
        if (!cfg.geoBaseUrl.isNullOrBlank() && spaceHost == SpaceServiceClient.SPACE_HOST &&
            cfg.geoBaseUrl.trimEnd('/') != SpaceServiceClient.SPACE_HOST.trimEnd('/')
        ) {
            // 받은 주소를 못 쓴다(https 아님·URL 아님) — 기본 주소로 간다는 사실만 남긴다(주소는 키가 아니라 실어도 된다).
            log(LogLevel.WARN, "geo_base_url ignored (not https): ${cfg.geoBaseUrl}")
        }
        spaceClient = spaceClientFactory(api.key, key, spaceHost).also { c ->
            // 서버 값에서 걸러낸 점·존·항목 — 조용히 넘기면 "구역이 안 보인다" 로만 드러난다.
            c.onDataWarning = { msg -> log(LogLevel.WARN, msg) }
            c.onDuplicateZoneName = { name, keptId, droppedId -> reportDuplicateZone(name, keptId, droppedId) }
            // 폴백 뒤로 숨던 키 문제(401·403)를 E1002·E5004 로 드러낸다 — 동작은 그대로(감사 SF-C7).
            c.onAuthFailure = { e, ctx -> reportApi(e, ctx) }
        }
    }

    /** 이미 알린 같은 이름 구역(버린 구역 ID) — 구역은 폴링으로 자주 다시 받으므로 한 번만 알린다. */
    private val reportedDuplicateZones = mutableSetOf<String>()

    /**
     * 같은 이름 구역을 버렸다(감사 SF-A12) — 엔진은 영역을 이름으로만 알려 주므로 그 구역의 이벤트는 매핑될 수
     * 없다. 매핑 실패와 같은 E3009 로 올린다(관리자가 콘솔에서 이름을 고치면 풀린다).
     */
    private fun reportDuplicateZone(name: String, keptId: String, droppedId: String) {
        if (!reportedDuplicateZones.add(droppedId)) return
        report(SdkErrorCode.ZONE_MAPPING_FAILED, "duplicate zone name=$name kept=$keptId dropped=$droppedId")
    }

    /** 측위 키를 못 구했다는 사실을 남긴다. reason 은 고정 토큰이라 키 값이 실리지 않는다. */
    private fun reportKeyUnavailable(reason: String) {
        report(SdkErrorCode.KEY_UNAVAILABLE, "reason=$reason", message = SdkLocalized.t("coord.keyUnavailable"))
    }

    // MARK: - 공간 조회 (엔드포인트 하나당 메서드 하나 — 공간 서비스 키를 못 구했으면 빈 값)

    suspend fun buildings(): List<Building> = spaceClient?.buildings() ?: emptyList()

    suspend fun floors(buildingId: String): List<Floor> = spaceClient?.floors(buildingId) ?: emptyList()

    /** 층 단건 — 도면 이미지 포함. */
    suspend fun floor(buildingId: String, floorId: String): Floor {
        val client = spaceClient ?: throw SdkError.NotInitialized()
        return client.loadFloor(buildingId, floorId)
    }

    suspend fun zones(buildingId: String, floorId: String): List<Zone> =
        spaceClient?.loadZones(buildingId, floorId) ?: emptyList()

    /**
     * ⚠️ 조회 실패는 던지지 않는다 — 빈 목록으로 떨어져 `positioningReady` 가 거짓이 된다.
     * 던지는 것은 초기화 전 호출(NotInitialized) 뿐이다.
     */
    suspend fun locators(floorId: String): FloorLocators {
        val client = spaceClient ?: throw SdkError.NotInitialized()
        return client.loadLocators(floorId) ?: FloorLocators(emptyList(), null)
    }

    // MARK: - 층 지정

    /**
     * 측위·판정에 쓸 층을 지정한다. 호출할 때마다 갱신되고, null 이면 비운다.
     * 가동 중에 부르면 즉시 층 전환 — 세션은 그대로, 엔진 주입값만 갈린다.
     *
     * @throws SdkError.BuildingNotSet [floor] 가 있는데 [buildingId] 가 없다. 층 상태는 그대로 둔다.
     *   ⚠️ 감사 SF-A1: 예전엔 이 경우를 "층 해제" 로 처리해, 성공 콜백이 오는데 층이 비어 구역 이벤트가
     *   0건이었다(E3009).
     */
    suspend fun setFloorMap(floor: Floor?, buildingId: String?) {
        if (floor != null && buildingId == null) throw SdkError.BuildingNotSet()
        val previousFloor = floorState
        if (floor == null || buildingId == null) {
            floorState = null
            currentFloor = null
            // 엔진에서 층 설정 해제 — 존·로케이터뿐 아니라 콘솔 층 ID 도 비운다. 안 비우면 provider 가 해제한
            // 뒤에도 옛 층으로 엔진 층을 대조(E3008)하고, 엔진 층이 없을 때 구역 이벤트를 옛 층으로 보낸다.
            provider?.apply(buildingId = "", floorId = "")
            provider?.apply(PositioningConfig())
            restartLiveStreamIfFloorChanged(previousFloor)
            return
        }
        val client = spaceClient ?: throw SdkError.NotInitialized()
        val state = client.loadFloorState(buildingId, floor.id)
        floorState = state
        currentFloor = floor
        // ⚠️ 로케이터 수와 존 수를 둘 다 이름 붙여 찍는다(예전에 하나로 뭉쳐 장애 분석을 헤맸다).
        report(
            SdkInfoCode.FLOOR_SET,
            "building=$buildingId floor=${floor.id} locators=${state.locators.size} zones=${state.zones.size}",
            message = SdkLocalized.t("coord.floorLoaded", state.locators.size, state.zones.size, floor.id.take(8)),
        )
        // "못 받았다"(E3006)와 "안 깔았다"(E3002)를 가른다 — 확인할 곳이 다르다.
        // ⚠️ 어느 쪽이든 도면·존 표시는 막지 않는다.
        if (state.locatorsFetchFailed) {
            report(SdkErrorCode.LOCATORS_FETCH_FAILED, "floor=${floor.id}")
        } else if (state.locators.isEmpty()) {
            report(SdkErrorCode.LOCATORS_MISSING, "floor=${floor.id}")
        }
        if (state.sessionId == null) report(SdkErrorCode.SESSION_ID_MISSING, "floor=${floor.id}")
        if (state.zones.isEmpty()) report(SdkErrorCode.ZONES_EMPTY, "floor=${floor.id}")
        // 도면 없음은 실패가 아니다 — 지도를 배경 없이 그려야 한다는 사실만 남긴다.
        if (!state.hasPlan) report(SdkInfoCode.PLAN_MISSING, "floor=${floor.id}")
        if (isRunning) applyFloorStateToProvider() // 가동 중 층 전환
        restartLiveStreamIfFloorChanged(previousFloor)
    }

    /**
     * 존만 재조회 (도면 재다운로드 없음 — 폴링용). 받은 존은 엔진에도 즉시 반영.
     * 실패하면 지금 존을 그대로 돌려준다 — 통신 오류로 지도의 존이 사라지면 안 된다.
     * 로그는 "결과가 바뀔 때만" — 폴링 경로라 매번 찍으면 로그창이 덮인다.
     */
    suspend fun refreshZones(): List<Zone> {
        val client = spaceClient
        val state = floorState
        if (client == null || state == null) {
            logZoneOutcome(LogLevel.WARN, SdkLocalized.t("zone.refreshSkipped"), "no-floor")
            return floorState?.zones ?: emptyList()
        }
        return try {
            val zones = client.loadZones(state.buildingId, state.floorId)
            // 기다리는 사이 층이 바뀌었다(자동 층 전환·setFloorMap) — 옛 층 구역을 새 층에 넣지 않는다. 넣으면 다른
            // 층 zone_id 로 매핑돼 엉뚱한 구역 시책이 나갔다(감사 SP-B6 · iOS S10).
            if (floorState !== state) return floorState?.zones ?: emptyList()
            val changed = geofencesChanged(state.zones, zones)
            // 내용(id·이름·도형·체류 초 등)이 하나라도 다른가 — 같으면 provider 를 건드리지 않는다.
            // ⚠️ 감사 SP-B2: 폴링마다 apply 하면 판정기의 체류 타이머가 지워져 DWELL 이 안 떴다.
            val contentChanged = zones != state.zones
            floorState?.zones = zones
            // 구역을 전부 지웠을 때도 엔진에 반영해야 한다 — 안 그러면 삭제된 구역이 계속 발화한다.
            if (isRunning && contentChanged) {
                applyZonesOnly(zones) // 구역만 — 앵커·세션은 그대로(감사 SP-C9)
                // 바뀐 순간에만 엔진이 영역을 다시 읽게 한다 — 폴링마다 부르면 엔진이 계속 껐다 켜진다.
                if (changed) {
                    log(LogLevel.WARN, SdkLocalized.t("zone.geofenceReload", zones.size))
                    provider?.reloadGeofences()
                }
            }
            val names = zones.joinToString(", ") { it.name }
            logZoneOutcome(
                LogLevel.INFO,
                if (zones.isEmpty()) {
                    SdkLocalized.t("zone.refreshEmpty")
                } else {
                    SdkLocalized.t("zone.refreshOk", zones.size, names)
                },
                "ok:$names",
            )
            zones
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logZoneOutcome(LogLevel.ERROR, SdkLocalized.t("zone.refreshFail", state.zones.size, "$e"), "err:$e")
            state.zones
        }
    }

    /** 직전과 결과가 같으면 침묵 (폴링 도배 방지). */
    private var lastZoneOutcome: String? = null

    private fun logZoneOutcome(level: LogLevel, message: String, key: String) {
        if (lastZoneOutcome == key) return
        lastZoneOutcome = key
        log(level, message)
    }

    /** floorState → provider (로케이터·세션·존 + 건물·층 ID) */
    private fun applyFloorStateToProvider() {
        val state = floorState ?: return
        val provider = provider ?: return
        provider.apply(state.buildingId, state.floorId)
        val anchorMap = LinkedHashMap<Int, DoubleArray>()
        for (l in state.locators) anchorMap[l.address] = doubleArrayOf(l.x, l.y, l.z)
        provider.apply(PositioningConfig(anchors = anchorMap, sessionId = state.sessionId, zones = state.zones))
    }

    /** 시작(매장 진입 시) — 측위 가동. 서버 왕복 없음 (prepare 가 미리 끝나 있다). */
    suspend fun start(provider: PositioningProvider) {
        // 내려가는 중이면 그 정지가 끝나기를 기다렸다가 이어서 켠다.
        stopInFlight?.await()
        if (isRunning) { // 멱등
            // ⚠️ 조용히 돌아가지 않는다 — 삼켜진 start 하나가 현장에서 안 보였다.
            log(LogLevel.WARN, SdkLocalized.t("coord.startIgnored"))
            return
        }
        if (!isPrepared) throw SdkError.NotInitialized()
        requireUserId() // 인증이 앞에 있어야 한다

        // 층 상태 주입 — setFloorMap 으로 받아둔 층이 있을 때만. 없으면 조용히 두지 않고 알린다.
        this.provider = provider
        if (floorState != null) {
            applyFloorStateToProvider()
        } else {
            // 엔진이 BLE 로 층을 찾는 흐름에서는 **여기가 정상 경로다** — 서버에 E3001 을 올리지 않는다(iOS #54).
            // 예전엔 시작할 때마다 올라가 콘솔 로그가 이 줄로 덮였다. 층이 **끝내** 안 잡히는 것은 E3007 이 알린다.
            // 화면 로그(onDebugLog)에는 INFO 로 남긴다 — "왜 아직 좌표가 없지" 의 답이다.
            log(LogLevel.INFO, SdkLocalized.t("coord.noFloorLoaded"))
        }

        // 방문 시작
        visitorId = identity.newVisitorId()
        uploads.beginSession()
        report(SdkInfoCode.POSITIONING_ON, "visitor=$visitorId")
        // 새 세션은 일시정지 없이 시작한다 — provider 는 생명주기 재시작 때 일시정지를 유지하므로(S20) 지난 세션의
        // 일시정지가 남아 있을 수 있다(iOS 와 같다).
        if (provider.isPaused) provider.resume()
        provider.delegate = this
        supervisor.reset()
        closingAfterEngineStop = false
        pauseToRestore = false
        // ⚠️ isRunning 을 **먼저** 세운다. 엔진은 시작 안에서 동기로 접힐 수 있다(라이선스 없음·권한 이미 거부) —
        //    그 알림(onStoppedUnexpectedly)이 isRunning=false 일 때 오면 무시돼, 세션은 「측위 중」 인 채 엔진만
        //    죽은 상태로 남았다(iOS #54 와 같다).
        isRunning = true
        try {
            provider.start()
        } catch (e: Throwable) {
            isRunning = false
            throw e
        }
        // 시작 안에서 이미 닫기로 했다(재시도 불가) — 타이머·스트림을 걸지 않는다. 닫기가 정리한다.
        if (closingAfterEngineStop || !isRunning) return
        uploads.startTimer()
        startReceptionCheck()
        ensureLiveStream()
        observeAppLifecycleIfNeeded()
    }

    /**
     * 측위를 켠 뒤 한 번, 신호가 실제로 잡히고 있는지 본다.
     *
     * ⚠️ 미수신을 고장으로 단정하지 않는다(서브가 빠져도 측위는 계속된다) — WARN.
     * 한 번만 본다. 주기적으로 남기면 같은 줄이 로그를 덮는다.
     */
    private fun startReceptionCheck() {
        receptionCheckJob?.cancel()
        receptionCheckJob = scope.launch {
            // 내장 엔진은 층을 잡아야(TRACKING) 좌표를 낸다 — 층을 찾는 동안 재면 정상인데 E4002 가 났다(감사 SP-B8).
            // 층을 끝내 못 찾는 것은 E3007(20초)의 몫이다. 층을 잡은 뒤부터 [receptionCheckDelayMs] 를 잰다.
            val uwb = provider as? UwbPositioningProvider
            if (uwb != null) {
                while (isActive && isRunning && uwb.phase != UwbPositioningProvider.PositioningPhase.TRACKING) {
                    delay(RECEPTION_POLL_MS)
                }
            }
            delay(receptionCheckDelayMs)
            // 배경이면 재지 않는다 — 복귀(onForegroundResumed)가 다시 건다.
            if (!isActive || !isRunning || !appInForeground) return@launch
            val d = provider?.positioningDiagnostic ?: return@launch // 진단 없는 provider

            // ⚠️ 두 갈래를 하나로 합치지 말 것 — 앵커별 상태를 못 주는 엔진에서는 예전 조건이
            //    둘 다 영원히 거짓이 되어 좌표가 안 나와도 아무 로그가 안 남았다.
            if (d.canAttributePerAnchor) {
                if (d.missingAddresses.isNotEmpty()) {
                    // 마스터인지 서브인지는 모른다(주소만 안다) — 사실만 적는다.
                    report(
                        SdkErrorCode.LOCATOR_NOT_RECEIVED,
                        "registered=${d.registeredCount} received=${d.receivedCount} missing=${d.missingLabel}",
                    )
                }
                // 신호는 충분한데 좌표가 안 나오면 등록 좌표와 실제 배치가 어긋났을 수 있다 — ERROR.
                if (!d.hasFix && d.matchedCount >= 3) {
                    report(SdkErrorCode.NO_POSITION_FIX, "matched=${d.matchedCount} fix=none")
                }
            } else {
                // 앵커별 특정 불가 — 물을 수 있는 것은 "좌표가 나오는가" 하나뿐이다.
                if (!d.hasFix && d.registeredCount > 0) {
                    report(
                        SdkErrorCode.NO_POSITION_FIX,
                        "registered=${d.registeredCount} fix=none (per-anchor detail unavailable)",
                    )
                }
            }
        }
    }

    /** 프로필 연결 — 좌표·존 이벤트가 이 ID 로 귀속된다. */
    fun identify(profileId: String?) {
        this.profileId = profileId
        if (profileId != null) {
            report(SdkInfoCode.IDENTIFIED)
            // 프로필이 생기기 전에 쌓인 로그(초기화 중 E1007 등)를 이제 보낸다(SP-B7 · iOS S13).
            scope.launch { reporter.flush() }
        }
    }

    // MARK: - 프로필 CRUD (키 검증 통과가 전제라 coordinator 경유)

    suspend fun createProfile(attributes: Map<String, String>): String = api.createProfile(attributes).profileId

    suspend fun getProfile(profileId: String): Map<String, String> = api.getProfile(profileId).attributes ?: emptyMap()

    suspend fun putProfile(profileId: String, attributes: Map<String, String>) {
        api.putProfile(profileId, attributes)
    }

    suspend fun deleteProfile(profileId: String) {
        api.deleteProfile(profileId)
    }

    // MARK: - 버퍼 창구

    /** 쌓인 좌표를 지금 전송 (300건/60초를 기다리지 않고 앞당김) — OneS1ght.send(). */
    suspend fun flush() {
        uploads.flush()
    }

    /** 쌓인 좌표를 전송 없이 폐기 — OneS1ght.empty(). */
    fun empty() {
        uploads.discard()
    }

    /**
     * 진행 중인 정지 — `start` 는 이걸 기다렸다가 이어서 켠다.
     *
     * ⚠️ 이게 없어서 [측위 종료] 뒤 재시작이 영영 안 살아났다(iOS 2026-09-10 실기기).
     * `isRunning = false` 는 flush 왕복이 끝난 뒤에야 서므로 그 창에 온 start 가 삼켜졌다.
     */
    private var stopInFlight: Deferred<Unit>? = null

    /**
     * 구역만 바꾼다 — 앵커·세션은 그대로(감사 SP-C9). 내장 provider 는 SDK 안의 전용 길([UwbPositioningProvider.applyZones])
     * 로 받고, 그 밖의 provider 는 iOS 와 같은 공개 계약대로 앵커가 빈 `apply(PositioningConfig(zones = …))` 로 받는다.
     */
    private fun applyZonesOnly(zones: List<Zone>) {
        when (val p = provider) {
            is UwbPositioningProvider -> p.applyZones(zones)
            null -> Unit
            else -> p.apply(PositioningConfig(zones = zones))
        }
    }

    /**
     * 종료: 측위 정지 + 잔여 좌표 flush. 이미 내려가는 중이면 그 정지에 합류한다. 끝나면 [onSessionClosed] 에
     * [reason] 을 넘긴다(가동 중이 아니었으면 아무것도 안 한다 — iOS 와 같다).
     */
    suspend fun stop(reason: FloorSession.StopReason = FloorSession.StopReason.ENDED) {
        stopInFlight?.let {
            it.await()
            return
        }
        if (!isRunning) return
        // LAZY — 자리를 먼저 잡고 시작한다. Main.immediate 에서 곧바로 돌면 끝난 뒤에야
        // stopInFlight 가 채워져, 끝난 정지가 다음 stop 을 막는다.
        val task = scope.async(start = CoroutineStart.LAZY) { performStop(reason) }
        stopInFlight = task
        task.invokeOnCompletion { if (stopInFlight === task) stopInFlight = null }
        task.await()
    }

    private suspend fun performStop(reason: FloorSession.StopReason) {
        supervisor.reset()
        // 끝낸 세션에 일시정지를 남기지 않는다 — provider 의 stop() 은 일시정지를 풀지 않으므로(iOS #55) 여기서 푼다.
        provider?.let { if (it.isPaused) it.resume() }
        provider?.stop()
        uploads.stopTimer()
        receptionCheckJob?.cancel()
        receptionCheckJob = null
        // 층을 계속 보고 있으면 스트림은 그대로 둔다 — 측위를 껐다고 콘솔 변경까지 안 받을
        // 이유는 없다. ⚠️ iOS 는 여기서 isRunning 이 아직 true 인 채로 판정해 층이 없어도
        // 스트림·관찰자가 남았다(사양서 §4.3 "층이 지정됨 || 측위 중" 과 어긋남). 여기서는
        // 측위가 곧 꺼진다는 전제(running=false)로 판정한다. isRunning 자체는 맨 끝에 내린다.
        if (!LiveStreamController.streamWanted(floorSet = floorState != null, running = false)) {
            liveStream.detach()
            removeLifecycleObservers()
        } else {
            ensureLiveStream()
        }
        log(SdkLocalized.t("coord.stopFlush", uploads.pendingCount))
        uploads.flush()
        if (uploads.pendingCount > 0) {
            report(
                SdkErrorCode.PENDING_DROPPED,
                "points=${uploads.pendingCount}",
                message = SdkLocalized.t("coord.pendingLost", uploads.pendingCount),
            )
        }
        report(SdkInfoCode.POSITIONING_OFF, "visitor=$visitorId")
        reporter.flush() // 세션 종료 — 잔여 로그도 내보낸다
        isRunning = false
        closingAfterEngineStop = false
        pauseToRestore = false
        // 앱에 알린다 — 엔진이 포기해 닫힌 세션을 앱이 모르면 화면은 「찾는 중」 에 머문다(iOS S6).
        onSessionClosed?.invoke(reason)
    }

    // MARK: - 실시간 수신 (SSE)

    /** 스트림이 붙어 있어야 하는가 — 층이 정해졌거나 측위가 도는 동안. */
    val liveStreamWanted: Boolean
        get() = LiveStreamController.streamWanted(floorSet = floorState != null, running = isRunning)

    /** 필요하면 붙이고, 필터가 그대로면 아무것도 하지 않는다(멱등) — [LiveStreamController.ensure]. */
    private fun ensureLiveStream() {
        liveStream.ensure(liveStreamWanted, floorState)
    }

    /**
     * 코디네이터를 버리기 전 정리. stop() 은 층이 남아 있으면 스트림을 일부러 살려 두므로,
     * 참조를 놓는 쪽에서 이걸 부르지 않으면 열린 연결이 남는다. 측위 정지는 하지 않는다
     * (iOS 와 같다 — 그건 stop() 의 몫이고, 부르는 쪽이 먼저 끝낸다). suspend 인 것은
     * 진행 중인 정지와 겹치지 않게 그 끝을 기다리기 위해서다.
     */
    suspend fun teardown() {
        stopInFlight?.await()
        liveStream.detach()
        removeLifecycleObservers()
    }

    /**
     * 고객사에게 그대로 넘긴다. SDK 가 하는 일은 하나 — 도면이 바뀌었으면 SDK 안의 도면 캐시를 버린다
     * (안 버리면 앱이 floor() 를 다시 불러도 옛 도면이 온다 — 감사 SF-A15 · iOS S16). 무엇을 다시 받을지는
     * 여전히 앱이 정한다.
     */
    fun deliverConfigChange(change: ConfigChange) {
        if (change is ConfigChange.PlanChanged) spaceClient?.invalidatePlan(change.floorId)
        onConfigChange?.invoke(change)
    }

    /**
     * setFloorMap 이 건물·층을 실제로 바꿨을 때만 스트림을 다시 붙인다.
     * 층을 비우는 것도 "바뀜"이다 — 다음 층을 고르는 중일 수 있다.
     */
    private fun restartLiveStreamIfFloorChanged(previousFloor: FloorState?) {
        if (!LiveStreamController.filterChanged(previousFloor, floorState)) return
        observeAppLifecycleIfNeeded() // 측위 없이 층만 봐도 배경 전환을 다뤄야 한다
        ensureLiveStream()
    }

    // MARK: - verify 재료

    /** profileId 가 없으면 세션이 성립하지 않는다 — 데이터에 주인이 없으면 리포트가 성립하지 않는다. */
    private fun requireUserId(): String {
        val id = profileId
        if (id.isNullOrEmpty()) throw SdkError.NotIdentified()
        return id
    }

    private fun makeVerifyRequest() = ReqVerify(platformName = SdkPlatform.NAME, appId = appId)

    // MARK: - 생명주기 (백그라운드 · 포그라운드)

    /**
     * 측위가 돌고 있고 내려가는 중도 아닌가 — 생명주기 처리가 provider 를 건드려도 되는 조건.
     * 정지가 flush 에 매달린 사이(isRunning 은 아직 true)에 provider 를 다시 켜면, 정지가 끝난 뒤
     * isRunning=false 인데 provider 만 도는 유령 세션이 남는다.
     */
    private val runningAndNotStopping: Boolean
        get() = isRunning && stopInFlight == null && !closingAfterEngineStop

    /** observe() 호출 안에서 동기로 들어오는 통지(ProcessLifecycleOwner 의 catch-up)를 거르는 표시. */
    private var attachingObserver = false

    private fun observeAppLifecycleIfNeeded() {
        val lifecycle = lifecycle ?: return
        if (observingLifecycle) return
        observingLifecycle = true
        attachingObserver = true
        try {
            lifecycle.observe(
                // 백그라운드: UWB 는 어차피 정지(포그라운드 전용) → 측위 정지 + 잔여 flush
                onBackground = {
                    if (!attachingObserver) {
                        scope.launch {
                            appInForeground = false
                            // 내려가면 다시 켜 보기를 멈춘다 — 배경에선 UWB 가 안 돈다. 돌아오면 아래에서 켠다.
                            supervisor.cancel()
                            receptionCheckJob?.cancel() // 배경에서는 재지 않는다(SP-B8) — 복귀가 다시 건다
                            receptionCheckJob = null
                            if (runningAndNotStopping) { // 측위는 세션이 돌 때만, 정지 중이면 그 정지에 맡긴다
                                provider?.let { p ->
                                    // 사용자가 건 일시정지는 생명주기 재시작에서 유지한다(iOS S20) — 복귀 때 다시 건다.
                                    pauseToRestore = p.isPaused
                                    // 배경에서는 UWB 가 멈춰 OUT 이 안 온다 — 지금 구역에서 나간 것으로 친다(SP-B15).
                                    (p as? UwbPositioningProvider)?.exitActiveZoneBeforeBackground()
                                    p.stop()
                                }
                                uploads.flush()
                            }
                            liveStream.suspend() // 스트림은 언제나 끊는다
                        }
                    }
                },
                // 포그라운드 복귀: 측위 재개 + 실시간 수신 재연결. 재연결 자체가 ResyncNeeded 를
                // 올린다 — 배경에 있던 동안의 변경을 고객사가 따라잡는 유일한 경로다.
                // ⚠️ 붙이는 순간의 catch-up 통지는 버린다 — 부른 쪽(start·setFloorMap)이 이미
                // 스트림을 붙였다. 받아들이면 연결이 하나 더 열리고 ResyncNeeded 가 두 번 간다.
                onForeground = {
                    if (!attachingObserver) {
                        scope.launch { onForegroundResumed() }
                    }
                },
            )
        } finally {
            attachingObserver = false
        }
    }

    private fun onForegroundResumed() {
        appInForeground = true
        // 복귀는 새 기회다 — 내려가기 전의 재시도 횟수는 잊는다. 이 시작이 접히면 provider 가
        // onStoppedUnexpectedly 로 알려 오고, 거기서 다시 켜 보거나 세션을 닫는다.
        supervisor.resetAttempts()
        val active = runningAndNotStopping
        if (active) {
            provider?.let(::restartKeepingPause)
            startReceptionCheck() // 복귀 후 다시 잰다(SP-B8)
        }
        // 정지 중이면 스트림을 둘지 말지는 그 정지(performStop)가 정한다.
        if (!LiveStreamController.streamWanted(floorSet = floorState != null, running = active)) return
        // 배경에서 끊긴 것 — 앞 연결을 확실히 닫고 새로 붙인다(중복 통지로 연결이 새지 않게).
        liveStream.forgetConnection()
        ensureLiveStream()
    }

    private fun removeLifecycleObservers() {
        if (!observingLifecycle) return
        observingLifecycle = false
        lifecycle?.stopObserving()
    }

    private fun iso(ms: Long): String = Iso8601.format(ms)

    // MARK: - PositioningProviderDelegate

    /** 엔진이 층을 잡았다/잃었다 — 앱에 그대로 넘긴다(층 고르기는 앱의 몫). 물려 있는 provider 것만. */
    override fun onFloorDetected(provider: PositioningProvider, floorId: String?) {
        if (this.provider !== provider) return
        onFloorDetected?.invoke(floorId)
    }

    /** 앱에 보일 구역 이벤트 — 세션이 도는 동안만(iOS `isLiveSession`). */
    override fun onEmit(provider: PositioningProvider, event: ZoneEvent) {
        if (!runningAndNotStopping || this.provider !== provider) return
        onZoneEvent?.invoke(event)
    }

    /** 엔진 진단 → 표준 경로(onLog + 서버 E-코드). */
    override fun onReport(provider: PositioningProvider, code: SdkErrorCode, context: String) {
        report(code, context)
    }

    /**
     * 엔진이 스스로 꺼졌다 — 다시 켜 보거나(포그라운드·재시도 가능·횟수 남음), 세션을 닫는다(iOS #54 와 같다).
     *
     * ⚠️ 그대로 두지 않는다. 세션이 "측위 중" 인 채 엔진만 죽어 있으면 앱의 `begin()` 이 삼켜지고,
     *    화면은 「찾는 중」인데 아무것도 안 도는 상태가 앱을 껐다 켤 때까지 간다(감사 SP-B1).
     */
    override fun onStoppedUnexpectedly(provider: PositioningProvider, retryable: Boolean, context: String) {
        if (!isRunning || stopInFlight != null || closingAfterEngineStop || this.provider !== provider) return
        supervisor.handleUnexpectedStop(
            retryable = retryable,
            context = context,
            beforeRetry = { pauseToRestore = provider.isPaused }, // 다시 켜도 사용자가 건 일시정지는 그대로(iOS S20)
            canRestart = { runningAndNotStopping && this.provider === provider },
            inForeground = { appInForeground },
            restart = { restartKeepingPause(provider) },
            giveUp = {
                closingAfterEngineStop = true
                scope.launch { stop(FloorSession.StopReason.ENGINE_FAILED) }
            },
        )
    }

    /**
     * 내려가기 전에 사용자가 건 일시정지 — 생명주기 재시작(배경 복귀·엔진 재시도)이 provider 를 다시 켜면 풀리므로
     * 다시 건다(iOS S20: 예전엔 배경에 한 번 다녀오면 멈춘 측위가 다시 좌표·구역 이벤트를 올렸다). 앱의
     * begin()·end() 는 이 값을 지운다.
     */
    private var pauseToRestore = false

    private fun restartKeepingPause(p: PositioningProvider) {
        val keepPaused = pauseToRestore
        pauseToRestore = false
        p.start()
        if (keepPaused) p.pause()
    }

    /** 좌표 fix — 앱 훅(onPosition) + 다운샘플 후 버퍼 적재, 임계를 넘는 순간 flush(실패 뒤엔 backoff). */
    override fun onPosition(provider: PositioningProvider, coordinates: Coordinates, floorId: String?, atMs: Long) {
        supervisor.resetAttempts() // 다시 살아났다 — 다음 고장은 처음부터 센다
        onPosition?.invoke(coordinates) // 앱 훅 — 원속도 유지 (지도 렌더)
        // 안드로이드 provider 는 층을 모를 수 있다(null) — 지정된 층으로 귀속한다. 그것도 없으면
        // floor_id 없이는 서버 계약이 성립하지 않으므로 싣지 않는다.
        val floor = floorId ?: floorState?.floorId ?: return
        uploads.record(coordinates, floor, atMs)
    }

    /** 존 판정 — 즉시 전송 (network 실패만 1회 재시도), triggers 는 호스트 콜백으로. DWELL 은 안 보낸다. */
    override fun onZone(provider: PositioningProvider, zoneId: String, status: ZoneEventStatus, floorId: String?, atMs: Long) {
        // DWELL 은 앱 콜백 전용이다(사양서 §6) — 서버 계약은 IN/OUT 뿐.
        if (status == ZoneEventStatus.DWELL) return
        val floor = floorId ?: floorState?.floorId ?: return
        val profileId = profileId ?: return
        val req = ReqZoneEvent(
            profileId = profileId,
            visitorId = visitorId,
            floorId = floor,
            zoneId = zoneId,
            status = status.wire,
            occurredAt = iso(atMs),
            platformName = SdkPlatform.NAME,
        )
        val ctx = "zone=$zoneId status=${status.wire} dropped"
        scope.launch {
            val res: ResZoneEvent = try {
                api.sendZoneEvent(req).also {
                    log(SdkLocalized.t("coord.zoneSent", status.wire, it.triggers.size))
                }
            } catch (e: ApiError.Network) {
                // 소량 재시도 (사양서 §9) — 1회만, 그래도 실패면 드랍 (인메모리 v1)
                val retried = try {
                    api.sendZoneEvent(req)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    null
                }
                if (retried == null) {
                    report(SdkErrorCode.NETWORK, ctx, message = SdkLocalized.t("coord.zoneDropNet", status.wire))
                    return@launch
                }
                log(LogLevel.INFO, SdkLocalized.t("coord.zoneRetryOK", status.wire))
                retried
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportApi(e, ctx, message = SdkLocalized.t("coord.zoneDropErr", status.wire)) // 서버 500이면 여기
                return@launch
            }
            onTriggers?.invoke(zoneId, res.triggers)
        }
    }

    internal companion object {
        /** 수신 점검 — 엔진이 층을 잡았는지 보는 간격. */
        const val RECEPTION_POLL_MS: Long = 1_000L

        /**
         * 엔진이 지오펜스를 다시 읽어야 하는가 — **어느 구역이 있느냐**만 본다(id 집합).
         * 구역을 다시 그리면 콘솔이 새 id 를 주므로 도형이 바뀐 경우도 여기서 잡힌다.
         */
        fun geofencesChanged(old: List<Zone>, new: List<Zone>): Boolean =
            old.map { it.id }.toSet() != new.map { it.id }.toSet()

        /** [LiveStreamController.filterChanged] 와 같다 — 기존 호출부(테스트)용 이름. */
        fun floorFilterChanged(previous: FloorState?, next: FloorState?): Boolean =
            LiveStreamController.filterChanged(previous, next)

        /** [LiveStreamController.streamWanted] 와 같다 — 운영 경로(liveStreamWanted)도 그것을 쓴다. */
        fun streamWanted(floorSet: Boolean, running: Boolean): Boolean = LiveStreamController.streamWanted(floorSet, running)
    }
}
