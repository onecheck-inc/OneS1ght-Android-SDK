package co.onecheck.ones1ght.android;

import co.onecheck.ones1ght.android.model.Building;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

/**
 * 공개 API 가 Java 에서 자연스럽게 불리는지 — 컴파일되는 것 자체가 절반의 검증이다(사양서 §3.0).
 * initialize → identify → buildings → floorSession → 리스너 → begin → end 를 Java 로 돈다.
 */
public class JavaInteropTest {
    @Test public void fullFlowCompilesAndRunsFromJava() throws Exception {
        JavaInteropHarness h = JavaInteropHarness.start();   // MockWebServer + 코어 디스패처 대체 + Mock provider
        try {
            final Object[] got = new Object[1];
            OneS1ght.initialize(h.context(), "ock_test", h.baseUrl(), new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "ok"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("ok", got[0]);
            assertTrue(OneS1ght.isInitialized());
            OneS1ght.identify("p1");
            OneS1ght.buildings(new Callback<List<Building>>() {
                @Override public void onSuccess(List<Building> r) { got[0] = r; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertTrue(got[0] instanceof List);
            FloorSession s = OneS1ght.floorSession();
            s.setOnZoneEnter(zone -> {}); s.setOnPosition(c -> {}); s.setOnZoneDwell((zone, sec) -> {});
            OneS1ght.setOnDebugLog((level, msg) -> {});
            s.begin(h.mockProvider(), new Callback<Void>() { @Override public void onSuccess(Void r) {} @Override public void onError(Throwable e) { fail(e.toString()); } });
            h.drain(); assertTrue(s.isRunning());
            s.end(new Callback<Void>() { @Override public void onSuccess(Void r) {} @Override public void onError(Throwable e) {} });
            h.drain(); assertFalse(s.isRunning());
            try { throw new SdkError.NotInitialized(); } catch (SdkError e) { assertEquals("E1001", e.getCode().getCode()); }
        } finally {
            h.close();
        }
    }

    /** 나머지 정적 멤버·오버로드도 Java 에서 그 모양 그대로 불린다. */
    @Test public void staticSurfaceIsReachableFromJava() throws Exception {
        JavaInteropHarness h = JavaInteropHarness.start();
        try {
            assertEquals("0.0.1", OneS1ght.SDK_VERSION);
            DeviceAvailability a = OneS1ght.getDeviceAvailability();
            assertEquals(DeviceAvailability.AVAILABLE, a);
            assertTrue(OneS1ght.isDeviceAvailable());
            assertNull(OneS1ght.getGoogleMapKey());
            OneS1ght.setLanguage("en");
            OneS1ght.setLanguage(null);
            OneS1ght.empty();

            final Object[] got = new Object[1];
            // baseUrl 없는 판 — 기본 주소로 나가므로 결과는 보지 않고 모양만 확인한다(초기화 전 조회로 대체).
            OneS1ght.floors("b1", new Callback<List<co.onecheck.ones1ght.android.model.Floor>>() {
                @Override public void onSuccess(List<co.onecheck.ones1ght.android.model.Floor> r) { got[0] = r; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain();
            assertTrue(got[0] instanceof SdkError.NotInitialized);
            assertTrue(got[0] instanceof SdkError);

            OneS1ght.initialize(h.context(), "ock_java2", h.baseUrl(), new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "ok"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("ok", got[0]);
            assertEquals("AIza_facade", OneS1ght.getGoogleMapKey());

            OneS1ght.setFloorMap(null, new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "floor-cleared"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("floor-cleared", got[0]);
            OneS1ght.setFloorMap(null, "b1", new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "floor-cleared-2"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("floor-cleared-2", got[0]);

            OneS1ght.refreshZones(new Callback<List<co.onecheck.ones1ght.android.model.Zone>>() {
                @Override public void onSuccess(List<co.onecheck.ones1ght.android.model.Zone> r) { got[0] = r; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertTrue(got[0] instanceof List);

            OneS1ght.send(new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "sent"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("sent", got[0]);

            FloorSession s = OneS1ght.floorSession();
            s.setOnZoneExit(zone -> {});
            s.setOnTriggers((zoneId, triggers) -> {});
            s.setOnConfigChanged(change -> {});
            s.pause(); s.resume();
            assertFalse(s.isPaused());
            assertNull(s.getFloor());

            OneS1ght.reset(new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "reset"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("reset", got[0]);
            assertFalse(OneS1ght.isInitialized());
        } finally {
            h.close();
        }
    }
}
