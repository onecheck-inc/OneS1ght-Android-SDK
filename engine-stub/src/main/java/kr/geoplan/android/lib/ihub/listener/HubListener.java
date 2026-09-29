package kr.geoplan.android.lib.ihub.listener;

/** 컴파일 전용 스텁 — 엔진 콜백 계약의 모양만. */
public interface HubListener {
    void onStarted();
    void onStopped();
    void onTrackingStarted(long floorId);
    void onTrackingStopped(long floorId);
    void onPosition(long floorId, double x, double y, double z);
    void onAreaEvent(long floorId, String areaName, String inOut);
    void onError(int code, String msg);
}
