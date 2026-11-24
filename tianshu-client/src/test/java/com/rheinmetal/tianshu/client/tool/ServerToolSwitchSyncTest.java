package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCatalogEntry;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue decides whether a server tool is on.
 *
 * <p>Only ids used to travel, so the client applied its own default — and the world-writing tools default to off. An
 * operator who enabled one on the server then watched the client answer {@code DISABLED} without sending the call: the
 * server setting existed and nothing on the client could see it.</p>
 */
class ServerToolSwitchSyncTest {

    private static final ToolId WORLD_WRITE = ToolId.of("test", "world.write");

    @AfterEach
    void clean() {
        ServerToolDeclarations.clearForTests();
        TianshuTools.unregister(WORLD_WRITE);
    }

    @Test
    void aToolTheServerEnabledBecomesCallableOnTheClient() {
        ServerToolDeclarations.declare(new WorldWriteTool());
        assertFalse(enabled(), "the local default for a world change is off");

        ServerToolDeclarations.activate(List.of(new ToolCatalogEntry(WORLD_WRITE.toString(), true)));

        assertTrue(enabled(), "the server said this tool is on, so the client must not answer DISABLED");
    }

    @Test
    void aToolTheServerDisabledStopsBeingCallable() {
        ServerToolDeclarations.declare(new WorldWriteTool());
        ServerToolDeclarations.activate(List.of(new ToolCatalogEntry(WORLD_WRITE.toString(), true)));

        // The same server turning it off: the next catalogue carries that, and the client follows.
        ServerToolDeclarations.synchronize(List.of(new ToolCatalogEntry(WORLD_WRITE.toString(), false)));

        assertFalse(enabled(), "the server owns the switch in both directions");
    }

    private static boolean enabled() {
        return TianshuTools.isEnabled(WORLD_WRITE);
    }

    /** A world change: `SERVER_WRITE`, so its own default is off until a server says otherwise. */
    private static final class WorldWriteTool implements TianshuTool<JsonObject, JsonObject> {
        @Override
        public ToolId id() {
            return WORLD_WRITE;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.SERVER_WRITE;
        }

        @Override
        public String description() {
            return "A world change; a catalogue test double.";
        }

        @Override
        public Type argsType() {
            return JsonObject.class;
        }

        @Override
        public Type resultType() {
            return JsonObject.class;
        }

        @Override
        public ToolPolicy policy() {
            return ToolPolicy.forCategory(ToolCategory.SERVER_WRITE);
        }

        @Override
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }
}