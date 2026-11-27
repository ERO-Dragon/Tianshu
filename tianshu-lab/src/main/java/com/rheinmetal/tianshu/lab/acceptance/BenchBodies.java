package com.rheinmetal.tianshu.lab.acceptance;

import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntitySpawnPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.lab.LabModuleRegistration;

/**
 * Withdraws the bodies an interrupted run left behind.
 *
 * <p>Bodies are part of the world: a client killed mid-case never reaches its own release, and the next run would find
 * them still standing. Cleaning them at the start is the bench's business, not the platform's — a module's body
 * outliving the module is a documented state, and the operator command {@code /tianshu body despawn} is the way out for
 * anyone who is not the bench.</p>
 */
final class BenchBodies {

    private BenchBodies() {
    }

    /** Releases every live body this module owns; does nothing where there is no authoritative registry. */
    static void sweep() {
        EntitySpawnPort port = TianshuRuntimePorts.find(EntitySpawnPort.class).orElse(null);
        if (port == null) {
            return;
        }
        for (EntityActor body : EntityActors.list()) {
            if (LabModuleRegistration.MOD_ID.equals(body.ownerModule())) {
                try {
                    port.release(LabModuleRegistration.MOD_ID, body.id());
                } catch (RuntimeException alreadyGone) {
                    // Released by an earlier sweep, or gone with the world; the next one will not see it either.
                }
            }
        }
    }
}
