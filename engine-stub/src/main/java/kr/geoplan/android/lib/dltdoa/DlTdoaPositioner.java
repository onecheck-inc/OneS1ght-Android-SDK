package kr.geoplan.android.lib.dltdoa;

import android.ranging.DlTdoaMeasurement;
import android.ranging.RangingDevice;
import android.util.Pair;

import java.util.List;
import java.util.Map;

public class DlTdoaPositioner {
    public DlTdoaPositioner(PositionCallback callback) { throw new UnsupportedOperationException("stub"); }
    public void applyAnchorCoordinates(Map<Integer, double[]> coords) { throw new UnsupportedOperationException("stub"); }
    public void setMinRssi(int minRssi) { throw new UnsupportedOperationException("stub"); }
    public void setAssumedTagZ(double z) { throw new UnsupportedOperationException("stub"); }
    public void setMaxSpeed(double metersPerSecond) { throw new UnsupportedOperationException("stub"); }
    public double[] update(List<Pair<RangingDevice, DlTdoaMeasurement>> block) { throw new UnsupportedOperationException("stub"); }
    public void reset() { throw new UnsupportedOperationException("stub"); }
}
