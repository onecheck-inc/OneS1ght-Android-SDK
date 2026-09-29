package kr.geoplan.android.lib.ihub;

import android.content.Context;

import kr.geoplan.android.lib.ihub.listener.HubListener;

/**
 * 컴파일 전용 스텁 — 엔진 저장소 계정 없이 빌드·테스트하기 위한 공개 API 모양만.
 * 배포 AAR 에는 절대 실리지 않는다(계정 없으면 assembleRelease·publish 가 실패한다).
 */
public abstract class IntelligenceHub {
    public static void setLicense(String license) { throw new UnsupportedOperationException("stub"); }
    public static IntelligenceHub getInstance(Context context) { throw new UnsupportedOperationException("stub"); }
    public static boolean isUwbHardwareAvailable(Context context) { throw new UnsupportedOperationException("stub"); }
    public abstract void setListener(HubListener listener);
    public abstract void start();
    public abstract void stop();
    public abstract String getLibraryVersion();
}
