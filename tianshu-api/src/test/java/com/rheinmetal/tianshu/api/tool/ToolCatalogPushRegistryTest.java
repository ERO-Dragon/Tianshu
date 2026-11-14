package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogPushRegistryTest {

    private static final ToolId READ = ToolId.of("test", "push.read");
    private static final ToolId OPERATOR = ToolId.of("test", "push.operator");

    @AfterEach
    void clean() {
        TianshuTools.unregister(READ);
        TianshuTools.unregister(OPERATOR);
        ToolCatalogPushRegistry.clear();
    }

    @Test
    void aSubscriberGetsTheVisibleCatalogueAndFollowsChanges() {
        TianshuTools.register(new PushTool(READ, ToolPermission.EVERYONE));
        List<List<String>> seen = new ArrayList<>();
        ToolCatalogPushRegistry.register("p1", false, (player, ids) -> seen.add(ids));

        assertTrue(seen.get(seen.size() - 1).contains(READ.toString()));

        TianshuTools.register(new PushTool(OPERATOR, ToolPermission.OPERATOR));
        assertFalse(seen.get(seen.size() - 1).contains(OPERATOR.toString()),
                "a non-operator must not see an operator-only tool");
    }

    @Test
    void anOperatorSubscriberSeesOperatorToolsAndUnregisterStopsPushes() {
        TianshuTools.register(new PushTool(READ, ToolPermission.EVERYONE));
        TianshuTools.register(new PushTool(OPERATOR, ToolPermission.OPERATOR));
        List<List<String>> seen = new ArrayList<>();
        ToolCatalogPushRegistry.register("op", true, (player, ids) -> seen.add(ids));

        assertTrue(seen.get(seen.size() - 1).contains(OPERATOR.toString()));
        int before = seen.size();
        ToolCatalogPushRegistry.unregister("op");
        TianshuTools.unregister(READ);

        assertEquals(before, seen.size());
        assertEquals(0, ToolCatalogPushRegistry.subscriberCount());
    }

    private record PushTool(ToolId id, ToolPermission permission) implements TianshuTool<JsonObject, JsonObject> {

        @Override
        public ToolCategory category() {
            return ToolCategory.SERVER_READ;
        }

        @Override
        public String description() {
            return "Push registry test tool.";
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
            return ToolPolicy.forCategory(category()).withPermission(permission);
        }

        @Override
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }
}
