package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolId;

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

    private static final Map<ToolId, TianshuTool<?, ?>> DECLARED = new ConcurrentHashMap<>();
    private static final Set<ToolId> ACTIVATED = ConcurrentHashMap.newKeySet();
    private static final Set<ToolId> REGISTERED = ConcurrentHashMap.newKeySet();

    private ServerToolDeclarations() {
    }

    /** Publishes a declaration. The server implementation is a separate object on the server side. */
    public static void declare(TianshuTool<?, ?> tool) {
        Objects.requireNonNull(tool, "tool");
        TianshuTool<?, ?> existing = DECLARED.putIfAbsent(tool.id(), tool);
        if (existing != null && existing != tool) {
            throw new IllegalArgumentException("server tool " + tool.id() + " is already declared");
        }
    }

    /** Registers the declarations the server named. Unknown ids are skipped; repeats are idempotent. */
    public static void activate(List<String> serverToolIds) {
        if (serverToolIds == null) {
            return;
        }
        for (String raw : serverToolIds) {
            if (raw == null) {
                continue;
            }
            ToolId id;
            try {
                id = ToolId.parse(raw);
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            TianshuTool<?, ?> declaration = DECLARED.get(id);
            if (declaration == null) {
                continue;
            }
            if (ACTIVATED.add(id) && TianshuTools.find(id) == null) {
                TianshuTools.register(declaration);
                REGISTERED.add(id);
            }
        }
    }

    /** Withdraws everything the catalog had activated; declarations stay, so the next server can re-activate. */
    public static void deactivateAll() {
        for (ToolId id : REGISTERED) {
            TianshuTools.unregister(id);
        }
        REGISTERED.clear();
        ACTIVATED.clear();
    }

    public static Set<ToolId> activated() {
        return Set.copyOf(ACTIVATED);
    }

    /** Tests only: forget declarations and activation. */
    public static void clearForTests() {
        deactivateAll();
        DECLARED.clear();
    }
}