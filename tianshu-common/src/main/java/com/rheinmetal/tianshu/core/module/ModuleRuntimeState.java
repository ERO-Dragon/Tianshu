package com.rheinmetal.tianshu.core.module;

import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;

public final class ModuleRuntimeState {
    private final TianshuCoreManager.State readiness = new TianshuCoreManager.State();
    private volatile AsrEngine asrEngine;

    public TianshuCoreManager.State readiness() {
        return readiness;
    }

    public AsrEngine asrEngine() {
        return asrEngine;
    }

    public void installAsrEngine(AsrEngine engine) {
        asrEngine = engine;
        readiness.setAsrReady(engine != null);
    }

    public void clearAsrEngine() {
        AsrEngine engine = asrEngine;
        if (engine != null) {
            engine.shutdown();
        }
        asrEngine = null;
        readiness.setAsrReady(false);
    }
}
