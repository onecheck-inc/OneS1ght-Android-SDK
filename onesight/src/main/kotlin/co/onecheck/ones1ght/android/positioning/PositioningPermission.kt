package co.onecheck.ones1ght.android.positioning

//
//  PositioningPermission.kt
//  측위 권한 확인·요청 — RANGING + ACCESS_FINE_LOCATION(+ 함께 묻는 ACCESS_COARSE_LOCATION)을 한 번에.
//
//  iOS 는 NearbyInteraction 에 상태 조회 API 가 없어 세션을 띄워 보는 프로브였지만,
//  안드로이드는 런타임 권한이라 조회와 요청이 나뉜다:
//    · 이미 둘 다 허용됐으면 팝업 없이 AUTHORIZED
//    · 아니면 ActivityResultRegistry 로 한 번에 요청 — onCreate 이후 아무 때나 불러도 된다
//      (registerForActivityResult 와 달리 등록 시점 제약이 없다).
//    · 30초 안에 답이 없으면 보수적으로 DENIED — 팝업을 방치하면 앱이 영영 기다리기 때문이다.
//
//  포팅 원본: PositioningPermission.swift (결과 3종·30초 타임아웃).
//

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import co.onecheck.ones1ght.android.PermissionStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

internal object PositioningPermission {

    /** UWB 레인징 권한 (API 36+). 문자열로 둔다 — 상수가 없는 구 컴파일 SDK 에서도 같은 값이다. */
    const val RANGING: String = "android.permission.RANGING"

    const val FINE_LOCATION: String = "android.permission.ACCESS_FINE_LOCATION"

    /**
     * Android 12+ 는 FINE 을 COARSE 없이 요청하면 무시한다(lint 오류) — 그래서 함께 요청한다.
     * 측위에 필요한 것은 정밀 위치라 판정([decide])에는 넣지 않는다.
     */
    const val COARSE_LOCATION: String = "android.permission.ACCESS_COARSE_LOCATION"

    /** 측위에 반드시 있어야 하는 권한 — 둘 다 허용이어야 AUTHORIZED. */
    val REQUIRED: Array<String> = arrayOf(RANGING, FINE_LOCATION)

    /** 한 번에 요청하는 권한 — 순서는 팝업 순서다. */
    val PERMISSIONS: Array<String> = arrayOf(RANGING, FINE_LOCATION, COARSE_LOCATION)

    /** ActivityResultRegistry 키 접두어 — 실제 키는 호출마다 뒤에 번호를 붙인다. */
    const val REGISTRY_KEY: String = "onesight.permissions"

    private val registrySeq = AtomicInteger(0)

    /**
     * 호출마다 다른 레지스트리 키. 같은 키로 다시 등록하면 앞 등록을 대신해, 동시에 두 번 부르면
     * 앞 호출이 답을 영영 못 받고(30초 뒤 DENIED) 끝나면서 뒤 호출의 등록까지 풀어 버린다.
     */
    fun nextRegistryKey(): String = "$REGISTRY_KEY.${registrySeq.incrementAndGet()}"

    /** 사용자 응답을 기다리는 상한. */
    const val TIMEOUT_MS: Long = 30_000L

    /**
     * 요청 결과 → 상태. 필수 권한([REQUIRED])이 **전부** 허용이어야 AUTHORIZED 다 — 대략 위치만
     * 허용(정밀 거부)은 DENIED. 빈 결과(액티비티 재생성 등으로 요청이 취소됨)나 일부 누락은
     * 허용으로 보지 않는다.
     */
    fun decide(result: Map<String, Boolean>): PermissionStatus =
        if (REQUIRED.all { result[it] == true }) PermissionStatus.AUTHORIZED else PermissionStatus.DENIED

    /** 메인 스레드(코어 디스패처)에서 부른다 — ActivityResultRegistry 는 메인 전용이다. */
    suspend fun request(activity: ComponentActivity): PermissionStatus {
        val alreadyGranted = REQUIRED.all {
            activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (alreadyGranted) return PermissionStatus.AUTHORIZED

        var launcher: ActivityResultLauncher<Array<String>>? = null
        try {
            return withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val registered = activity.activityResultRegistry.register(
                        nextRegistryKey(),
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { result ->
                        if (cont.isActive) cont.resume(decide(result))
                    }
                    launcher = registered
                    registered.launch(PERMISSIONS)
                }
            } ?: PermissionStatus.DENIED // 응답이 없으면 "확인 못 함" — 보수적으로 거부 취급
        } finally {
            launcher?.unregister()
        }
    }
}
