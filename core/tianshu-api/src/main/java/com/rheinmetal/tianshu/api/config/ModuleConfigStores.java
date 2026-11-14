package com.rheinmetal.tianshu.api.config;

import java.nio.file.Path;

/**
 * Hands each module its own {@link ModuleConfigStore}.
 *
 * <p>Keyed by module id so two modules can never overwrite each other's settings, and so the kernel
 * does not need a list of the modules that exist. {@link #moduleDirectory()} is exposed because a
 * module keeps its data next to its settings, and the kernel decides that layout, not the module.</p>
 */
public interface ModuleConfigStores {

    ModuleConfigStore forModule(String moduleId);

    /** The directory that holds every module's settings and data, e.g. {@code config/Tianshu/module}. */
    Path moduleDirectory();
}
