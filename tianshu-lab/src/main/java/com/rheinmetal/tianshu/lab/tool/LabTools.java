package com.rheinmetal.tianshu.lab.tool;

import com.rheinmetal.tianshu.api.capability.CapabilitySharing;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.lab.LabModuleRegistration;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The bench's own tools, registered once per process.
 *
 * <p>They exist so the acceptance run can drive a body across the wire the way a feature module does: the module
 * publishes a capability, the server's catalogue names it, a client calls it, and the authoritative side creates the
 * body. Nothing here is shipped — the lab is a development module.</p>
 */
public final class LabTools {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private LabTools() {
    }

    /** The bench's capabilities in registration order. */
    public static List<TianshuTool<?, ?>> tools() {
        return List.of(new SummonBodyTool(), new ReleaseBodyTool());
    }

    /** Publishes them under the bench's module id, so a client sees them as that module's capability. */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        for (TianshuTool<?, ?> tool : tools()) {
            TianshuTools.register(tool, LabModuleRegistration.MOD_ID, CapabilitySharing.ALL);
        }
    }

    public static boolean registered() {
        return REGISTERED.get();
    }

    /** Drops them again; tests only. */
    public static void unregisterForTests() {
        tools().forEach(tool -> TianshuTools.unregister(tool.id()));
        REGISTERED.set(false);
    }
}