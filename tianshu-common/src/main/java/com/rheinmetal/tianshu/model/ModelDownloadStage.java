package com.rheinmetal.tianshu.model;

public enum ModelDownloadStage {
    CHECKING_NETWORK,
    RESOLVING_FILES,
    DOWNLOADING,
    EXTRACTING,
    MATERIALIZING,
    PAUSED,
    CANCELLING,
    COMPLETED
}
