package com.rheinmetal.tianshu.dubhe.shared.storage;

public record DubheStoreWriteResult(boolean success, int writtenRecords) {
    public DubheStoreWriteResult {
        writtenRecords = Math.max(0, writtenRecords);
    }

    public static DubheStoreWriteResult success(int writtenRecords) {
        return new DubheStoreWriteResult(true, writtenRecords);
    }

    public static DubheStoreWriteResult failed() {
        return new DubheStoreWriteResult(false, 0);
    }
}
