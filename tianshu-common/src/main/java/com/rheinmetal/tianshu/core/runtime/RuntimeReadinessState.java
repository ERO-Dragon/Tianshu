package com.rheinmetal.tianshu.core.runtime;

public class RuntimeReadinessState {
    private volatile RuntimeEnginePhase phase = RuntimeEnginePhase.IDLE;

    public RuntimeEnginePhase getPhase() {
        return phase;
    }

    public void setPhase(RuntimeEnginePhase phase) {
        this.phase = phase;
    }

    public void refreshPhase(boolean running) {
        if (phase == RuntimeEnginePhase.DESTROYED || phase == RuntimeEnginePhase.RESTARTING || phase == RuntimeEnginePhase.INITIALIZING) {
            return;
        }
        phase = running ? RuntimeEnginePhase.FULLY_READY : RuntimeEnginePhase.IDLE;
    }

    public void reset() {
        phase = RuntimeEnginePhase.IDLE;
    }
}
