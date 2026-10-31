package com.rheinmetal.tianshu.mc.event;

import com.mojang.blaze3d.platform.InputConstants;
import com.rheinmetal.tianshu.api.assembly.InputSignals;
import com.rheinmetal.tianshu.api.assembly.KeyBindings;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The platform side of {@link KeyBindings}: modules declare, this registers, presses go to the bus.
 *
 * <p>Two ordering facts decide the shape. Declarations happen while mods load and registrations happen later,
 * when the loader offers its key-mapping hook, so declarations are collected here and replayed there. And every
 * press becomes a signal on {@link InputSignals#keyChannel(String)} rather than a call, so a key whose module is
 * not installed simply publishes to nobody.</p>
 *
 * <p>The ownership rule itself lives in {@link KeyBindingPlan}, which has no Minecraft types and is therefore
 * testable without a client. Registration takes a plain {@link Consumer} rather than a loader event, which is
 * what lets both loaders share this class.</p>
 */
public final class DeclaredKeyBindings implements KeyBindings {

    private final Map<String, Declared> declarations = new LinkedHashMap<>();
    private final Map<String, KeyMapping> mappings = new LinkedHashMap<>();
    private final Consumer<String> diagnostics;

    public DeclaredKeyBindings() {
        this(message -> {
        });
    }

    public DeclaredKeyBindings(Consumer<String> diagnostics) {
        this.diagnostics = diagnostics == null ? message -> {
        } : diagnostics;
    }

    @Override
    public synchronized void declare(String keyId, String defaultKeyName, String categoryLangKey) {
        if (keyId == null || keyId.isBlank()) {
            return;
        }
        declarations.put(keyId.trim(), new Declared(keyId.trim(), defaultKeyName == null ? "" : defaultKeyName.trim(), categoryLangKey));
    }

    /** A module's declaration: the key name is resolved to a platform code when the mappings are registered. */
    private record Declared(String keyId, String defaultKeyName, String categoryLangKey) {
    }

    private static int resolveKeyCode(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return KeyBindingPlan.UNBOUND;
        }
        try {
            return InputConstants.getKey(keyName).getValue();
        } catch (RuntimeException unknownName) {
            return KeyBindingPlan.UNBOUND;
        }
    }

    /** Turns declared key names into platform key codes, reporting names the platform does not know. */
    private List<KeyBindingPlan.Declaration> resolvedDeclarations() {
        List<KeyBindingPlan.Declaration> resolved = new ArrayList<>(declarations.size());
        for (Declared declared : declarations.values()) {
            int keyCode = resolveKeyCode(declared.defaultKeyName());
            if (keyCode == KeyBindingPlan.UNBOUND && !declared.defaultKeyName().isBlank()) {
                diagnostics.accept("key " + declared.keyId()
                        + " left unbound: unknown default key " + declared.defaultKeyName());
            }
            resolved.add(new KeyBindingPlan.Declaration(declared.keyId(), keyCode, declared.categoryLangKey()));
        }
        return resolved;
    }

    /** Creates and registers the mappings through whatever registrar the loader offers. */
    public synchronized void registerAll(Consumer<KeyMapping> registrar) {
        if (registrar == null) {
            return;
        }
        List<KeyBindingPlan.Entry> plan = KeyBindingPlan.resolve(resolvedDeclarations());
        for (KeyBindingPlan.Entry entry : plan) {
            KeyMapping existing = mappings.get(entry.keyId());
            if (existing != null) {
                registrar.accept(existing);
                continue;
            }
            if (entry.unbound()) {
                diagnostics.accept("key " + entry.keyId() + " left unbound: its default key is already claimed");
            }
            KeyMapping mapping = new KeyMapping(
                    entry.keyId(),
                    InputConstants.Type.KEYSYM,
                    entry.keyCode(),
                    entry.categoryLangKey());
            mappings.put(entry.keyId(), mapping);
            registrar.accept(mapping);
        }
    }

    /**
     * Turns this tick's presses into signals; called once per client tick.
     *
     * <p>The click queues are drained even while {@code publish} is false, so a press made during startup cannot
     * fire later, after the player already forgot about it; only publishing is suppressed.</p>
     */
    public synchronized void publishPressed(boolean publish) {
        for (Map.Entry<String, KeyMapping> entry : mappings.entrySet()) {
            KeyMapping mapping = entry.getValue();
            while (mapping.consumeClick()) {
                if (publish) {
                    InputSignals.publish(InputSignals.keyChannel(entry.getKey()), "");
                }
            }
        }
    }
}
