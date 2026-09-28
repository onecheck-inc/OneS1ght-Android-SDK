package kr.geoplan.android.lib.dltdoa;

import android.ranging.DlTdoaMeasurement;
import android.ranging.RangingDevice;
import android.util.Pair;

import java.util.List;

public class DlBlockAccumulator {
    public interface BlockReadyListener { void onBlockReady(List<Pair<RangingDevice, DlTdoaMeasurement>> block); }
    public DlBlockAccumulator(long timeoutMillis, BlockReadyListener listener) { throw new UnsupportedOperationException("stub"); }
    public void add(RangingDevice peer, DlTdoaMeasurement m) { throw new UnsupportedOperationException("stub"); }
    public void reset() { throw new UnsupportedOperationException("stub"); }
}
