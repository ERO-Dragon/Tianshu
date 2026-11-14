package com.rheinmetal.tianshu.core.lifecycle;

public final class ModuleLifecycleException extends RuntimeException {
    private final String moduleId;
    private final ModuleLifecyclePhase phase;

    public ModuleLifecycleException(String moduleId, ModuleLifecyclePhase phase, Throwable cause) {
        super("Module lifecycle failed: " + moduleId + " @ " + phase, cause);
        this.moduleId = moduleId;
        this.phase = phase;
    }

    public String moduleId() {
        return moduleId;
    }

    public ModuleLifecyclePhase phase() {
        return phase;
    }
}
