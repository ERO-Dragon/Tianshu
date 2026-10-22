package com.rheinmetal.tianshu.model;

public record ModelDownloadProgress(
        ModelDownloadStage stage,
        int percent,
        long downloadedBytes,
        long totalBytes,
        String detailCode
) {
    public ModelDownloadProgress {
        stage = stage == null ? ModelDownloadStage.CHECKING_NETWORK : stage;
        percent = Math.max(0, Math.min(100, percent));
        downloadedBytes = Math.max(0L, downloadedBytes);
        totalBytes = Math.max(0L, totalBytes);
        detailCode = detailCode == null ? "" : detailCode.trim();
    }

    public static ModelDownloadProgress stage(ModelDownloadStage stage, int percent, String detailCode) {
        return new ModelDownloadProgress(stage, percent, 0L, 0L, detailCode);
    }

    public static ModelDownloadProgress bytes(
            ModelDownloadStage stage,
            int percent,
            long downloadedBytes,
            long totalBytes,
            String detailCode
    ) {
        return new ModelDownloadProgress(stage, percent, downloadedBytes, totalBytes, detailCode);
    }
}
