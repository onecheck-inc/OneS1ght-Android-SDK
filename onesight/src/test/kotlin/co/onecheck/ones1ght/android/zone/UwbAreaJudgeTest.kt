package co.onecheck.ones1ght.android.zone

import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.runtime.LogLevel
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import co.onecheck.ones1ght.android.runtime.SdkLocalized
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 엔진 영역 이벤트 → 콘솔 존 매핑.
 *
 * 여기서 지키는 것은 **연결고리 하나**다. 엔진의 onAreaEvent 에는 존 ID 가 없고 이름만 온다.
 * 그래서 콘솔 존과 잇는 유일한 끈이 이름이고, 그 끈이 끊기면 그 영역의 시책이 통째로 안 돈다.
 * 끊긴 것을 조용히 넘기면 현장에서는 "쿠폰이 안 나온다"로만 보인다 — 그래서 알린다.
 *
 * 포팅 원본: UwbAreaJudgeTests.swift. 체류 타이머는 [ManualScheduler] 로 시간을 직접 민다.
 */
class UwbAreaJudgeTest {

    /** 시간을 테스트가 직접 미는 스케줄러 — 예약·취소를 그대로 드러낸다. */
    private class ManualScheduler : DwellScheduler {
        private class Task(val dueAt: Long, val action: () -> Unit) {
            var cancelled = false
        }

        private val tasks = mutableListOf<Task>()
        var now = 0L
            private set

        val pending: Int get() = tasks.count { !it.cancelled }

        override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
            val t = Task(now + delayMs, action)
            tasks += t
            return Cancellable { t.cancelled = true }
        }

