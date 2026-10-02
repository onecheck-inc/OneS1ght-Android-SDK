package co.onecheck.ones1ght.android.zone

//
//  UwbAreaJudge.kt
//  측위 엔진의 영역 이벤트(onAreaEvent)를 SDK 의 ZoneEvent 로 옮기는 얇은 판정기.
//  옛 존 엔진(좌표 → IN/OUT)의 후신이다 — 판정 자체를 엔진이 하므로 좌표를 먹지 않는다.
//
//  종전(자체 존 엔진)과 같은 점:
//   · IN/OUT 만 온다 → DWELL 은 dwellSeconds 가 설정된 존에서만, 도달 시점에 1회 파생.
//     (반복 발화 금지 — 서버 시책 매칭에 중복 억제가 없어 반복 전송은 트리거 도배가 된다)
//   · 이벤트에 존 ID 가 없다 → **이름을 매핑 키로 쓴다** (중복 시 #n 접미사로 유일화).
//
//  달라진 점 — 읽는 사람이 반드시 알아야 한다:
//   · 콘솔 존의 판정 파라미터(in_dist·in_count·out_period·priority)가 **더 이상 쓰이지 않는다.**
//     엔진은 자기 서버의 지오펜스로 판정한다. 콘솔에서 그 값을 바꿔도 판정이 변하지 않는다.
//   · 그래서 콘솔 존은 이제 **지도 표시·zone_id 매핑·서버 전송**을 위해서만 쓰인다.
//   · 엔진 영역 이름과 콘솔 존 이름이 어긋나면 이벤트가 버려진다 — 조용히 넘기지 않고
//     경고 + E3009 로 남긴다. 이름이 유일한 연결고리라 여기가 끊기면 시책이 안 돈다.
//
//  ⚠️ 스레드 한정: 모든 호출과 [DwellScheduler] 콜백은 같은 디스패처(코어) 한 곳에서.
//
//  포팅 원본: UwbAreaJudge.swift (iOS 0.1.23).
//

import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized

/** 엔진 영역 이벤트 → ZoneEvent. 좌표를 받지 않는다. */
internal class UwbAreaJudge(private val scheduler: DwellScheduler) {

    /** 지금 물려 있는 콘솔 존 (지도·매핑용). */
    var zones: List<Zone> = emptyList()
        private set

    /** 이벤트 확정 훅 — provider 가 받아 delegate·앱으로 흘린다. */
    var onEvent: ((ZoneEvent) -> Unit)? = null

    /** 진단 로그 훅. */
    var onLog: ((LogLevel, String) -> Unit)? = null

    /** 진단 코드 훅 — 화면 로그로 끝내지 않고 서버(E-코드)까지 올릴 것만 여기로 보낸다. */
    var onReport: ((SdkErrorCode, String) -> Unit)? = null

    /** 판정 파라미터 무력화 경고는 한 번만 — 존을 갈아끼울 때마다 반복하면 로그가 덮인다. */
    private var warnedParamsIgnored = false

    private var nameToZone: Map<String, Zone> = emptyMap()

    /** 지금 안에 있다고 믿는 존 id — DWELL 발화 조건. */
    var activeZoneId: String? = null
        private set

    private var dwell: Cancellable? = null

    /** 매핑 실패는 이름당 1회만 경고한다 — IN/OUT 이 반복되면 로그가 덮인다. */
    private val warnedNames = mutableSetOf<String>()

    // MARK: - 존 주입

    /**
     * 콘솔 존 교체 (층 전환·폴링).
     *
     * - 내용이 같은 목록이면 아무것도 안 한다 — 판정 상태·체류 타이머·경고 이력 그대로.
     * - 지금 안에 있는 존이 새 목록에 **정의 그대로**(id·이름·도형·체류 초 등 전부 같음) 남아 있으면
     *   그 존의 판정 상태와 체류 타이머를 유지한다.
     * - 그 존이 사라졌거나 정의가 바뀌었으면 판정 상태를 버린다 — 사라진 존의 IN 상태가 남으면
     *   이탈 이벤트가 영영 안 나오고, 바뀐 존을 옛 기준으로 발화하면 틀린 시책이 나간다.
     *
     * ⚠️ 감사 SP-B2: 예전엔 무조건 버렸다. 앱이 구역을 주기적으로 다시 받으면(온보딩 앱 5초) 그때마다
     *    체류 타이머가 지워져 DWELL 이 사실상 안 떴다 — 엔진은 IN 을 다시 주지 않아 복구도 없다.
     */
    fun apply(zones: List<Zone>) {
        if (zones == this.zones) return
        val active = activeZoneId?.let { id -> this.zones.firstOrNull { it.id == id } }
        val keepActive = active != null && zones.any { it == active }
        this.zones = zones
        if (!keepActive) reset()
        warnedNames.clear()

        // 엔진 영역 이벤트에 ID 가 없다 → name 이 키. 중복이면 #2, #3… 로 유일화
        // (옛 존 엔진과 같은 규칙 — 콘솔에서 같은 이름을 두 번 쓴 경우)
        val map = LinkedHashMap<String, Zone>()
        for (z in zones) {
            var key = z.name
            var n = 2
            while (map.containsKey(key)) {
                key = "${z.name}#$n"
                n += 1
            }
            if (key != z.name) {
                // 엔진은 영역을 **이름으로만** 알려 준다 — 같은 이름의 두 번째 구역(#2 키)은 영영 매핑되지 않는다.
                // 조용히 두지 않고 알린다(감사 SP-B10). 목록이 바뀔 때만 여기 오므로 폴링마다 반복하지 않는다.
                val first = map[z.name]?.id ?: "?"
                onLog?.invoke(LogLevel.WARN, "zone ${z.id} (${z.name}) can never be mapped: duplicate name of $first")
                onReport?.invoke(SdkErrorCode.ZONE_MAPPING_FAILED, "duplicate zone name=${z.name} kept=$first unmapped=${z.id}")
            }
            map[key] = z
        }
        nameToZone = map
        warnIfJudgingParamsIgnored()
    }

