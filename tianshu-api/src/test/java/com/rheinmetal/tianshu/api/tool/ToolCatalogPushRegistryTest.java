package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

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
        List<List<ToolCatalogEntry>> seen = new CopyOnWriteArrayList<>();
        ToolCatalogPushRegistry.register("p1", false, (player, tools) -> seen.add(tools));

        assertTrue(ids(seen.get(seen.size() - 1)).contains(READ.toString()));

        TianshuTools.register(new PushTool(OPERATOR, ToolPermission.OPERATOR));
        assertFalse(ids(seen.get(seen.size() - 1)).contains(OPERATOR.toString()),
                "a non-operator must not see an operator-only tool");
    }

    @Test
    void anOperatorSubscriberSeesOperatorToolsAndUnregisterStopsPushes() {
        TianshuTools.register(new PushTool(READ, ToolPermission.EVERYONE));
        TianshuTools.register(new PushTool(OPERATOR, ToolPermission.OPERATOR));
        List<List<ToolCatalogEntry>> seen = new CopyOnWriteArrayList<>();
        ToolCatalogPushRegistry.register("op", true, (player, tools) -> seen.add(tools));

        assertTrue(ids(seen.get(seen.size() - 1)).contains(OPERATOR.toString()));
        int before = seen.size();
        ToolCatalogPushRegistry.unregister("op");
        TianshuTools.unregister(READ);

        assertEquals(before, seen.size());
        assertEquals(0, ToolCatalogPushRegistry.subscriberCount());
    }

    @Test
    void aCatalogueChangeReachesTheSubscriberWithoutTheCallerPushing() throws Exception {
        TianshuTools.register(new PushTool(READ, ToolPermission.EVERYONE));
        List<List<ToolCatalogEntry>> seen = new CopyOnWriteArrayList<>();
        ToolCatalogPushRegistry.register("p1", false, (player, tools) -> seen.add(tools));
        seen.clear();

        // A change-driven publish runs on the registry's own thread, so the caller must not have to do anything.
        TianshuTools.register(new PushTool(OPERATOR, ToolPermission.EVERYONE));

        for (int attempt = 0; attempt < 200 && seen.isEmpty(); attempt++) {
            Thread.sleep(5L);
        }
        assertTrue(seen.stream().anyMatch(entries -> ids(entries).contains(OPERATOR.toString())),
                "the change should have been pushed: " + seen);
    }

    @Test
    void aSlowSubscriberReceivesTheFinalSwitchAfterABurst() throws Exception {
        var firstChange = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var finalChange = new CountDownLatch(1);
        ToolCatalogPushRegistry.register("slow", false, (player, entries) -> {
            for (var entry : entries) {
                if (!entry.toolId().equals(READ.toString())) {
                    continue;
                }
                if (entry.enabled()) {
                    firstChange.countDown();
                    try {
                        assertTrue(resume.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                } else {
                    finalChange.countDown();
                }
            }
        });
        try {
            TianshuTools.register(new PushTool(READ, ToolPermission.EVERYONE));
            assertTrue(firstChange.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < 100; index++) {
                TianshuTools.setEnabled(READ, index % 2 == 0);
            }
        } finally {
            resume.countDown();
        }
        assertTrue(finalChange.await(5, TimeUnit.SECONDS));
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

    /** The ids of a published catalogue; the switch is irrelevant to these assertions. */
    private static List<String> ids(List<ToolCatalogEntry> entries) {
        return entries.stream().map(ToolCatalogEntry::toolId).toList();
    }
}