        fun advanceBy(ms: Long) {
            now += ms
            val due = tasks.filter { !it.cancelled && it.dueAt <= now }
            tasks.removeAll(due)
            due.forEach { it.action() }
        }
    }

    private fun zone(id: String, name: String, dwell: Int? = null): Zone =
        Zone(id, name, listOf(Position(0.0, 0.0), Position(1.0, 0.0), Position(1.0, 1.0)), dwellSeconds = dwell)

    private lateinit var scheduler: ManualScheduler
    private lateinit var judge: UwbAreaJudge
    private val events = mutableListOf<ZoneEvent>()
    private val reports = mutableListOf<Pair<SdkErrorCode, String>>()
    private val logs = mutableListOf<Pair<LogLevel, String>>()

    @Before fun setUp() {
        SdkLocalized.language = "ko"
        scheduler = ManualScheduler()
        judge = UwbAreaJudge(scheduler)
        judge.onEvent = { events += it }
        judge.onReport = { code, ctx -> reports += code to ctx }
        judge.onLog = { level, msg -> logs += level to msg }
    }

    @After fun tearDown() {
        SdkLocalized.language = null
    }

    private val dwells get() = events.filterIsInstance<ZoneEvent.Dwell>()

    // MARK: - 이름 매핑

    /** 이름이 맞으면 콘솔 zone_id 로 옮겨진다 — 이게 서버로 나가는 값이다. */
    @Test fun areaNameMapsToConsoleZoneId() {
        judge.apply(listOf(zone("zn_7", "정육 코너")))

        judge.handleAreaEvent("IN", "정육 코너", 1_000)

        assertEquals(1, events.size)
        val e = events[0] as ZoneEvent.Enter
        assertEquals("서버로 나가는 것은 이름이 아니라 콘솔 zone_id 다", "zn_7", e.zone.id)
        assertEquals(1_000, e.at)
    }

    /** 대응하는 존이 없으면 이벤트를 만들지 않고, 코드로 알린다. */
    @Test fun unmappedAreaIsReportedAndEmitsNothing() {
        judge.apply(listOf(zone("zn_7", "정육 코너")))

        judge.handleAreaEvent("IN", "수산 코너", 0) // 콘솔에 없는 이름

        assertTrue("매핑 안 된 영역으로 이벤트를 만들면 안 된다", events.isEmpty())
        assertEquals(SdkErrorCode.ZONE_MAPPING_FAILED, reports.first().first)
        assertTrue("어느 이름이 안 맞았는지 남아야 대조할 수 있다: $reports", reports.first().second.contains("수산 코너"))
        assertTrue(logs.any { it.first == LogLevel.WARN && it.second.contains("수산 코너") })
    }

    /** 같은 이름이 반복돼도 한 번만 알린다 — IN/OUT 이 오갈 때마다 쌓이면 로그가 덮인다. */
    @Test fun unmappedAreaWarnsOnlyOncePerName() {
        judge.apply(listOf(zone("zn_7", "정육 코너")))

        judge.handleAreaEvent("IN", "수산 코너", 0)
        judge.handleAreaEvent("OUT", "수산 코너", 0)
        judge.handleAreaEvent("IN", "수산 코너", 0)

        assertEquals(reports.toString(), 1, reports.count { it.first == SdkErrorCode.ZONE_MAPPING_FAILED })
    }

    /** 다른 이름은 따로 센다 — 하나 알렸다고 나머지를 삼키면 안 된다. */
    @Test fun differentUnmappedNamesEachWarn() {
        judge.apply(listOf(zone("zn_7", "정육 코너")))

        judge.handleAreaEvent("IN", "수산 코너", 0)
        judge.handleAreaEvent("IN", "청과 코너", 0)

        assertEquals(reports.toString(), 2, reports.count { it.first == SdkErrorCode.ZONE_MAPPING_FAILED })
    }

    /** 존을 다시 물리면(층 전환) 같은 이름도 다시 알린다 — 새 층에서도 끊겼다는 사실은 새 정보다. */
    @Test fun reapplyingZonesResetsTheOncePerNameGuard() {
        judge.apply(listOf(zone("zn_7", "정육 코너")))
        judge.handleAreaEvent("IN", "수산 코너", 0)

        judge.apply(listOf(zone("zn_8", "청과 코너")))
        judge.handleAreaEvent("IN", "수산 코너", 0)

        assertEquals(2, reports.count { it.first == SdkErrorCode.ZONE_MAPPING_FAILED })
    }

    /** 콘솔에서 같은 이름을 두 번 쓴 경우 — 뒤엣것은 `#2` 로 유일화된다(옛 존 엔진과 같은 규칙). */
    @Test fun duplicateConsoleNamesAreDisambiguated() {
        judge.apply(listOf(zone("zn_a", "코너"), zone("zn_b", "코너"), zone("zn_c", "코너")))

        judge.handleAreaEvent("IN", "코너", 0)
        judge.handleAreaEvent("IN", "코너#2", 0)
        judge.handleAreaEvent("IN", "코너#3", 0)

        assertEquals(listOf("zn_a", "zn_b", "zn_c"), events.map { (it as ZoneEvent.Enter).zone.id })
    }

    // MARK: - IN / OUT

    /** 알 수 없는 inOut 값은 이벤트로 만들지 않는다. 엔진이 규약을 바꿔도 조용히 새 뜻을 지어내면 안 된다. */
    @Test fun unknownInOutValueIsRejected() {
        judge.apply(listOf(zone("zn_7", "정육 코너")))
        logs.clear()

        judge.handleAreaEvent("ENTER", "정육 코너", 0) // 규약은 "IN"/"OUT"

        assertTrue(events.toString(), events.isEmpty())
        assertFalse("무시했으면 그 사실은 남겨야 한다", logs.isEmpty())
    }

    @Test fun outEmitsExit() {
        judge.apply(listOf(zone("zn_7", "정육 코너")))

        judge.handleAreaEvent("IN", "정육 코너", 0)
        judge.handleAreaEvent("OUT", "정육 코너", 5_000)

        assertEquals(2, events.size)
        val e = events[1] as ZoneEvent.Exit
        assertEquals("zn_7", e.zone.id)
        assertEquals(5_000, e.at)
        assertEquals(null, judge.activeZoneId)
    }

    // MARK: - DWELL 파생

    /** dwellSeconds 에 도달하면 한 번만 발화한다 — 반복 발화는 트리거 도배가 된다. */
    @Test fun dwellFiresOnceAtConfiguredSeconds() {
        judge.apply(listOf(zone("zn_7", "정육 코너", dwell = 5)))

        judge.handleAreaEvent("IN", "정육 코너", 1_000)
        scheduler.advanceBy(4_999)
        assertTrue(dwells.isEmpty())
        scheduler.advanceBy(1)
        scheduler.advanceBy(60_000)

        assertEquals(1, dwells.size)
        assertEquals(5.0, dwells[0].seconds, 0.0)
        assertEquals("zn_7", dwells[0].zone.id)
        assertEquals("발생 시각은 진입 + 체류시간", 6_000, dwells[0].at)
    }

    /** dwellSeconds 가 없는 존은 체류 이벤트를 만들지 않는다. */
    @Test fun noDwellWhenNotConfigured() {
        judge.apply(listOf(zone("zn_7", "정육 코너"))) // dwell 미설정

        judge.handleAreaEvent("IN", "정육 코너", 0)
        scheduler.advanceBy(600_000)

        assertTrue(events.toString(), dwells.isEmpty())
        assertEquals(0, scheduler.pending)
    }

    /** 존을 갈아끼우면 진행 중이던 체류 타이머는 죽는다 — 사라진 존의 DWELL 이 없는 구역의 시책을 발화시킨다. */
    @Test fun applyZonesCancelsPendingDwell() {
        judge.apply(listOf(zone("zn_7", "정육 코너", dwell = 1)))
        judge.handleAreaEvent("IN", "정육 코너", 0)

        judge.apply(emptyList()) // 층 전환 · 존 전부 삭제
        scheduler.advanceBy(5_000)

        assertTrue("존이 사라졌는데 체류가 발화했다: $events", dwells.isEmpty())
    }

    /** OUT 이 오면 체류 타이머도 함께 끝난다. */
    @Test fun exitCancelsPendingDwell() {
        judge.apply(listOf(zone("zn_7", "정육 코너", dwell = 1)))

        judge.handleAreaEvent("IN", "정육 코너", 0)
        judge.handleAreaEvent("OUT", "정육 코너", 0)
        scheduler.advanceBy(5_000)

        assertTrue(events.toString(), dwells.isEmpty())
    }

    /** reset(측위 시작·재개) 도 체류 타이머를 끝낸다 — 존 목록은 그대로다. */
    @Test fun resetCancelsDwellButKeepsZones() {
        judge.apply(listOf(zone("zn_7", "정육 코너", dwell = 1)))
        judge.handleAreaEvent("IN", "정육 코너", 0)

        judge.reset()
        scheduler.advanceBy(5_000)

        assertTrue(dwells.isEmpty())
        assertEquals(1, judge.zones.size)
        judge.handleAreaEvent("IN", "정육 코너", 0)
        assertEquals(2, events.filterIsInstance<ZoneEvent.Enter>().size)
    }

    /** 다른 존에 들어가면 앞 존의 체류는 취소된다. */
    @Test fun enteringAnotherZoneCancelsThePreviousDwell() {
        judge.apply(listOf(zone("zn_a", "A", dwell = 2), zone("zn_b", "B", dwell = 10)))

        judge.handleAreaEvent("IN", "A", 0)
        scheduler.advanceBy(1_000)
        judge.handleAreaEvent("IN", "B", 1_000)
        scheduler.advanceBy(5_000)

        assertTrue("A 의 체류가 B 안에서 발화했다: $events", dwells.isEmpty())
        scheduler.advanceBy(5_000)
        assertEquals(listOf("zn_b"), dwells.map { it.zone.id })
    }

    // MARK: - 판정 파라미터 무력화 안내

    /** 존이 있으면 "콘솔 판정 파라미터가 이 경로에서 안 쓰인다" 를 한 번 말한다. */
    @Test fun judgingParamsIgnoredIsAnnouncedOnce() {
        judge.apply(listOf(zone("zn_7", "정육 코너")))
        judge.apply(listOf(zone("zn_8", "수산 코너"))) // 층 전환 — 다시 말하지 않는다

        assertEquals("층마다 반복하면 로그가 덮인다: $logs", 1, logs.count { it.first == LogLevel.WARN })
    }

    /** 존이 하나도 없으면 말하지 않는다 — 할 말이 없는 상황이다. */
    @Test fun nothingAnnouncedWhenThereAreNoZones() {
        judge.apply(emptyList())

        assertTrue(logs.toString(), logs.isEmpty())
    }
}
