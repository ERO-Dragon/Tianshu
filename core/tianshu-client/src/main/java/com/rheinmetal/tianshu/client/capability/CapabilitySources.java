package com.rheinmetal.tianshu.client.capability;

import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.client.tool.skill.SkillFileProvider;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The public registration point for capability content owned by another mod.
 *
 * <p>{@code user} and {@code tianshu} are reserved for the player's folder and built-in content; a mod registers
 * under its own id so a short skill name can never collide. Registering twice under the same namespace is a
 * programming error. {@link #closeAll()} is for tests and for a mod that unloads.</p>
 */
public final class CapabilitySources {

    private static final Set<String> RESERVED = Set.of("user", "tianshu");
    private static final Map<String, Runnable> REGISTERED = new LinkedHashMap<>();

    private CapabilitySources() {
    }

    public static synchronized SkillFileProvider registerSkills(String namespace, Path root) {
        requireNamespace(namespace);
        SkillFileProvider provider = SkillFileProvider.install(namespace, root);
        REGISTERED.put(namespace + ":skills", provider::unload);
        return provider;
    }

    public static synchronized TextCapabilityProvider registerResources(String namespace, Path root) {
        return registerText(namespace, root, CapabilityKind.RESOURCE);
    }

    public static synchronized TextCapabilityProvider registerPrompts(String namespace, Path root) {
        return registerText(namespace, root, CapabilityKind.PROMPT);
    }

    public static synchronized void closeAll() {
        REGISTERED.values().forEach(Runnable::run);
        REGISTERED.clear();
    }

    private static TextCapabilityProvider registerText(String namespace, Path root, CapabilityKind kind) {
        requireNamespace(namespace);
        TextCapabilityProvider provider = TextCapabilityProvider.install(kind, namespace, root);
        REGISTERED.put(namespace + ":" + kind.value(), provider::unload);
        return provider;
    }

    private static void requireNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (RESERVED.contains(namespace)) {
            throw new IllegalArgumentException("namespace " + namespace + " is reserved");
        }
        if (REGISTERED.keySet().stream().anyMatch(key -> key.startsWith(namespace + ":"))) {
            throw new IllegalArgumentException("capability content already registered for " + namespace);
        }
    }
}
