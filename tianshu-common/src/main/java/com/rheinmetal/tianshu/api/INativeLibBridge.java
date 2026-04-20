package com.rheinmetal.tianshu.api;

import java.nio.file.Path;

public interface INativeLibBridge {

    boolean isNativesReady();

    void extractAndLoadAll();

    void extractServerJar();

    Path getServerJarPath();

    Path getNativesDir();

    Path getRootDir();
}
