package co.onecheck.ones1ght.android

//
//  SdkGateTest.kt
//  기기 게이트 위치 검증 — initialize 는 기기를 보지 않고, begin() 이 유일한 차단점이다.
//
//  포팅 원본: SdkGateTests.swift. iOS 는 "UWB 없는 시뮬레이터" 라는 환경에 기댔지만, JVM 에서는
//  기기 판정을 가짜(FakeDeviceCapability)로 주입해 OS 미달·칩 없음 두 갈래를 모두 돈다.
//  가짜 키 대신 verify 401 스텁을 쓴다(실제 서버로 나가지 않는다).
//

import co.onecheck.ones1ght.android.network.ApiError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SdkGateTest {

    private lateinit var h: JavaInteropHarness

    @Before fun setUp() {
        h = JavaInteropHarness.start()
        h.routes.verifyStatus = 401 // 가짜 키 — 서버가 거절한다
    }

    @After fun tearDown() {
        h.close()
    }

    private fun unsupportedChip() {
        h.capability.sdkInt = 37
        h.capability.supported = false
    }

    private fun oldOs() {
        h.capability.sdkInt = 36
        h.capability.supported = false
    }

    /**
     * initialize 는 기기 게이트를 보지 않는다. 실패 사유가 기기 게이트가 아니라 서버 쪽(InvalidKey)
     * 이면 "게이트가 initialize 에서 빠졌다" 는 증거다.
     */
    @Test fun initializeNeverThrowsDeviceGate() {
        for (setup in listOf(::unsupportedChip, ::oldOs)) {
            setup()
            try {
                h.await { OneS1ght.initialize(h.context(), "ock_gate_probe_invalid", h.baseUrl()) }
                fail("가짜 키로 initialize 가 성공함 — verify 검증 확인 필요")
            } catch (e: SdkError) {
                assertFalse("기기 게이트가 initialize 에 남아 있음", e is SdkError.DeviceNotSupported)
                assertFalse("OS 게이트가 initialize 에 남아 있음", e is SdkError.OsVersionTooLow)
            } catch (e: ApiError) {
                // 게이트 없이 곧장 서버까지 갔다는 증거 → 통과
            }
            h.await { OneS1ght.reset() }
        }
        assertEquals(2, h.routes.count("/auth/verify"))
    }

    /** 조회 API 는 정직해야 한다 — OS 를 먼저 본다(구 OS 에 칩 없음을 잘못 알리지 않는다). */
    @Test fun availabilityIsHonest() {
        oldOs()
        assertEquals(DeviceAvailability.OS_VERSION_TOO_LOW, OneS1ght.deviceAvailability)
        assertFalse(OneS1ght.isDeviceAvailable)
        assertEquals("OS 미달이면 칩을 묻지도 않는다", 0, h.capability.queries)

        h.capability.supported = true // 칩이 있다고 해도 OS 가 먼저다
        assertEquals(DeviceAvailability.OS_VERSION_TOO_LOW, OneS1ght.deviceAvailability)

        unsupportedChip()
        assertEquals(DeviceAvailability.DEVICE_NOT_SUPPORTED, OneS1ght.deviceAvailability)
        assertFalse(OneS1ght.isDeviceAvailable)

        h.capability.supported = true
        assertEquals(DeviceAvailability.AVAILABLE, OneS1ght.deviceAvailability)
        assertTrue(OneS1ght.isDeviceAvailable)

        h.capability.sdkInt = 40 // 더 새 OS 도 그대로 통과
        assertEquals(DeviceAvailability.AVAILABLE, OneS1ght.deviceAvailability)
    }

    /**
     * 새 계약의 핵심 — 실제 차단 지점은 begin() 이다. initialize 가 (가짜 키로) 실패해도
     * 세션은 이미 만들어져 floorSession() 은 열리고, begin() 에서 막힌다.
     * 사유는 기기에 달렸다 — OS 미달이 먼저, 그다음이 칩.
     */
    @Test fun beginIsTheOnlyDeviceGate() {
        for ((setup, expected) in listOf(
            ::oldOs to SdkError.OsVersionTooLow::class,
            ::unsupportedChip to SdkError.DeviceNotSupported::class,
        )) {
            setup()
            try {
                h.await { OneS1ght.initialize(h.context(), "ock_gate_probe_invalid", h.baseUrl()) }
            } catch (_: ApiError) {
                // 키는 실패해도 무방
            }
            val session = OneS1ght.floorSession()
            try {
                h.await { session.begin() }
                fail("미지원 기기에서 begin() 이 통과함")
            } catch (e: SdkError) {
                assertEquals(expected, e::class)
            }
            assertEquals("막힌 begin() 은 내장 provider 를 만들지 않는다", 0, h.builtInCreated)
            h.await { OneS1ght.reset() }
        }
    }

    /**
     * 미지원 기기에서 앱이 멈추지 않아야 한다. 측위만 못 할 뿐 어느 호출도 크래시나 무한 대기로
     * 가지 않는다.
     */
    @Test fun unsupportedDeviceDoesNotBreakTheApp() {
        unsupportedChip()

        // 초기화 전에도 답해야 하는 조회들 — throw 도, 멈춤도 없어야 한다.
        assertEquals(DeviceAvailability.DEVICE_NOT_SUPPORTED, OneS1ght.deviceAvailability)
        assertFalse(OneS1ght.isDeviceAvailable)
        OneS1ght.setLanguage("ko")
        OneS1ght.setLanguage(null)
        OneS1ght.identify(null)
        OneS1ght.empty()
        h.await { OneS1ght.send() }

        // 초기화 이후에도 마찬가지 — 막히는 것은 begin() 하나뿐이다.
        try {
            h.await { OneS1ght.initialize(h.context(), "ock_gate_probe_invalid", h.baseUrl()) }
        } catch (_: ApiError) {
        }
        OneS1ght.floorSession()
        h.await { OneS1ght.reset() }
    }
}
