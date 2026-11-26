package com.rheinmetal.tianshu.client.capability;

import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.capability.skill.SkillFileProvider;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import java.util.Set;
import java.util.List;
import java.util.function.Supplier;

/**
 * The public registration point for capability content owned by another mod.
 *
 * <p>{@code user} and {@code tianshu} are reserved for the player's folder and built-in content; a mod registers
 * under its own id so a short skill name can never collide. Each content kind has its own registration. A module closes its handles on unload.</p>
 */
public final class CapabilitySources {

    private static final Set<String> RESERVED = Set.of("user", "tianshu");
    private static final Map<CapabilityId, Registration> REGISTERED = new LinkedHashMap<>();

    private CapabilitySources() {
    }

    /** The registering module owns this handle for its lifetime. Closing it twice is safe. */
    public static final class Registration implements AutoCloseable {
        private final CapabilityId key;
        private Runnable unload;

        private Registration(CapabilityId key) {
            this.key = key;
        }

        @Override
        public void close() {
            synchronized (CapabilitySources.class) {
                if (REGISTERED.get(key) == this) {
                    try {
                        if (unload != null) {
                            unload.run();
                        }
                    } finally {
                        REGISTERED.remove(key, this);
                        unload = null;
                    }
                }
            }
        }
    }

    public static Registration registerSkills(String namespace, Path root) {
        return register(namespace, CapabilityKind.SKILL, () -> SkillFileProvider.install(namespace, root)::unload);
    }

    public static Registration registerResources(String namespace, Path root) {
        return registerText(namespace, root, CapabilityKind.RESOURCE);
    }

    public static Registration registerPrompts(String namespace, Path root) {
        return registerText(namespace, root, CapabilityKind.PROMPT);
    }

    private static Registration registerText(String namespace, Path root, CapabilityKind kind) {
        return register(namespace, kind, () -> TextCapabilityProvider.install(kind, namespace, root)::unload);
    }

    private static synchronized Registration register(String namespace, CapabilityKind kind, Supplier<Runnable> install) {
        if (RESERVED.contains(namespace)) {
            throw new IllegalArgumentException("reserved content namespace: " + namespace);
        }
        CapabilityId key = CapabilityId.of(namespace, kind.value());
        Registration registration = new Registration(key);
        if (REGISTERED.putIfAbsent(key, registration) != null) {
            throw new IllegalArgumentException("content source already registered: " + key);
        }
        try {
            registration.unload = install.get();
            if (REGISTERED.get(key) != registration) {
                registration.unload.run();
                throw new IllegalStateException("content registration closed during installation: " + key);
            }
            return registration;
        } catch (RuntimeException failure) {
            REGISTERED.remove(key, registration);
            throw failure;
        }
    }

    public static synchronized void closeAll() {
        List.copyOf(REGISTERED.values()).forEach(Registration::close);
    }
}
