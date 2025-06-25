package kr.co.mirerotack.btsever1.model;

public class RtuSnapshotType {
    public String type;         // "All" 또는 "Trigger"
    public RtuSnapshot data;    // 실제 데이터

    public RtuSnapshotType(String type, RtuSnapshot data) {
        this.type = type;
        this.data = data;
    }
}
