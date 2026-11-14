package com.rheinmetal.tianshu.function.intake.state;

public final class AsrStateMachine {
    private AsrState state = AsrState.IDLE;

    public synchronized AsrState state() {
        return state;
    }

    public synchronized void moveTo(AsrState next) {
        state = next;
    }

    public synchronized boolean canBeginCapture() {
        return state == AsrState.IDLE;
    }

    public synchronized boolean canEndCapture() {
        return state == AsrState.CAPTURING;
    }

    public synchronized void reset() {
        state = AsrState.IDLE;
    }
}