    /**
     * 콘솔에서 판정 파라미터를 손댔는데 그 값이 아무 데도 안 쓰이는 상황을 드러낸다.
     *
     * ⚠️ "값을 손댔는지"로 거르지 않는다. 서버에서 오는 존은 미설정이어도 기본값이 채워져
     *    내려와 설정한 것과 구분되지 않는다 — 그래서 값은 보지 않고, 존이 있을 때
     *    **경로의 성질**을 한 번만 말한다.
     */
    private fun warnIfJudgingParamsIgnored() {
        if (warnedParamsIgnored || zones.isEmpty()) return
        warnedParamsIgnored = true
        onLog?.invoke(LogLevel.WARN, SdkLocalized.t("uwb.paramsIgnored", zones.size))
    }

    // MARK: - 엔진 영역 이벤트

    /**
     * 엔진 `onAreaEvent(floorId, areaName, inOut)` 를 받아 ZoneEvent 로 옮긴다.
     *
     * @param inOut 엔진이 주는 `"IN"` / `"OUT"` 문자열 (그 밖의 값은 무시)
     * @param atMs 발생 시각
     */
    fun handleAreaEvent(inOut: String, areaName: String, atMs: Long) {
        val zone = nameToZone[areaName]
        if (zone == null) {
            // 이름이 유일한 연결고리라 여기가 끊기면 그 영역의 시책이 통째로 안 돈다.
            // 화면 로그로만 남기면 현장에서만 보이고 관리자는 영영 모른다 → E-코드로도 올린다.
            if (warnedNames.add(areaName)) {
                onLog?.invoke(LogLevel.WARN, SdkLocalized.t("uwb.areaUnmapped", areaName, zones.size))
                onReport?.invoke(SdkErrorCode.ZONE_MAPPING_FAILED, "area=$areaName consoleZones=${zones.size}")
            }
            return
        }
        when (inOut) {
            "IN" -> {
                activeZoneId = zone.id
                onEvent?.invoke(ZoneEvent.Enter(zone, atMs))
                startDwell(zone, atMs)
            }
            "OUT" -> {
                if (activeZoneId == zone.id) reset()
                onEvent?.invoke(ZoneEvent.Exit(zone, atMs))
            }
            else -> onLog?.invoke(LogLevel.WARN, SdkLocalized.t("uwb.areaUnknown", inOut, areaName))
        }
    }

    /**
     * 지금 안에 있는 구역에서 나간 것으로 친다 — EXIT 를 한 번 내고 판정 상태를 비운다. 안에 있는 구역이 없으면
     * 아무것도 안 한다. 측위를 잠시 멈추기 전(배경 전환)에 쓴다 — 감사 SP-B15.
     */
    fun exitActive(atMs: Long) {
        val zone = activeZoneId?.let { id -> zones.firstOrNull { it.id == id } } ?: return reset()
        reset()
        onEvent?.invoke(ZoneEvent.Exit(zone, atMs))
    }

    /** 판정 상태 초기화 (측위 시작·층 전환·재개). 존 목록은 유지한다. */
    fun reset() {
        dwell?.cancel()
        dwell = null
        activeZoneId = null
    }

    // MARK: - DWELL 파생

    /**
     * 체류시간(dwellSeconds) 도달 시 1회 발화 (콘솔 "체류 트리거" 의미 그대로).
     * dwellSeconds 미설정 존(enter/exit 트리거)은 DWELL 이벤트를 만들지 않는다.
     */
    private fun startDwell(zone: Zone, enteredAtMs: Long) {
        dwell?.cancel()
        dwell = null
        val seconds = zone.dwellSeconds
        if (seconds == null || seconds <= 0) return
        val delayMs = seconds.toLong() * 1_000
        dwell = scheduler.schedule(delayMs) {
            if (activeZoneId == zone.id) {
                onEvent?.invoke(ZoneEvent.Dwell(zone, seconds.toDouble(), enteredAtMs + delayMs))
            }
        }
    }
}
