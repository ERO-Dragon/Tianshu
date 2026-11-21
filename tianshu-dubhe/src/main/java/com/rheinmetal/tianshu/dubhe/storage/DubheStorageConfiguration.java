package com.rheinmetal.tianshu.dubhe.storage;

import java.nio.file.Path;

@FunctionalInterface
public interface DubheStorageConfiguration {
    Path storageRoot();
}
