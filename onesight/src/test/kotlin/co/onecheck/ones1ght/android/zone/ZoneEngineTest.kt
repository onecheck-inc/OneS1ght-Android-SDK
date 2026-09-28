package co.onecheck.ones1ght.android.zone

import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.model.ZoneEvent
import co.onecheck.ones1ght.android.runtime.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 구역 판정 엔진 — 1초 샘플 스로틀 · 3연속 확정(IN/OUT, 경계 지터 흡수) · 존 선택은 목록 순서상
 * 첫 존(priority 무시) · DWELL 은 IN 확정 시각부터 dwellSeconds 뒤 1회만 · apply/reset 은
 * streak·후보·활성 존을 비우고 대기 dwell 을 취소 · 판정 파라미터 무력화 WARN 은 최초 1회.
 *
 * 포팅 원본: ZoneEngine.swift(판정 루프) + UwbAreaJudge.swift(DWELL 1회 규칙·reset·
 * paramsIgnored 경고). 가짜 스케줄러([FakeDwellScheduler])로 dwell 시각을 직접 제어한다.
 */
class ZoneEngineTest {

    private val sq = listOf(Position(0.0, 0.0), Position(10.0, 0.0), Position(10.0, 10.0), Position(0.0, 10.0))
    private val a = Zone("za", "A", sq, dwellSeconds = 5)
    private val inside = Position(5.0, 5.0)
    private val outside = Position(20.0, 20.0)

    private lateinit var fake: FakeDwellScheduler

    @Before
    fun setUp() {
        fake = FakeDwellScheduler()
    }

    private fun engine(): ZoneEngine = ZoneEngine(scheduler = fake)

    @Test
    fun enterNeedsThreeConsecutiveSamples() {
        val ev = mutableListOf<ZoneEvent>()
        val e = engine().apply { onEvent = { ev += it }; apply(listOf(a)) }
        e.ingest(Position(5.0, 5.0), 0)
        e.ingest(Position(5.0, 5.0), 1000)
        assertTrue(ev.isEmpty())
        e.ingest(Position(5.0, 5.0), 2000)
        assertEquals(listOf("in-za-2"), ev.map { it.id })
    }

    // 0,100,200,…900ms 로 10번 → 판단은 1회(t=0)뿐 → 3연속이 안 돼 IN 없음.
    @Test
    fun samplesInsideOneSecondAreIgnored() {
        val ev = mutableListOf<ZoneEvent>()
        val e = engine().apply { onEvent = { ev += it }; apply(listOf(a)) }
        var t = 0L
        repeat(10) {
            e.ingest(inside, t)
            t += 100
        }
        assertTrue(ev.isEmpty())
    }

    // IN 확정 뒤 안/밖/안/밖 을 반복해도 단일 이탈 샘플로는 OUT 이 안 나온다(연속 3회 필요).
    @Test
    fun boundaryJitterDoesNotFlap() {
        val ev = mutableListOf<ZoneEvent>()
        val e = engine().apply { onEvent = { ev += it }; apply(listOf(a)) }
        e.ingest(inside, 0)
        e.ingest(inside, 1000)
        e.ingest(inside, 2000)
        assertEquals(listOf("in-za-2"), ev.map { it.id })

        e.ingest(outside, 3000)
        e.ingest(inside, 4000)
        e.ingest(outside, 5000)
        e.ingest(inside, 6000)

        assertTrue(ev.none { it is ZoneEvent.Exit })
        assertEquals("za", e.activeZoneId)
    }

    @Test
    fun exitAfterThreeOutsideSamples() {
        val ev = mutableListOf<ZoneEvent>()
        val e = engine().apply { onEvent = { ev += it }; apply(listOf(a)) }
        e.ingest(inside, 0)
        e.ingest(inside, 1000)
        e.ingest(inside, 2000)

        e.ingest(outside, 3000)
        e.ingest(outside, 4000)
        e.ingest(outside, 5000)

        assertEquals(listOf("in-za-2", "out-za-5"), ev.map { it.id })
        assertNull(e.activeZoneId)
    }

    @Test
    fun dwellFiresOnceAfterDwellSeconds() {
        val ev = mutableListOf<ZoneEvent>()
        val e = engine().apply { onEvent = { ev += it }; apply(listOf(a)) }
        e.ingest(inside, 0)
        e.ingest(inside, 1000)
        e.ingest(inside, 2000) // IN 확정, dwellSeconds=5 → 5000ms 뒤 예약

        fake.advance(5000)
        assertEquals(1, ev.count { it is ZoneEvent.Dwell })

        fake.advance(10000)
        assertEquals(1, ev.count { it is ZoneEvent.Dwell }) // 추가 발화 없음
    }

