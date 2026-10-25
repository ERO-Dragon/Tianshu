package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.api.assembly.ModuleRuntimeServices;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * What a module gets while it builds itself for a session.
 *
 * <p>Everything here is a kernel port: the module never receives the host, the config file or the
 * protocol runtime's internals. {@code clientPorts} may be absent in headless assembly, so a module
 * that needs a UI must handle its absence instead of assuming one.</p>
 */
public record TianshuModuleAssemblyContext(
        IGameEnvironment env,
        IAudioBridge audioBridge,
        ModuleRuntimeAccess moduleRuntime,
        BooleanSupplier voiceInputGate,
        LongSupplier interruptionSignal,
        ClientPorts clientPorts,
        ModuleRuntimeServices services
) {

    public boolean hasClientPorts() {
        return clientPorts != null;
    }
}
