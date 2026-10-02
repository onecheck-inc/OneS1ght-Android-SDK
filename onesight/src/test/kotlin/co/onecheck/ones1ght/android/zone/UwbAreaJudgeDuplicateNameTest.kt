package co.onecheck.ones1ght.android.zone

import co.onecheck.ones1ght.android.model.Position
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.runtime.SdkErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 감사 SP-B10 — 같은 이름 구역의 두 번째는 엔진이 이름으로만 영역을 알려 주므로 영영 매핑되지 않는다. 조용히 두지
 * 않고 한 번 알린다(E3009). 같은 목록을 다시 받아도(폴링) 반복하지 않는다. 활성 구역 하나는 iOS 와 같게 둔다.
 */
class UwbAreaJudgeDuplicateNameTest {

    private fun zone(id: String, name: String) =
        Zone(id, name, listOf(Position(0.0, 0.0), Position(1.0, 0.0), Position(1.0, 1.0)))

    @Test fun duplicateNameIsReportedOncePerList() {
        val judge = UwbAreaJudge(DwellScheduler { _, _ -> Cancellable { } })
        val reports = mutableListOf<Pair<SdkErrorCode, String>>()
        judge.onReport = { code, ctx -> reports += code to ctx }
        val zones = listOf(zone("z1", "정육"), zone("z2", "정육"), zone("z3", "과일"))
        judge.apply(zones)
        judge.apply(zones.toList()) // 같은 목록 — 폴링
        assertEquals(reports.toString(), 1, reports.size)
        assertEquals(SdkErrorCode.ZONE_MAPPING_FAILED, reports[0].first)
        assertTrue(reports[0].second, reports[0].second.contains("z2"))
    }
}
