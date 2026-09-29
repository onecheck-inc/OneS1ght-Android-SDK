package co.onecheck.ones1ght.android.positioning

//
//  UwbProviderListeners.kt
//  [UwbPositioningProvider] 의 공개 훅 — Java · Kotlin 양쪽에서 쓰도록 fun interface 로 둔다
//  (Callbacks.kt 와 같은 규칙). 전부 메인 스레드에서 불린다.
//
//  포팅 원본: UwbPositioningProvider.swift 의 onFloorDetected · onEngineError · onRawAreaEvent · onZoneEvent.
//

import co.onecheck.ones1ght.android.model.ZoneEvent

/** 측위 엔진이 층 추적을 시작했다(층 번호) / 층을 놓쳤다(`null`). 호스트가 층 자동 선택에 쓴다. */
public fun interface FloorDetectedListener {
    public fun onFloorDetected(floorId: Long?)
}

/**
 * 측위 엔진 오류 — 엔진이 준 **원본 번호와 문장** 그대로. 같은 사실은 SDK 표준 경로(E-코드)로도 남는다.
 * 번호표: 1 라이선스 미등록 · 2 이미 시작됨 · 3 Bluetooth 불가 · 4 층 앵커 정보 없음 · 5 측위 세션 오류 ·
 * 6 영역 판정 오류 · 7 위치 불가 · 8 정지 중 시작 · 9 설정 누락 · 10 라이선스 거부 · 11 서버 미도달 ·
 * 12 미지원 기기 · 13 스캔 과다.
 */
public fun interface EngineErrorListener {
    public fun onEngineError(code: Int, message: String)
}

/**
 * 엔진이 준 **원본** 영역 이벤트 — 콘솔 구역으로 옮기기 전 그대로(진단용).
 * [inOut] 은 엔진 표기(`"IN"`/`"OUT"`), [atMs] 는 epoch 밀리초.
 */
public fun interface RawAreaEventListener {
    public fun onRawAreaEvent(floorId: Long, areaName: String, inOut: String, atMs: Long)
}

/** 콘솔 구역으로 옮긴 판정(진입·이탈·체류) — 서버 전송과 무관하게 호스트 UI 가 즉시 반응할 때 쓴다. */
public fun interface ZoneEventListener {
    public fun onZoneEvent(event: ZoneEvent)
}

/**
 * provider 의 관찰 상태(phase · isRunning · isPaused · latestPosition · detectedFloorId · measurementCount · log)
 * 중 무엇이든 바뀌었다 — Java 용 변경 통지(iOS 의 ObservableObject 에 해당). Kotlin 은 각 `…Flow` 를 모은다.
 */
public fun interface ProviderChangeListener {
    public fun onChanged(provider: UwbPositioningProvider)
}
