package com.rheinmetal.tianshu.function.MR;

public class MrStateMachine {

    public enum State { SILENT, SCANNING, FOCUSING }

    private State state = State.SILENT;
    private String focusedEntityUuid = null;

    public void transitionToScanning() {
        if (state == State.FOCUSING) {
            focusedEntityUuid = null;
        }
        state = State.SCANNING;
    }

    public void transitionToFocusing(String entityUuid) {
        if (state == State.SILENT) return;
        state = State.FOCUSING;
        focusedEntityUuid = entityUuid;
    }

    public void transitionToSilent() {
        state = State.SILENT;
        focusedEntityUuid = null;
    }

    public boolean isScanning() {
        return state == State.SCANNING;
    }

    public boolean isFocusing() {
        return state == State.FOCUSING;
    }

    public boolean isActive() {
        return state != State.SILENT;
    }

    public State getState() {
        return state;
    }

    public String getFocusedEntityUuid() {
        return focusedEntityUuid;
    }
}
