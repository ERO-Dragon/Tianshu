package com.rheinmetal.tianshu.function.auxilium.storage;

import java.nio.file.Path;

@FunctionalInterface
public interface AuxiliumStorageConfiguration {
    Path storageRoot();
}
