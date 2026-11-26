package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.capability.CapabilitySharing;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCatalogEntry;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private static final Map<ToolId, Declaration> DECLARED = new HashMap<>();
    private static final Map<ToolId, ToolCatalogEntry> OFFERED = new HashMap<>();
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

    public static synchronized void declare(TianshuTool<?, ?> tool, String provider, CapabilitySharing sharing) {
        var declaration = new Declaration(tool, Objects.requireNonNull(provider), Objects.requireNonNull(sharing));
        var existing = DECLARED.putIfAbsent(tool.id(), declaration);
        if (existing != null && !existing.equals(declaration)) {
            throw new IllegalArgumentException("server tool " + tool.id() + " is already declared");
        }
        var entry = OFFERED.get(tool.id());
        if (entry != null) {
            register(declaration, entry);
        }
    }

    /** Epoch 3 originally sent IDs alone. Use the switch the client declaration supplied before servers reported it. */
    public static synchronized ToolCatalogEntry legacyEntry(String id) {
        var declaration = DECLARED.get(ToolId.parse(id));
        return new ToolCatalogEntry(id, declaration != null && declaration.tool().policy().enabledByDefault());
    }

    /** Registers the declarations the server named. Offers are retained until the matching declaration arrives or the server withdraws them. */
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
            OFFERED.put(id, entry);
            Declaration declaration = DECLARED.get(id);
            if (declaration != null) {
                register(declaration, entry);
            }
        }
    }

    private static void register(Declaration declaration, ToolCatalogEntry entry) {
        ToolId id = declaration.tool().id();
        var existing = TianshuTools.find(id);
        if (existing == null) {
            try {
                TianshuTools.register(declaration.tool(), declaration.provider(), declaration.sharing());
            } catch (CapabilityCatalog.DuplicateCapabilityException conflict) {
                REGISTERED.remove(id);
                return;
            }
            REGISTERED.put(id, declaration.tool());
        } else if (existing != REGISTERED.get(id)) {
            REGISTERED.remove(id);
            return;
        }
        CapabilityCatalog.setToolEnabled(declaration.tool(), entry.enabled());
    }

    /**
     * Makes the active set match the catalogue exactly: activates new ids and withdraws ones the server no longer
     * names. Login and a periodic refresh both use this, so a removed tool does not linger after its server changed.
     */
    public static synchronized void synchronize(List<ToolCatalogEntry> tools) {
        if (tools == null) {
            return;
        }
        OFFERED.clear();
        activate(tools);
        for (ToolId id : List.copyOf(REGISTERED.keySet())) {
            if (OFFERED.containsKey(id)) {
                continue;
            }
            TianshuTools.unregister(REGISTERED.remove(id));
        }
    }

    /** Withdraws everything the catalog had activated; declarations stay, so the next server can re-activate. */
    public static synchronized void deactivateAll() {
        List.copyOf(REGISTERED.values()).forEach(TianshuTools::unregister);
        REGISTERED.clear();
        OFFERED.clear();
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