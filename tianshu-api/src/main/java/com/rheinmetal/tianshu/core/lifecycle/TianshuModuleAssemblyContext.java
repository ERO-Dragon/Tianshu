package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public record TianshuModuleAssemblyContext(
        IGameEnvironment env,
        IAudioBridge audioBridge,
        ModuleRuntimeAccess moduleRuntime,
        BooleanSupplier voiceInputGate,
        LongSupplier interruptionSignal
) {}
