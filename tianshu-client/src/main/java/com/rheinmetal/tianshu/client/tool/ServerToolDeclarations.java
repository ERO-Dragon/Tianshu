package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.capability.CapabilitySharing;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCatalogEntry;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declarations of tools that only the server can run.
 *
 * <p>The kernel ships these declarations with the client so it knows the id, the schema and the argument types of
 * every server tool; a declaration is registered in the local registry only after the server's catalog names it.
 * That keeps "the tool exists here" and "the server actually offers it" from drifting apart: a server without the
 * component announces nothing, and nothing appears.</p>
 */
public final class ServerToolDeclarations {

    private record Declaration(TianshuTool<?, ?> tool, String provider, CapabilitySharing sharing) {
    }

    private static final Map<ToolId, Declaration> DECLARED = new ConcurrentHashMap<>();
    private static final Map<ToolId, TianshuTool<?, ?>> REGISTERED = new HashMap<>();

    private ServerToolDeclarations() {
    }

    /** Publishes a declaration. The server implementation is a separate object on the server side. */
    public static void declare(TianshuTool<?, ?> tool) {
        declare(tool, tool.id().namespace(), CapabilitySharing.NONE);
    }

    static void declareBuiltin(TianshuTool<?, ?> tool) {
        declare(tool, CapabilitySharing.BASE_PROVIDER, CapabilitySharing.ALL);
    }

    public static void declare(TianshuTool<?, ?> tool, String provider, CapabilitySharing sharing) {
        var declaration = new Declaration(tool, Objects.requireNonNull(provider), Objects.requireNonNull(sharing));
        var existing = DECLARED.putIfAbsent(tool.id(), declaration);
        if (existing != null && !existing.equals(declaration)) {
            throw new IllegalArgumentException("server tool " + tool.id() + " is already declared");
        }
    }

    /** Epoch 3 originally sent IDs alone. Use the switch the client declaration supplied before servers reported it. */
    public static ToolCatalogEntry legacyEntry(String id) {
        var declaration = DECLARED.get(ToolId.parse(id));
        return new ToolCatalogEntry(id, declaration != null && declaration.tool().policy().enabledByDefault());
    }

    /** Registers the declarations the server named. Unknown ids are skipped; repeats are idempotent. */
    public static synchronized void activate(List<ToolCatalogEntry> tools) {
        if (tools == null) {
            return;
        }
        for (ToolCatalogEntry entry : tools) {
            if (entry == null) {
                continue;
            }
            ToolId id;
            try {
                id = ToolId.parse(entry.toolId());
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            Declaration declaration = DECLARED.get(id);
            if (declaration == null) {
                continue;
            }
            var existing = TianshuTools.find(id);
            if (existing == null) {
                try {
                    TianshuTools.register(declaration.tool(), declaration.provider(), declaration.sharing());
                } catch (CapabilityCatalog.DuplicateCapabilityException conflict) {
                    REGISTERED.remove(id);
                    continue;
                }
                REGISTERED.put(id, declaration.tool());
            } else if (existing != REGISTERED.get(id)) {
                REGISTERED.remove(id);
                continue;
            }
            CapabilityCatalog.setToolEnabled(declaration.tool(), entry.enabled());
        }
    }

    /**
     * Makes the active set match the catalogue exactly: activates new ids and withdraws ones the server no longer
     * names. Login and a periodic refresh both use this, so a removed tool does not linger after its server changed.
     */
    public static synchronized void synchronize(List<ToolCatalogEntry> tools) {
        if (tools == null) {
            return;
        }
        Set<ToolId> desired = new HashSet<>();
        for (ToolCatalogEntry entry : tools) {
            if (entry == null) {
                continue;
            }
            try {
                desired.add(ToolId.parse(entry.toolId()));
            } catch (IllegalArgumentException malformed) {
                // Ignore malformed ids; the server will correct them on the next catalogue.
            }
        }
        for (ToolId id : List.copyOf(REGISTERED.keySet())) {
            if (desired.contains(id)) {
                continue;
            }
            TianshuTools.unregister(REGISTERED.remove(id));
        }
        activate(tools);
    }

    /** Withdraws everything the catalog had activated; declarations stay, so the next server can re-activate. */
    public static synchronized void deactivateAll() {
        List.copyOf(REGISTERED.values()).forEach(TianshuTools::unregister);
        REGISTERED.clear();
    }

    public static synchronized Set<ToolId> activated() {
        return Set.copyOf(REGISTERED.keySet());
    }

    /** Tests only: forget declarations and activation. */
    public static void clearForTests() {
        deactivateAll();
        DECLARED.clear();
    }
}