package com.rheinmetal.tianshu.api.entity;

import java.util.Objects;
import java.util.Optional;

/**
 * The module-facing way to bring a body into the world.
 *
 * <p>The port is installed by the adapter at bootstrap; modules call {@link #create(EntityBodySpec)}. Keeping the port
 * behind a facade means the loader is someone else's problem, and a module that asks for a body gets one answer:
 * either a commandable {@link EntityActor}, or {@link Optional#empty()} when this side cannot create one.</p>
 *
 * <p>Installing twice replaces the port: a client half that installs later must win, and a shutdown that clears it must
 * leave the platform usable rather than pointing at a destroyed level.</p>
 */
public final class EntityBodies {

    private static volatile EntityCreationPort port;

    private EntityBodies() {
    }

    public static void install(EntityCreationPort creationPort) {
        port = creationPort;
    }

    public static void uninstall() {
        port = null;
    }

    /**
     * Creates one body.
     *
     * @throws IllegalStateException when no adapter has installed a port — a wiring mistake, not a runtime condition
     */
    public static Optional<EntityActor> create(EntityBodySpec spec) {
        Objects.requireNonNull(spec, "spec");
        EntityCreationPort installed = port;
        if (installed == null) {
            throw new IllegalStateException("no entity creation port is installed; the adapter must install one");
        }
        return installed.create(spec);
    }

    /** Whether a port is installed; a caller that must degrade gracefully asks this first. */
    public static boolean available() {
        return port != null;
    }
}