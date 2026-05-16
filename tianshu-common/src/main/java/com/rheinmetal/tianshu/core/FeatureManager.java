package com.rheinmetal.tianshu.core;

public final class FeatureManager {
    private FeatureManager() {}

    private static volatile boolean tianshuEnabled = true;

    public static boolean isTianshuEnabled() {
        return tianshuEnabled;
    }

    public static void setTianshuEnabled(boolean enabled) {
        tianshuEnabled = enabled;
    }
}
