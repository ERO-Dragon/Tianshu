package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.llm.engine.LlmEngine;

public final class LlmEngineProvider {
    private final LlmEngine llmEngine;

    public LlmEngineProvider(IGameEnvironment env, ITianshuConfig config) {
        this.llmEngine = new LlmEngine(env, "http://127.0.0.1:" + config.getLlmPort());
    }

    public LlmEngine getLlmEngine() {
        return llmEngine;
    }

    public void stop() {
        llmEngine.shutdown();
    }
}
