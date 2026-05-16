package com.rheinmetal.tianshu.function.asr.state;

public final class AsrStateMachine {
    private AsrState state = AsrState.IDLE;

    public synchronized AsrState state() {
        return state;
    }

    public synchronized boolean is(AsrState expected) {
        return state == expected;
    }

    public synchronized boolean transition(AsrState expected, AsrState next) {
        if (state != expected) {
            return false;
        }
        state = next;
        return true;
    }

    public synchronized void moveTo(AsrState next) {
        state = next;
    }

    public synchronized boolean canBeginCapture() {
        return state == AsrState.IDLE || state == AsrState.STREAMING;
    }

    public synchronized boolean canEndCapture() {
        return state == AsrState.CAPTURING;
    }

    public synchronized boolean canStartStreaming() {
        return state == AsrState.IDLE;
    }

    public synchronized boolean canCommitStream() {
        return state == AsrState.STREAMING;
    }

    public synchronized void reset() {
        state = AsrState.IDLE;
    }
}
