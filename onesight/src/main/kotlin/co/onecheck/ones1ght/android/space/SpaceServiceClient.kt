package co.onecheck.ones1ght.android.space

//
//  SpaceServiceClient.kt
//  공간 서비스 연동 — 한 호스트, 두 키.
//
//  · /api/m/floors/{id}/plan       (X-SDK-Key = 공간 서비스 키) → 도면 이미지(base64) + widthM + origin
//  · /api/m/floors/{id}/anchors    (X-SDK-Key = 공간 서비스 키) → 앵커(도면 로컬 미터)
//  · 존은 콘솔(X-SDK-Key = SDK 키)에서 — 파트너 키는 쓰기 권한이 있어 클라이언트 배포 금지
//
//  ⚠️ SDK 내부 전용 — 호스트 앱은 이 타입을 모른다. 앵커·세션·도면·존이 어디서 오는지는
//     SDK 사정으로 감춘다.
//
//  포팅 원본: SpaceServiceClient.swift.
//

import co.onecheck.ones1ght.android.model.Building
import co.onecheck.ones1ght.android.model.Floor
import co.onecheck.ones1ght.android.model.FloorLocators
import co.onecheck.ones1ght.android.model.FloorState
import co.onecheck.ones1ght.android.model.Locator
import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneDefaults
import co.onecheck.ones1ght.android.internal.decodeLenientList
import co.onecheck.ones1ght.android.network.ApiClient
import co.onecheck.ones1ght.android.network.ApiError
import co.onecheck.ones1ght.android.network.pathSegment
import co.onecheck.ones1ght.android.network.performJsonRequest
import co.onecheck.ones1ght.android.runtime.SdkTimeouts
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 공간 조회 — 건물·층·도면·로케이터·구역. [sdkKey] 는 콘솔(§4.2 표), [spaceKey] 는 공간
 * 서비스(`/config` 의 `geo_sdk_key`) 호출에 쓴다.
 *
 * [consoleBase] 는 `initialize` 의 baseUrl 이다 — 자체 서버 고객의 공간 조회가 우리 서버로 새지 않게(감사 SF-A9 ·
 * iOS S15, 예전엔 늘 기본 주소였다). [spaceHost] 는 콘솔 `/config` 의 `geo_base_url`([spaceHostFor] 로 검사).
 */
