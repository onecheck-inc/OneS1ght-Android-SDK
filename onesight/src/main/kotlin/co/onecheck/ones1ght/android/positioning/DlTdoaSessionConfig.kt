package co.onecheck.ones1ght.android.positioning

//
//  DlTdoaSessionConfig.kt
//  DL-TDoA 레인징 세션 설정값 — 전부 이 파일 한 곳에서 만든다(spec §5 "세션 설정 격리").
//
//  ⚠️ 미결(spec §10-1): 세팅값은 앵커 하드웨어 제조사와 합의된 값이어야 한다. iOS 는
//     networkIdentifier(= sessionId) 하나로 충분했고, 안드로이드에 추가로 필요한 값(채널,
//     프리앰블, 슬롯 등)은 아직 확인 전이다. 그때까지 sessionId 만 넣고 나머지는 플랫폼
//     기본값을 쓴다 — 실기기가 생기면 여기만 고친다.
//

import android.ranging.RangingDevice
import android.ranging.RangingPreference
import android.ranging.SessionConfig
import android.ranging.raw.RawDtTagRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.uwb.DlTdoaRangingParams
import androidx.annotation.RequiresApi

internal object DlTdoaSessionConfig {

    /** DL-TDoA 태그 레인징(android.ranging DT-Tag + onDlTdoaResults)이 되는 최소 API. */
    const val MIN_API: Int = 37

    /** 측정 블록 누적 타임아웃 — 이 시간 안에 모인 측정을 한 블록으로 좌표 계산에 넘긴다. */
    const val BLOCK_TIMEOUT_MS: Long = 260L

    /** 이보다 약한 신호(dBm)는 좌표 계산에서 뺀다. */
    const val MIN_RSSI: Int = -90

    @RequiresApi(MIN_API)
    fun preference(sessionId: Int): RangingPreference =
        RangingPreference.Builder(
            RangingPreference.DEVICE_ROLE_DT_TAG,
            RawDtTagRangingConfig.Builder(
                RawRangingDevice.Builder()
                    .setRangingDevice(RangingDevice.Builder().build())
                    .setDlTdoaRangingParams(DlTdoaRangingParams.Builder(sessionId).build())
                    .build(),
            ).build(),
        )
            .setSessionConfig(SessionConfig.Builder().build())
            .build()
}
