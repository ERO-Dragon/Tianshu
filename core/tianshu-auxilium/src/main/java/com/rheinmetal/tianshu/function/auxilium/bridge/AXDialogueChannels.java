package com.rheinmetal.tianshu.function.auxilium.bridge;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;

/**
 * Chooses the dialogue channel for a session: the bridge when the dialogue domain is installed,
 * {@link AXDialogueChannel#NONE} otherwise.
 *
 * <p>This is the one place the "if present, integrate" decision is made, so the assembly and the module
 * cannot drift apart. Loading the bridge is what pulls in that module's payload classes, which is why the
 * decision has to come before the channel is used rather than inside it.</p>
 *
 * <p>The guard asks the <em>loader</em> about a mod id; protocol module ids are a different namespace and must not be
 * passed here.</p>
 */
public final class AXDialogueChannels {

    /** Loader id of the dialogue module; the optional edge's target. */
    private static final String INTAKE_MOD_ID = "tianshu_intake";

    private AXDialogueChannels() {
    }

    public static AXDialogueChannel forRuntime(AXProtocolAdapter adapter) {
        if (adapter == null) {
            return AXDialogueChannel.NONE;
        }
        boolean installed = TianshuRuntimePorts.find(InstalledMods.class)
                .orElseGet(InstalledMods::none)
                .isLoaded(INTAKE_MOD_ID);
        return installed ? new IaDialogueBridge(adapter) : AXDialogueChannel.NONE;
    }
}
