package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.agent.AgentBodies;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * Owns the single body registry of the running server and keeps the platform registry in step with it.
 *
 * <p>The adapter starts once per server lifecycle, so this is a switch rather than a collection: starting a server
 * installs a fresh registry and stopping removes it, which also makes an integrated server restart behave.</p>
 */
public final class BodyRuntime {

    private static BodyRegistry registry;
    private static Runnable unregister;

    private BodyRuntime() {
    }

    public static synchronized BodyRegistry start(MinecraftServer server) {
        stop();
        registry = new BodyRegistry(server);
        unregister = AgentBodies.register(registry);
        return registry;
    }

    public static synchronized void stop() {
        if (unregister != null) {
            unregister.run();
        }
        unregister = null;
        registry = null;
    }

    public static Optional<BodyRegistry> current() {
        return Optional.ofNullable(registry);
    }

    /** The registry of the running server, or a clear failure when a caller runs outside one. */
    public static BodyRegistry require() {
        BodyRegistry current = registry;
        if (current == null) {
            throw new IllegalStateException("agent bodies need a running server");
        }
        return current;
    }
}