internal class SpaceServiceClient(
    private val sdkKey: String,
    private val spaceKey: String,
    http: OkHttpClient,
    private val consoleBase: String = ApiClient.DEFAULT_BASE_URL,
    private val spaceHost: String = SPACE_HOST,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    internal companion object {
        internal const val SPACE_HOST: String = "https://geospace.geoplan.io/"
        internal const val ANCHOR_TTL_MS: Long = 180_000L

        /**
         * 도면 캐시 수명 — plan.changed 신호를 놓쳐도(배경·끊김) 도면이 프로세스 끝까지 낡지 않게(감사 SF-A15 ·
         * iOS S16). 신호를 받으면 그 자리에서 버린다([invalidatePlan]).
         */
        internal const val PLAN_TTL_MS: Long = 10 * 60_000L

        /**
         * 구역 폴리곤이 픽셀인지 가르는 여유 — 점 하나라도 도면 치수(미터)의 이 배수를 넘으면 픽셀로 본다(사양서 §4.2).
         */
        internal const val PIXEL_DETECT_MARGIN: Double = 1.5

        /** 층 이름이 없을 때 대신 보여 줄 층 ID 앞부분 길이. */
        private const val FLOOR_NAME_FALLBACK_LENGTH = 8

        /** 이름 없는 층의 표시 이름 — 층 ID 앞부분. 네 곳(콘솔 목록·도면 유무 두 갈래)이 같은 규칙을 쓴다. */
        internal fun fallbackFloorName(floorId: String): String = floorId.take(FLOOR_NAME_FALLBACK_LENGTH)

        /**
         * 콘솔이 준 `geo_base_url` → 공간 서비스 주소. 없거나 URL 이 아니거나 https 가 아니면 [SPACE_HOST]
         * (감사 SF-A11: 예전엔 값을 무시하고 늘 하드코딩 주소였다 — 공간 서비스 호스트가 바뀌면 배포된 앱이
         * 앵커를 못 받는다). 공간 서비스 키가 실리므로 평문 http 는 받지 않는다 — 루프백(개발·테스트)만 예외.
         */
        internal fun spaceHostFor(geoBaseUrl: String?): String {
            val url = geoBaseUrl?.trim()?.toHttpUrlOrNull() ?: return SPACE_HOST
            val loopback = url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1"
            if (url.scheme != "https" && !loopback) return SPACE_HOST
            return url.toString().trimEnd('/') + "/"
        }
    }

    /** 콘솔·공간 서비스 둘 다 [SdkTimeouts.SPACE_SECONDS] — [http] 의 다른 설정(예: 재시도 정책)은 그대로 물려받는다. */
    private val spaceHttp: OkHttpClient = http.newBuilder()
        .connectTimeout(SdkTimeouts.SPACE_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SdkTimeouts.SPACE_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SdkTimeouts.SPACE_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 서버 값 일부를 버렸을 때의 진단 문구(폴리곤 점·존, 읽을 수 없는 목록 항목) — 코어가 받아 화면 로그(WARN)로
     * 남긴다. 공간 조회는 코어 디스패처에서만 돌므로 같은 스레드에서 불린다.
     */
    var onDataWarning: ((String) -> Unit)? = null

    /**
     * 같은 이름의 구역을 버렸다 — 엔진은 영역을 **이름**으로만 알려 주므로 같은 이름 두 번째 구역은 매핑될 수
     * 없다(감사 SF-A12: 예전엔 로그 없이 버려 구역 하나가 원인 없이 사라졌다). 코어가 E3009 로 올린다.
     */
    var onDuplicateZoneName: ((name: String, keptId: String, droppedId: String) -> Unit)? = null

    // 세션 캐시 — plan 은 거의 정적(층이름 겸 선로딩)이라 길게, 앵커는 전원상태(clusterStatus)가 변할 수 있어 짧게.
    private val planCache = mutableMapOf<String, Pair<Long, ConsolePlanResponse>>()
    private val anchorCache = mutableMapOf<String, Pair<Long, AnchorResponse>>()

    // MARK: - 공개 진입점

    /** 건물 목록 — 콘솔이 기본, 실패하거나 비면 공간 서비스로 폴백. 층은 담지 않는다. */
    suspend fun buildings(): List<Building> {
        consoleBuildingsOrNull()?.let { return it }
        val res = spaceGet<SpaceBuildingsResponse>("api/m/buildings")
        return res.buildings.map { b ->
            Building(id = b.buildingId, name = b.buildingName, floorCount = b.floors.size)
        }
    }

    private suspend fun consoleBuildingsOrNull(): List<Building>? {
        val res = try {
            consoleGet<ConsoleBuildingsResponse>("/positioning/buildings")
        } catch (e: ApiError) {
            return null
        }
        val out = decodeLenientList(res.buildings, ConsoleBuildingDto.serializer(), dropped("building"))
            .filter { !it.buildingId.startsWith("sim-") } // 공간 서비스 미연동 sim 매장 제외
            .map { Building(id = it.buildingId, name = it.name, floorCount = it.floorCount) }
        return out.ifEmpty { null }
    }

    /**
     * 층 목록 — 이름·hasPlan 만 채우고 도면은 받지 않는다(도면은 [loadFloor] 로 단건 조회).
     * 콘솔 층이 비거나 실패하면 공간 서비스 건물 트리로 우회한다.
     */
    suspend fun floors(buildingId: String): List<Floor> {
        val fromConsole = try {
            consoleGet<ConsoleFloorsResponse>("/positioning/buildings/${pathSegment(buildingId)}/floors")
        } catch (e: ApiError) {
            null
        }
        val consoleFloors = fromConsole?.let { decodeLenientList(it.floors, ConsoleFloorDto.serializer(), dropped("floor")) }
        if (!consoleFloors.isNullOrEmpty()) {
            return consoleFloors.map {
                Floor(id = it.floorId, name = it.name ?: fallbackFloorName(it.floorId), hasPlan = it.hasPlan ?: false)
            }
        }
        val res = spaceGet<SpaceBuildingsResponse>("api/m/buildings")
        val floors = res.buildings.firstOrNull { it.buildingId == buildingId }?.floors ?: emptyList()
        return floors.map { Floor(id = it.floorId, name = it.floorName, hasPlan = it.hasPlan) }
    }

    /**
     * 층 단건 — 도면 이미지까지 채워 반환. 실패 시 이름은 [fallbackFloorName].
     * base64 → PNG 바이트 변환은 Default 에서 한다 — 수 MB 도면을 코어(메인)에서 풀면 ANR 이었다(SF-A13).
     */
    suspend fun loadFloor(buildingId: String, floorId: String): Floor {
        val plan = try {
            consolePlan(buildingId, floorId)
        } catch (e: ApiError) {
            null
        }
        return withContext(Dispatchers.Default) { makeFloor(floorId, plan, withImage = true) }
    }

    /**
     * 존만 재조회(존 등록 대기 폴링용) — 미터로 정규화까지 마쳐 반환한다. **던진다**: 통신
     * 실패를 "존 0개"로 삼키면 새로고침이 지도의 존을 지워버린다. 단 404 는 예외 — 서버가
     * 존 없는 층에 빈 배열 대신 404 를 주므로 0개로 읽는다.
     */
    suspend fun loadZones(buildingId: String, floorId: String): List<Zone> {
        val raw = try {
            consoleZones(buildingId, floorId)
        } catch (e: ApiError.NotFound) {
            return emptyList()
        }
        return normalizeZones(raw, planCache[floorId]?.second?.plan?.image)
    }

    /**
     * 로케이터 + 세션ID. 실패하면 **던지지 않고 null** 을 돌려준다 — 로케이터를 못 받아도
     * 층은 열려야 한다(도면·존은 앵커와 무관하게 이미 받아 온 것이다).
     */
    suspend fun loadLocators(floorId: String): FloorLocators? {
        val anchors = anchorsOrNull(floorId) ?: return null
        return FloorLocators(
            locators = anchors.mapNotNull { it.toLocator() },
            sessionId = sessionIdOf(anchors),
        )
    }

    /**
     * 측위·판정 재료(로케이터·세션·존) 로드 — 도면 메타(plan)·앵커·존을 병렬로 받는다.
     * 도면이 없는 층은 오류가 아니다 — 좌표는 로케이터 배치에서 나오지, 도면이 아니다.
     */
    suspend fun loadFloorState(buildingId: String, floorId: String): FloorState = coroutineScope {
        val planDeferred = async { planImageIfAny(buildingId, floorId) }
        val anchorDeferred = async { anchorsOrNull(floorId) }
        val zonesDeferred = async { getZonesSilent(buildingId, floorId) }
        val planImage = planDeferred.await()
        val anchors = anchorDeferred.await()
        val zonesRaw = zonesDeferred.await()

        val zones = normalizeZones(zonesRaw, planImage)
        FloorState(
            buildingId = buildingId,
            floorId = floorId,
            sessionId = anchors?.let(::sessionIdOf),
            locators = anchors?.mapNotNull { it.toLocator() } ?: emptyList(),
            zones = zones,
            hasPlan = planImage != null,
            locatorsFetchFailed = anchors == null,
        )
    }

    // MARK: - 도면

    /** ConsolePlanResponse → Floor. withImage=false 면 치수·이름만 채우고 PNG 는 뺀다. */
    private fun makeFloor(id: String, plan: ConsolePlanResponse?, withImage: Boolean): Floor {
        val img = plan?.plan?.image
        if (plan == null || img == null) {
            return Floor(id = id, name = plan?.floorName ?: fallbackFloorName(id), hasPlan = plan?.hasPlan ?: false)
        }
        val heightM = img.widthM * img.imgH / img.imgW
        return Floor(
            id = id,
            name = plan.floorName ?: fallbackFloorName(id),
            image = if (withImage) img.pngData() else null,
            hasPlan = plan.hasPlan,
            originX = img.originX,
            originY = img.originY,
            widthM = img.widthM,
            heightM = heightM,
        )
    }

    /**
     * 도면 — console 프록시 우선(+세션 캐시), 실패 시 공간 서비스 직행 폴백. 도면이 없는
     * 층이면 null. 콘솔이 `has_plan:false` 를 **명시**하면 거기서 끝낸다(폴백을 타지 않는다) —
     * 콘솔 조회 자체가 실패했을 때만(오래된 서버·네트워크) 폴백을 탄다.
     */
    private suspend fun planImageIfAny(buildingId: String, floorId: String): PlanImage? {
        val res = try {
            consolePlan(buildingId, floorId)
        } catch (e: ApiError) {
            null
        }
        if (res != null) {
            return if (res.hasPlan) res.plan?.image else null
        }
        val fallback = try {
            spaceGet<SpacePlanResponse>("api/m/floors/${pathSegment(floorId)}/plan")
        } catch (e: ApiError) {
            null
        }
        return fallback?.plan?.image
    }

    /**
     * 도면 캐시를 버린다 — 콘솔이 도면이 바뀌었다고 알렸을 때(plan.changed). [floorId] 가 null 이면 전부.
     * 다음 조회가 새로 받는다.
     */
    fun invalidatePlan(floorId: String?) {
        if (floorId == null) planCache.clear() else planCache.remove(floorId)
    }

    /** console 도면 프록시. 층별 캐시([PLAN_TTL_MS]). */
    private suspend fun consolePlan(buildingId: String, floorId: String): ConsolePlanResponse {
        planCache[floorId]?.let { (at, cached) -> if (clock() - at < PLAN_TTL_MS) return cached }
        val res = consoleGet<ConsolePlanResponse>(
            "/positioning/buildings/${pathSegment(buildingId)}/floor/${pathSegment(floorId)}/plan",
        )
        planCache[floorId] = clock() to res
        return res
    }

    // MARK: - 앵커

    /**
     * 앵커 조회 — 실패를 값으로 돌려준다(null = 못 받음). 던지면 층 전체가 무너진다. 앵커는 하나씩 읽는다 —
     * 하나가 깨져도 나머지로 측위한다(SF-A5).
     */
    private suspend fun anchorsOrNull(floorId: String): List<AnchorDto>? {
        val res = try {
            getAnchorsCached(floorId)
        } catch (e: ApiError) {
            return null
        }
        return decodeLenientList(res.anchors, AnchorDto.serializer(), dropped("anchor"))
    }

    /**
     * 층의 UWB 세션 ID — 값이 있는 첫 앵커에서. 첫 앵커만 보면 그 앵커만 세션이 비었을 때 층 전체가 E3003 으로
     * 측위를 못 했다(감사 SF-A14).
     */
    private fun sessionIdOf(anchors: List<AnchorDto>): Int? = anchors.firstNotNullOfOrNull { it.sessionId }

    /** 앵커 — 공간 서비스 유일 잔존(콘솔 미제공). TTL [ANCHOR_TTL_MS] 캐시로 층 재방문 시 즉시. */
    private suspend fun getAnchorsCached(floorId: String): AnchorResponse {
        val now = clock()
        anchorCache[floorId]?.let { (at, cached) -> if (now - at < ANCHOR_TTL_MS) return cached }
        val res = spaceGet<AnchorResponse>("api/m/floors/${pathSegment(floorId)}/anchors")
        anchorCache[floorId] = now to res
        return res
    }

    // MARK: - 존

    /** zone — 콘솔 단일 소스. loadZones 처럼 던지는 대신 실패를 조용히 삼킨다(loadFloorState 전용). */
    private suspend fun getZonesSilent(buildingId: String, floorId: String): List<RawZone> =
        try {
            consoleZones(buildingId, floorId)
        } catch (e: ApiError) {
            emptyList()
        }

    private suspend fun consoleZones(buildingId: String, floorId: String): List<RawZone> {
        val res = consoleGet<ConsoleZonesResponse>(
            "/positioning/buildings/${pathSegment(buildingId)}/floor/${pathSegment(floorId)}/zones",
        )
        // 구역은 하나씩 읽는다 — 하나의 name:null 이 목록 전체를 실패시켜 층이 구역 0개로 열리던 것(SF-A5).
        val dtos = decodeLenientList(res.zones, ConsoleZoneDto.serializer()) { i, el, reason ->
            val id = (el as? JsonObject)?.get("zone_id")?.toString() ?: "#$i"
            onDataWarning?.invoke("zone $id dropped: unreadable ($reason)")
        }
        val seen = mutableMapOf<String, String>() // 이름 → 남긴 구역 ID
        return dtos.mapNotNull { z ->
            val raw = z.polygon
            if (!z.isActive || raw == null) return@mapNotNull null
            // ⚠️ 감사 SF-A2: 점 원소를 확인 없이 it[0]·it[1] 로 읽으면 망가진 점 하나로 IndexOutOfBounds 가
            //    새어 층 전체가 안 열렸다. 나쁜 점만 거르고(iOS S23 과 같은 기준), 3개 미만이 남으면 그 존만 버린다.
            val poly = sanitizePolygon(raw)
            if (poly.size < 3) {
                onDataWarning?.invoke("zone ${z.zoneId} (${z.name}) dropped: ${poly.size}/${raw.size} valid polygon points")
                return@mapNotNull null
            }
            if (poly.size < raw.size) {
                onDataWarning?.invoke("zone ${z.zoneId} (${z.name}): skipped ${raw.size - poly.size} bad polygon points")
            }
            val kept = seen[z.name]
            if (kept != null) {
                onDuplicateZoneName?.invoke(z.name, kept, z.zoneId)
                return@mapNotNull null
            }
            seen[z.name] = z.zoneId
            RawZone(
                id = z.zoneId,
                name = z.name,
                polygon = poly,
                inDist = z.inDist ?: ZoneDefaults.IN_DIST,
                inCount = z.inCount ?: ZoneDefaults.IN_COUNT,
                inCountInterval = z.inCountInterval ?: ZoneDefaults.IN_COUNT_INTERVAL,
                outPeriod = z.outPeriod ?: ZoneDefaults.OUT_PERIOD,
                priority = z.priority ?: ZoneDefaults.PRIORITY,
                callInout = z.callInout ?: ZoneDefaults.CALL_INOUT,
                dwellSeconds = z.dwellSeconds,
            )
        }
    }

    /**
     * 폴리곤 점 정리 — 원소가 2개 미만이거나 x·y 가 유한하지 않은 점은 버리고, 남은 점은 `[x, y]` 로 맞춘다
     * (원소가 3개 이상이면 앞의 둘만 쓴다). 이 뒤의 코드는 모든 점이 원소 2개라고 믿고 읽는다.
     */
    private fun sanitizePolygon(raw: List<List<Double>>): List<List<Double>> = raw.mapNotNull { p ->
        if (p.size < 2) return@mapNotNull null
        val x = p[0]
        val y = p[1]
        if (!x.isFinite() || !y.isFinite()) null else listOf(x, y)
    }

    /**
     * 존 폴리곤 미터 정규화 — 도면이 없으면 미터 그대로 쓴다. 도면이 있고 점 하나라도
     * `x > widthM*`[PIXEL_DETECT_MARGIN] 또는 `y > heightM*`[PIXEL_DETECT_MARGIN] 면 픽셀로 보고 변환한다(spec §4.2).
     */
    private fun normalizeZones(raw: List<RawZone>, image: PlanImage?): List<Zone> {
        if (image == null) return raw.map { z -> z.toZone(z.polygon.map { Position(x = it[0], y = it[1]) }) }
        val widthM = image.widthM
        val heightM = widthM * image.imgH / image.imgW
        val scale = image.imgW / widthM
        val imgH = image.imgH.toDouble()
        val ox = image.originX
        val oy = image.originY
        return raw.map { z ->
            val isPixel = z.polygon.any { it[0] > widthM * PIXEL_DETECT_MARGIN || it[1] > heightM * PIXEL_DETECT_MARGIN }
            val pts = z.polygon.map { p ->
                if (isPixel) {
                    Position(x = p[0] / scale + ox, y = (imgH - p[1]) / scale + oy)
                } else {
                    Position(x = p[0], y = p[1])
                }
            }
            z.toZone(pts)
        }
    }

    /** 목록 항목을 버렸다는 진단 — [onDataWarning] 으로. */
    private fun dropped(kind: String): (Int, JsonElement, String) -> Unit = { i, _, reason ->
        onDataWarning?.invoke("$kind #$i dropped: unreadable ($reason)")
    }

    // MARK: - HTTP

    /**
     * console 공통 GET — `X-SDK-Key` 만 붙인다(사양서 §4.2). [path] 는 "/" 로 시작한다. 경로 변수는 부르는 쪽이
     * [pathSegment] 로 인코딩해 넣는다(SF-A16).
     */
    private suspend inline fun <reified R> consoleGet(path: String): R {
        val req = Request.Builder()
            .url(consoleBase.trimEnd('/') + path)
            .get()
            .header("X-SDK-Key", sdkKey)
            .build()
        return performJsonRequest(spaceHttp, req)
    }

    /** 공간 서비스 공통 GET — `X-SDK-Key` + `Connection: close`. [path] 는 앞에 "/" 없이. */
    private suspend inline fun <reified R> spaceGet(path: String): R {
        val req = Request.Builder()
            .url(spaceHost.trimEnd('/') + "/" + path.trimStart('/'))
            .get()
            .header("X-SDK-Key", spaceKey)
            .header("Connection", "close")
            .build()
        return performJsonRequest(spaceHttp, req)
    }

    // MARK: - DTO — 존 원시 데이터(폴리곤 단위 미정 — normalizeZones 로 미터 정규화)

    private data class RawZone(
        val id: String,
        val name: String,
        val polygon: List<List<Double>>,
        val inDist: Double,
        val inCount: Int,
        val inCountInterval: Int,
        val outPeriod: Int,
        val priority: Int,
        val callInout: Boolean,
        val dwellSeconds: Int?,
    ) {
        /** 미터로 옮긴 [points] 로 [Zone] 을 만든다 — 존을 만드는 자리는 여기 하나다(감사 SF-C5: 예전엔 두 벌). */
        fun toZone(points: List<Position>): Zone = Zone(
            id = id,
            name = name,
            polygon = points,
            inDist = inDist,
            inCount = inCount,
            inCountInterval = inCountInterval,
            outPeriod = outPeriod,
            priority = priority,
            callInout = callInout,
            dwellSeconds = dwellSeconds,
        )
    }
}

// MARK: - 콘솔 응답 DTO(snake_case — §4.2 콘솔 표)

/** console §6.4b 응답. */
@Serializable
private data class ConsolePlanResponse(
    @SerialName("has_plan") val hasPlan: Boolean,
    @SerialName("floor_name") val floorName: String? = null,
    val plan: ConsolePlanBody? = null,
)

@Serializable
private data class ConsolePlanBody(val image: PlanImage)

/** 도면 PNG — [PlanImage.dataUrl] 의 콤마 뒤를 `java.util.Base64` 로 디코드한다. */
@Serializable
private data class PlanImage(
    @SerialName("data_url") val dataUrl: String,
    @SerialName("width_m") val widthM: Double,
    @SerialName("img_w") val imgW: Int,
    @SerialName("img_h") val imgH: Int,
    @SerialName("origin_x") val originX: Double,
    @SerialName("origin_y") val originY: Double,
) {
    fun pngData(): ByteArray? {
        val b64 = if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl
        return try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** console §6.4 zones 응답. */
@Serializable
private data class ConsoleZonesResponse(val zones: List<JsonElement>)

@Serializable
private data class ConsoleZoneDto(
    @SerialName("zone_id") val zoneId: String,
    val name: String,
    val polygon: List<List<Double>>? = null,
    @SerialName("is_active") val isActive: Boolean,
    // 판정 파라미터(§6.4 존 메타) — 구서버 호환 위해 전부 옵셔널.
    @SerialName("in_dist") val inDist: Double? = null,
    @SerialName("in_count") val inCount: Int? = null,
    @SerialName("in_count_interval") val inCountInterval: Int? = null,
    @SerialName("out_period") val outPeriod: Int? = null,
    val priority: Int? = null,
    @SerialName("call_inout") val callInout: Boolean? = null,
    @SerialName("dwell_seconds") val dwellSeconds: Int? = null,
)

/** console §6.2 buildings 응답. */
@Serializable
private data class ConsoleBuildingsResponse(val buildings: List<JsonElement>)

@Serializable
private data class ConsoleBuildingDto(
    @SerialName("building_id") val buildingId: String,
    val name: String,
    /** 서버 floor_count(TBD) — 실릴 때까지 null. */
    @SerialName("floor_count") val floorCount: Int? = null,
)

/** console §6.3 floors 응답. */
@Serializable
private data class ConsoleFloorsResponse(val floors: List<JsonElement>)

/** name·has_plan 은 콘솔 배포 시차를 고려해 옵셔널 — 없던 시절 응답에도 깨지지 않는다. */
@Serializable
private data class ConsoleFloorDto(
    @SerialName("floor_id") val floorId: String,
    val name: String? = null,
    @SerialName("has_plan") val hasPlan: Boolean? = null,
)

// MARK: - 공간 서비스 직행 응답 DTO(camelCase)

/** 도면이 있는 층에서만 디코드된다 — 없는 층은 [SpaceServiceClient]가 콘솔 단계에서 이미 null 로 끝낸다. */
@Serializable
private data class SpacePlanResponse(val plan: SpacePlanBody)

@Serializable
private data class SpacePlanBody(val image: PlanImage)

@Serializable
private data class AnchorResponse(val anchors: List<JsonElement>)

@Serializable
private data class AnchorDto(
    val uwbMac: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    /** = networkIdentifier(층별 UWB 세션). */
    val sessionId: Int? = null,
    /** "auto_done"=배치완료 / "apply_failed" 등=미배치. */
    val clusterStatus: String? = null,
) {
    /**
     * 주소 = UWB MAC 뒤 2바이트(마지막 4 hex, 콜론 구분자는 무시). 서버가 값을 안 주면
     * (옛 응답) 배치된 것으로 본다 — 모르는 것을 고장으로 쳐서 멀쩡한 로케이터를 지도에서
     * 죽은 것처럼 보이게 하지 않는다.
     */
    fun toLocator(): Locator? {
        val mac = uwbMac ?: return null
        val xx = x ?: return null
        val yy = y ?: return null
        val cleaned = mac.replace(":", "")
        if (cleaned.length < 4) return null
        val addr = cleaned.takeLast(4).toIntOrNull(16) ?: return null
        return Locator(
            address = addr and 0xFFFF,
            x = xx,
            y = yy,
            z = 0.0,
            isPlaced = clusterStatus?.let { it == "auto_done" } ?: true,
        )
    }
}
