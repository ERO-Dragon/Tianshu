package com.rheinmetal.tianshu.function.auxilium.bridge;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.function.auxilium.speech.AXSpeechSink;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

/**
 * Chooses the speech sink for a session: the bridge when the speech module is installed, {@link
 * AXSpeechSink#NONE} otherwise.
 *
 * <p>This is the one place the "if present, integrate" decision is made, so the assembly and the module
 * cannot drift apart. Loading the bridge is what pulls in that module's payload classes, which is why the
 * decision has to come before the sink is used rather than inside it.</p>
 *
 * <p>The guard asks the <em>loader</em> about a mod id; protocol module ids are a different namespace and must not be
 * passed here. The id is the echo jar's, which is the artifact that has to be present.</p>
 */
public final class AXSpeechSinks {

    /** Loader id of the speech module; the optional edge's target. */
    private static final String ECHO_MOD_ID = "tianshu_echo";

    private AXSpeechSinks() {
    }

    public static AXSpeechSink forRuntime(ModuleRuntimeAccess runtime) {
        if (runtime == null) {
            return AXSpeechSink.NONE;
        }
        boolean speechInstalled = TianshuRuntimePorts.find(InstalledMods.class)
                .orElseGet(InstalledMods::none)
                .isLoaded(ECHO_MOD_ID);
        return speechInstalled ? new EchoSpeechBridge(runtime) : AXSpeechSink.NONE;
    }
}