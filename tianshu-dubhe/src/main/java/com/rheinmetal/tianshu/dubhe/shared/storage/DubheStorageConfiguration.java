package com.rheinmetal.tianshu.dubhe.shared.storage;

import java.nio.file.Path;

@FunctionalInterface
public interface DubheStorageConfiguration {
    Path storageRoot();
}
