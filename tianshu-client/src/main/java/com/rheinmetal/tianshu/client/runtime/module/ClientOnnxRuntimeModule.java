package com.rheinmetal.tianshu.client.runtime.module;

import ai.onnxruntime.OrtEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;

import java.util.concurrent.atomic.AtomicBoolean;

/** Required platform bootstrap that loads ONNX Runtime on the Core lifecycle worker. */
public final class ClientOnnxRuntimeModule implements TianshuManagedModule {
    private static final AtomicBoolean LOADED = new AtomicBoolean();

    @Override
    public String moduleId() {
        return "module.platform.onnx_runtime";
    }

    @Override
    public void prepare(ModuleRuntimeContext context) {
        if (LOADED.get()) {
            return;
        }
        try {
            OrtEnvironment.getEnvironment();
            LOADED.set(true);
        } catch (LinkageError failure) {
            throw new IllegalStateException("ONNX_RUNTIME_NATIVE_LOAD_FAILED", failure);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("ONNX_RUNTIME_INITIALIZATION_FAILED", failure);
        }
    }
}
