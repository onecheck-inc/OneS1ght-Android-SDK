package co.onecheck.ones1ght.android.positioning;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import co.onecheck.ones1ght.android.model.Coordinates;
import co.onecheck.ones1ght.android.model.ZoneEventStatus;
import co.onecheck.ones1ght.android.runtime.SdkErrorCode;
import org.junit.Test;

/**
 * PositioningProvider/PositioningProviderDelegate 는 Java 에서도 구현할 수 있어야 한다
 * (컨트롤러 지시사항): 기본 구현이 있는 멤버(positioningDiagnostic·apply·reloadGeofences·
 * isPaused·pause·resume·onReport)는 오버라이드하지 않아도 컴파일돼야 한다.
 *
 * 이 파일 자체가 컴파일되는 것이 검증이다 — Kotlin 2.2 의 jvm-default 기본값이 인터페이스
 * 디폴트 메서드를 실제 JVM default 메서드로 내보내는지 확인한다.
 */
public final class JavaPositioningProviderCompatTest {

    /** 필수 멤버만 구현 — 디폴트가 있는 것들은 하나도 오버라이드하지 않는다. */
    static final class MinimalProvider implements PositioningProvider {
        private PositioningProviderDelegate delegate;

        @Override
        public PositioningProviderDelegate getDelegate() {
            return delegate;
        }

        @Override
        public void setDelegate(PositioningProviderDelegate value) {
            this.delegate = value;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }

    /** onReport 하나만 디폴트 — 나머지 3 개만 구현한다. */
    static final class MinimalDelegate implements PositioningProviderDelegate {
        @Override
        public void onPosition(PositioningProvider provider, Coordinates coordinates, String floorId, long atMs) {
        }

        @Override
        public void onZone(PositioningProvider provider, String zoneId, ZoneEventStatus status, String floorId, long atMs) {
        }

        @Override
        public void onEnter(PositioningProvider provider, String buildingId) {
        }
    }

    @Test
    public void javaProviderWithoutOverridingDefaultsCompilesAndRunsDefaults() {
        MinimalProvider provider = new MinimalProvider();

        assertNull(provider.getPositioningDiagnostic());
        assertFalse(provider.isPaused());

        // 디폴트 no-op 이 예외 없이 돈다.
        provider.apply("b1", "f1");
        provider.apply(new PositioningConfig(java.util.Collections.emptyMap(), null, java.util.Collections.emptyList()));
        provider.reloadGeofences();
        provider.pause();
        provider.resume();
    }

    @Test
    public void javaDelegateWithoutOverridingOnReportCompilesAndRunsDefault() {
        MinimalDelegate delegate = new MinimalDelegate();
        MinimalProvider provider = new MinimalProvider();

        // onReport 디폴트(no-op)가 예외 없이 돈다.
        delegate.onReport(provider, SdkErrorCode.NETWORK, "ctx");
    }
}
