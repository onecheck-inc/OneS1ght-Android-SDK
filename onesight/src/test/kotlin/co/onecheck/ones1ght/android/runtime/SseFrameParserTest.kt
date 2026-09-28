package co.onecheck.ones1ght.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SSE 프레임 파서 — 같은 프레임의 `event:`/`data:` 줄들이 여러 번의 `feedLine` 호출에
 * 걸쳐 들어와도 빈 줄에서 프레임 하나로 복원해야 한다.
 *
 * iOS 쪽은 바이트 청크가 줄 중간에서 잘리는 경우까지 파서가 스스로 버퍼링했지만, 안드로이드는
 * OkHttp `BufferedSource.readUtf8Line()` 이 그 버퍼링을 이미 해 주므로(runtime/LiveConfigStream.kt)
 * 여기서는 "줄 단위로는 완성돼 들어온다"만 가정한다 — 그래서 `testRebuildsAFrameSplitAcrossChunks`
 * 는 "줄 중간에서 잘린 청크" 대신 "여러 feedLine 호출에 걸친 줄들"로 옮겼다.
 *
 * 포팅 원본: SseFrameParserTests.swift.
 */
class SseFrameParserTest {

    @Test
    fun parsesOneCompleteFrame() {
        val p = SseFrameParser()
        assertNull(p.feedLine("event: zones.changed"))
        assertNull(p.feedLine("data: {\"seq\":3}"))

        val frame = p.feedLine("")

        assertEquals(SseFrame(event = "zones.changed", data = "{\"seq\":3}"), frame)
    }

    @Test
    fun rebuildsAFrameAcrossSeparateFeedLineCalls() {
        val p = SseFrameParser()
        assertNull(p.feedLine("event: zones.cha".plus("nged"))) // 줄 자체는 한 번에 온다고 가정
        assertNull(p.feedLine("data: {\"seq\":3}"))

        val frame = p.feedLine("")

        assertEquals("zones.changed", frame?.event)
    }

    @Test
    fun heartbeatCommentProducesNoFrame() {
        val p = SseFrameParser()

        assertNull(p.feedLine(": ping"))
        assertNull(p.feedLine(""))
    }

    @Test
    fun multiLineDataIsJoinedWithNewline() {
        val p = SseFrameParser()
        p.feedLine("event: x")
        p.feedLine("data: a")
        p.feedLine("data: b")

        assertEquals("a\nb", p.feedLine("")?.data)
    }

    @Test
    fun frameWithoutEventNameDefaultsToMessage() {
        val p = SseFrameParser()
        p.feedLine("data: {}")

        assertEquals("message", p.feedLine("")?.event)
    }

    @Test
    fun twoFramesFedInSequenceAreParsedSeparately() {
        val p = SseFrameParser()
        p.feedLine("event: a")
        p.feedLine("data: 1")
        val first = p.feedLine("")
        p.feedLine("event: b")
        p.feedLine("data: 2")
        val second = p.feedLine("")

        assertEquals(listOf("a", "b"), listOf(first?.event, second?.event))
    }

    @Test
    fun blankLineWithNoPrecedingDataProducesNoFrame() {
        val p = SseFrameParser()

        assertNull(p.feedLine(""))
    }

    @Test
    fun noSpaceAfterColonIsStillParsed() {
        // prod 실응답은 "event:hello" 처럼 콜론 뒤 공백이 없다(콘솔 SSE 포맷).
        val p = SseFrameParser()
        p.feedLine("event:hello")
        p.feedLine("data:{\"seq\":36}")

        val frame = p.feedLine("")

        assertEquals(SseFrame(event = "hello", data = "{\"seq\":36}"), frame)
    }
}
