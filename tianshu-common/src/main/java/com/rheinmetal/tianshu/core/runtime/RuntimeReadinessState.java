package com.rheinmetal.tianshu.core.runtime;

public class RuntimeReadinessState {
    private volatile RuntimeEnginePhase phase = RuntimeEnginePhase.IDLE;

    public RuntimeEnginePhase getPhase() {
        return phase;
    }

    public void setPhase(RuntimeEnginePhase phase) {
        this.phase = phase;
    }
}
