package com.rheinmetal.tianshu.function.llm.gateway;

import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationHandle;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public final class LlmGatewayTask {
    private final LlmGatewayRequest request;
    private final TianshuEnvelope parent;
    private LlmGatewayTaskState state;
    private LlmInvocationHandle invocationHandle;
    private long stateUpdatedAtMillis;
    private int streamChunkCount;
    private boolean terminal;

    public LlmGatewayTask(LlmGatewayRequest request, TianshuEnvelope parent) {
        this.request = request;
        this.parent = parent;
        this.state = LlmGatewayTaskState.CREATED;
        this.stateUpdatedAtMillis = System.currentTimeMillis();
    }

    public LlmGatewayRequest request() {
        return request;
    }

    public TianshuEnvelope parent() {
        return parent;
    }

    public LlmGatewayTaskState state() {
        return state;
    }

    public LlmInvocationHandle invocationHandle() {
        return invocationHandle;
    }

    public long stateUpdatedAtMillis() {
        return stateUpdatedAtMillis;
    }

    public int streamChunkCount() {
        return streamChunkCount;
    }

    public boolean terminal() {
        return terminal;
    }

    public void markTerminal() {
        terminal = true;
    }

    public void transitionTo(LlmGatewayTaskState nextState) {
        state = nextState == null ? state : nextState;
        stateUpdatedAtMillis = System.currentTimeMillis();
    }

    public void attachInvocationHandle(LlmInvocationHandle handle) {
        invocationHandle = handle;
    }

    public int nextStreamChunkIndex() {
        return streamChunkCount++;
    }
}
