package co.onecheck.ones1ght.android

//
//  Callbacks.kt
//  공개 콜백 계약 — Java · Kotlin 양쪽에서 자연스럽게 쓰이도록(사양서 §3.0).
//
//  · 비동기 결과는 [Callback] 하나 — 값이 없으면 `Callback<Void?>`(Java 에서는 `Callback<Void>`).
//  · 이벤트는 함수 타입 대신 `fun interface` — Kotlin 은 SAM 변환(`ZoneListener { … }`),
//    Java 는 람다(`session.setOnZoneEnter(z -> …)`)로 쓴다.
//  · 전부 메인 스레드에서 불린다 — 예외 하나: initialize 전에 deviceAvailability 를 읽어 남는 [DebugLogListener]
//    경고 한 줄은 읽은 스레드에서 불린다.
//

import co.onecheck.ones1ght.android.model.ConfigChange
import co.onecheck.ones1ght.android.model.Coordinates
import co.onecheck.ones1ght.android.model.Trigger
import co.onecheck.ones1ght.android.model.Zone
import co.onecheck.ones1ght.android.runtime.LogLevel

/**
 * 비동기 호출의 Java 판 결과 통지. 둘 중 정확히 하나가 메인 스레드에서 한 번 불린다.
 * 실패는 [SdkError]·[co.onecheck.ones1ght.android.network.ApiError] 등 예외 그대로 온다 —
 * `instanceof` 로 분기한다.
 */
public interface Callback<T> {
    public fun onSuccess(result: T)
    public fun onError(error: Throwable)
}

/** 구역 진입·이탈. */
public fun interface ZoneListener {
    public fun onZone(zone: Zone)
}

/** 구역 체류 — dwellSeconds 도달 시 1회. [seconds] 단위는 초. */
public fun interface DwellListener {
    public fun onDwell(zone: Zone, seconds: Double)
}

/** 실시간 좌표(도면 로컬 미터). */
public fun interface PositionListener {
    public fun onPosition(coordinates: Coordinates)
}

/** 존 이벤트 서버 응답의 개인화 액션 — (zoneId, triggers). */
public fun interface TriggersListener {
    public fun onTriggers(zoneId: String, triggers: List<Trigger>)
}

/** 콘솔 변경 신호. SDK 는 이 신호로 아무것도 하지 않는다 — 무엇을 다시 받을지는 앱이 정한다. */
public fun interface ConfigChangeListener {
    public fun onConfigChanged(change: ConfigChange)
}

/** SDK 내부 활동 로그 — 등급과 글자가 함께 온다. */
public fun interface DebugLogListener {
    public fun onLog(level: LogLevel, message: String)
}

/**
 * 측위 세션이 **SDK 쪽 사정으로** 닫혔다 — 엔진이 스스로 멈췄고 다시 켜지 못했다(권한·Bluetooth·라이선스처럼
 * 사람이 풀어야 하는 원인이거나, 다시 켜기를 다 써도 안 됐다). 이때 `FloorSession.isRunning` 은 이미 false 라
 * `begin()` 으로 다시 열 수 있다. 앱이 `end()` 로 끈 경우에는 오지 않는다.
 */
public fun interface SessionStoppedListener {
    public fun onStopped()
}
