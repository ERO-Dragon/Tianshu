package com.rheinmetal.tianshu.lab.tool;

import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilitySharing;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscription;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions;
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

    /** Second consumer for the cross-module movement acceptance case. */
    public static final String PEER_MODULE = LabModuleRegistration.MOD_ID + ".peer";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private LabTools() {
    }

    /** The bench's capabilities in registration order. */
    public static List<TianshuTool<?, ?>> tools() {
        return List.of(new SummonBodyTool(), new ReleaseBodyTool());
    }

    /**
     * Publishes them under the bench's module id, and declares what the bench consumes.
     *
     * <p>Both halves belong here rather than in the module's start: a dedicated server loads this mod but does not run
     * the client module, and the server is exactly where a call from the bench's agent is judged — it has to know what
     * that caller subscribes to, or the platform's own tools are invisible to it.</p>
     */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        CapabilitySubscriptions.declare(LabModuleRegistration.MOD_ID,
                CapabilitySubscription.atomicFrom(CapabilitySharing.BASE_PROVIDER));
        CapabilitySubscriptions.declare(PEER_MODULE, CapabilitySubscription.only(CapabilityId.of("minecraft", "entity.move")));
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
        CapabilitySubscriptions.withdraw(PEER_MODULE);
        CapabilitySubscriptions.withdraw(LabModuleRegistration.MOD_ID);
        REGISTERED.set(false);
    }
}