package kr.geoplan.android.lib.dltdoa;

/** 컴파일 전용 스텁 — 실제 구현은 Nexus AAR. 배포물에 절대 실리지 않는다. */
public interface PositionCallback {
    void onPosition(double x, double y, double z);
    void onInvalidated(String error);
}
