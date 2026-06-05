package com.rheinmetal.tianshu.function.llm.service;

public class CacheStats {

    private int uidCount;
    private int totalChunks;
    private long cacheSizeBytes;

    public CacheStats() {
    }

    public CacheStats(int uidCount, int totalChunks, long cacheSizeBytes) {
        this.uidCount = uidCount;
        this.totalChunks = totalChunks;
        this.cacheSizeBytes = cacheSizeBytes;
    }

    public int getUidCount() {
        return uidCount;
    }

    public void setUidCount(int uidCount) {
        this.uidCount = uidCount;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public long getCacheSizeBytes() {
        return cacheSizeBytes;
    }

    public void setCacheSizeBytes(long cacheSizeBytes) {
        this.cacheSizeBytes = cacheSizeBytes;
    }
}