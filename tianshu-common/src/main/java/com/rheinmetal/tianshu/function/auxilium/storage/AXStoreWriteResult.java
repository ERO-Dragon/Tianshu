package com.rheinmetal.tianshu.function.auxilium.storage;

public record AXStoreWriteResult(boolean success, int writtenRecords) {
    public AXStoreWriteResult {
        writtenRecords = Math.max(0, writtenRecords);
    }

    public static AXStoreWriteResult success(int writtenRecords) {
        return new AXStoreWriteResult(true, writtenRecords);
    }

    public static AXStoreWriteResult failed() {
        return new AXStoreWriteResult(false, 0);
    }
}
