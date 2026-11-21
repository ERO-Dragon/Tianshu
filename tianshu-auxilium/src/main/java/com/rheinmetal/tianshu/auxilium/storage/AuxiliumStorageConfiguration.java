package com.rheinmetal.tianshu.auxilium.storage;

import java.nio.file.Path;

@FunctionalInterface
public interface AuxiliumStorageConfiguration {
    Path storageRoot();
}
