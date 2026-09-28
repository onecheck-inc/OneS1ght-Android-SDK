package co.onecheck.ones1ght.android;

import co.onecheck.ones1ght.android.model.Building;
import co.onecheck.ones1ght.android.model.Floor;
import co.onecheck.ones1ght.android.model.FloorLocators;
import co.onecheck.ones1ght.android.model.Zone;
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

    /** 실제 층(도면·앵커·구역을 서버에서 받은 Floor)을 Java 에서 골라 setFloorMap 한다. */
    @Test public void setFloorMapWithRealFloorFromJava() throws Exception {
        JavaInteropHarness h = JavaInteropHarness.start();
        try {
            h.enableSpaceService();
            final Object[] got = new Object[1];
            OneS1ght.initialize(h.context(), "ock_java_floor", h.baseUrl(), new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "ok"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("ok", got[0]);

            OneS1ght.floor("b1", "f1", new Callback<Floor>() {
                @Override public void onSuccess(Floor r) { got[0] = r; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain();
            assertTrue(String.valueOf(got[0]), got[0] instanceof Floor);
            Floor floor = (Floor) got[0];
            assertEquals("f1", floor.getId());
            assertEquals("F1", floor.getName());
            assertTrue(floor.getHasPlan());
            assertNotNull("도면 이미지까지 받아야 한다", floor.getImage());

            OneS1ght.setFloorMap(floor, "b1", new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "floor-set"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("floor-set", got[0]);

            FloorSession s = OneS1ght.floorSession();
            assertEquals(floor, s.getFloor());

            OneS1ght.locators("b1", "f1", new Callback<FloorLocators>() {
                @Override public void onSuccess(FloorLocators r) { got[0] = r; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain();
            FloorLocators locators = (FloorLocators) got[0];
            assertEquals(1, locators.getLocators().size());
            assertEquals(Integer.valueOf(7), locators.getSessionId());

            OneS1ght.refreshZones(new Callback<List<Zone>>() {
                @Override public void onSuccess(List<Zone> r) { got[0] = r; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain();
            @SuppressWarnings("unchecked") List<Zone> zones = (List<Zone>) got[0];
            assertEquals(1, zones.size());
            assertEquals("z-1", zones.get(0).getId());

            // 층을 비운다(직전 건물 판).
            OneS1ght.setFloorMap(null, new Callback<Void>() {
                @Override public void onSuccess(Void r) { got[0] = "floor-cleared"; }
                @Override public void onError(Throwable e) { got[0] = e; } });
            h.drain(); assertEquals("floor-cleared", got[0]);
            assertNull(s.getFloor());
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
