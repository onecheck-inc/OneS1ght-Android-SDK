package co.onecheck.ones1ght.android.runtime

//
//  SseFrameParser.kt
//  SSE 프레임 파서 — 한 줄씩 받아 event/data 프레임을 꺼낸다.
//
//  네트워킹과 분리한 순수 상태기계다. 안드로이드 쪽은 OkHttp `BufferedSource.readUtf8Line()`
//  으로 미리 줄 단위로 끊어 먹이므로(runtime/LiveConfigStream.kt), 이 파서는 청크가 줄
//  중간에서 잘리는 경우를 걱정하지 않는다 — 그 대신 **빈 줄이 프레임 경계**라는 것만 안다.
//  ⚠️ 빈 줄도 반드시 이 파서에 넘겨야 한다 — 그래야 마지막 프레임이 완성된다(프로덕션에서
//  실제로 겪은 문제. `LiveConfigStream.kt` 상단 주석 참고).
//
//  포팅 원본: SseFrameParser.swift.
//

/** 완성된 프레임 1건. */
internal data class SseFrame(
    /** `event:` 가 없으면 SSE 표준 기본값 `message`. */
    val event: String,
    /** `data:` 줄들을 개행으로 이어 붙인 것. */
    val data: String,
)

internal class SseFrameParser {

    private var event: String? = null
    private val dataLines = mutableListOf<String>()

    /**
     * 줄 하나를 먹인다(줄 끝 개행 문자는 빠진 채로 — `readUtf8Line()` 규약과 같다).
     *
     * 빈 줄이 프레임 경계다 — 그때까지 모은 것으로 프레임을 완성해 돌려주고(data 가
     * 하나도 없었으면 null), 상태를 비운다. 빈 줄이 아니면 항상 null(아직 프레임이
     * 안 끝났다). `:` 로 시작하는 줄은 주석(하트비트)이라 버린다.
     */
    fun feedLine(line: String): SseFrame? {
        if (line.isEmpty()) return complete()
        if (line.startsWith(":")) return null
        when {
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
        }
        return null
    }

    private fun complete(): SseFrame? {
        val frame = if (dataLines.isEmpty()) null else SseFrame(event ?: "message", dataLines.joinToString("\n"))
        event = null
        dataLines.clear()
        return frame
    }
}
