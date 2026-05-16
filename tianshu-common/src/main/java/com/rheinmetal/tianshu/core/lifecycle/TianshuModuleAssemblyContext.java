package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public record TianshuModuleAssemblyContext(
        IGameEnvironment env,
        ITianshuConfig config,
        INativeLibBridge nativeLibBridge,
        IAudioBridge audioBridge,
        TianshuEventBus eventBus,
        ProtocolRuntime protocolRuntime,
        BooleanSupplier voiceInputGate,
        LongSupplier interruptionSignal
) {}
