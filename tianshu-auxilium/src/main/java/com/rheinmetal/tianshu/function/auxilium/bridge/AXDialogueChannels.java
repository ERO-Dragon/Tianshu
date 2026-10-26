package com.rheinmetal.tianshu.function.auxilium.bridge;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;

/**
 * Chooses the dialogue channel for a session: the bridge when the dialogue domain is installed,
 * {@link AXDialogueChannel#NONE} otherwise.
 *
 * <p>This is the one place the "if present, integrate" decision is made, so the assembly and the module
 * cannot drift apart. Loading the bridge is what pulls in that module's payload classes, which is why the
 * decision has to come before the channel is used rather than inside it.</p>
 */
public final class AXDialogueChannels {

    private AXDialogueChannels() {
    }

    public static AXDialogueChannel forRuntime(AXProtocolAdapter adapter) {
        if (adapter == null) {
            return AXDialogueChannel.NONE;
        }
        boolean installed = TianshuRuntimePorts.find(InstalledMods.class)
                .orElseGet(InstalledMods::none)
                .isLoaded(ProtocolModuleIds.IA);
        return installed ? new IaDialogueBridge(adapter) : AXDialogueChannel.NONE;
    }
}
