package com.rheinmetal.tianshu.function.auxilium.bridge;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechSink;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

/**
 * Chooses the speech sink for a session: the bridge when the speech module is installed, {@link
 * AXSpeechSink#NONE} otherwise.
 *
 * <p>This is the one place the "if present, integrate" decision is made, so the assembly and the module
 * cannot drift apart. Loading the bridge is what pulls in that module's payload classes, which is why the
 * decision has to come before the sink is used rather than inside it.</p>
 */
public final class AXSpeechSinks {

    private AXSpeechSinks() {
    }

    public static AXSpeechSink forRuntime(ModuleRuntimeAccess runtime) {
        if (runtime == null) {
            return AXSpeechSink.NONE;
        }
        boolean speechInstalled = TianshuRuntimePorts.find(InstalledMods.class)
                .orElseGet(InstalledMods::none)
                .isLoaded(ProtocolModuleIds.TTS);
        return speechInstalled ? new EchoSpeechBridge(runtime) : AXSpeechSink.NONE;
    }
}