    @Test
    fun exitCancelsPendingDwell() {
        val ev = mutableListOf<ZoneEvent>()
        val e = engine().apply { onEvent = { ev += it }; apply(listOf(a)) }
        e.ingest(inside, 0)
        e.ingest(inside, 1000)
        e.ingest(inside, 2000) // IN 확정, dwell 예약됨

        e.ingest(outside, 3000)
        e.ingest(outside, 4000)
        e.ingest(outside, 5000) // OUT 확정 → 대기 dwell 취소돼야 함

        fake.advance(10000)
        assertTrue(ev.none { it is ZoneEvent.Dwell })
    }

    @Test
    fun applyResets() {
        val ev = mutableListOf<ZoneEvent>()
        val e = engine().apply { onEvent = { ev += it }; apply(listOf(a)) }
        e.ingest(inside, 0)
        e.ingest(inside, 1000)
        e.ingest(inside, 2000)
        assertEquals("za", e.activeZoneId)

        e.apply(listOf(a)) // 재적용 — 판정 상태 초기화

        assertNull(e.activeZoneId)
        e.ingest(inside, 3000)
        e.ingest(inside, 4000) // 2연속뿐 — 아직 재확정 전
        assertEquals(listOf("in-za-2"), ev.map { it.id })
    }

    @Test
    fun resetClearsActiveZone() {
        val e = engine().apply { apply(listOf(a)) }
        e.ingest(inside, 0)
        e.ingest(inside, 1000)
        e.ingest(inside, 2000)
        assertEquals("za", e.activeZoneId)

        e.reset()

        assertNull(e.activeZoneId)
    }

    // 두 존이 같은 좌표를 덮어도 목록 순서상 첫 존이 이긴다 — priority 값(z-low=99 가 z-high=1 보다
    // 낮은 우선순위)이 커도 순서를 뒤집지 않는다.
    @Test
    fun firstMatchingZoneWinsIgnoringPriority() {
        val low = Zone("z-low", "낮은우선순위", sq, priority = 99)
        val high = Zone("z-high", "높은우선순위", sq, priority = 1)
        val e = engine().apply { apply(listOf(low, high)) }

        e.ingest(inside, 0)
        e.ingest(inside, 1000)
        e.ingest(inside, 2000)

        assertEquals("z-low", e.activeZoneId)
    }

    @Test
    fun noDwellWhenDwellSecondsNullOrZero() {
        val zero = Zone("z0", "제로", sq, dwellSeconds = 0)
        val ev1 = mutableListOf<ZoneEvent>()
        val e1 = engine().apply { onEvent = { ev1 += it }; apply(listOf(zero)) }
        e1.ingest(inside, 0)
        e1.ingest(inside, 1000)
        e1.ingest(inside, 2000)
        fake.advance(60_000)
        assertTrue(ev1.none { it is ZoneEvent.Dwell })

        val none = Zone("zn", "널", sq, dwellSeconds = null)
        val ev2 = mutableListOf<ZoneEvent>()
        val e2 = engine().apply { onEvent = { ev2 += it }; apply(listOf(none)) }
        e2.ingest(inside, 0)
        e2.ingest(inside, 1000)
        e2.ingest(inside, 2000)
        fake.advance(60_000)
        assertTrue(ev2.none { it is ZoneEvent.Dwell })
    }

    // 존이 있는 첫 apply 에서 WARN 1회 — 층 전환 등으로 다시 apply 해도 반복하지 않는다
    // (iOS UwbAreaJudge 와 같은 규칙: warnedParamsIgnored 플래그는 reset 으로 풀리지 않는다).
    @Test
    fun paramsIgnoredWarnedOncePerApplyWithZones() {
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val e = engine().apply { onLog = { level, msg -> logs += level to msg } }

        e.apply(listOf(a))
        e.apply(listOf(Zone("zb", "B", sq)))

        val warns = logs.filter { it.first == LogLevel.WARN }
        assertEquals(1, warns.size)
        assertTrue(warns.first().second.contains("1"))
    }

    @Test
    fun noWarnWhenAppliedZonesEmpty() {
        val logs = mutableListOf<Pair<LogLevel, String>>()
        val e = engine().apply { onLog = { level, msg -> logs += level to msg } }

        e.apply(emptyList())

        assertTrue(logs.isEmpty())
    }
}

/** 테스트용 [DwellScheduler] — 실제 시간을 흘리지 않고 [advance] 로 시뮬레이션 시계를 민다. */
private class FakeDwellScheduler : DwellScheduler {
    private class Scheduled(val dueAtMs: Long, val action: () -> Unit) {
        var cancelled: Boolean = false
    }

    private val scheduled = mutableListOf<Scheduled>()
    private var elapsedMs = 0L

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val s = Scheduled(dueAtMs = elapsedMs + delayMs, action = action)
        scheduled += s
        return Cancellable { s.cancelled = true }
    }

    /** 시뮬레이션 시계를 [byMs] 만큼 밀고, 그 사이 기한이 된 취소되지 않은 예약을 실행한다. */
    fun advance(byMs: Long) {
        elapsedMs += byMs
        val due = scheduled.filter { !it.cancelled && it.dueAtMs <= elapsedMs }
        scheduled.removeAll(due)
        due.forEach { it.action() }
    }
}
