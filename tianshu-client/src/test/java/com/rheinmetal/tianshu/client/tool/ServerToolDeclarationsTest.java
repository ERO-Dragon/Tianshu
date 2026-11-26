package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.LootQuery;
import com.rheinmetal.tianshu.api.game.LootQueryPort;
import com.rheinmetal.tianshu.api.game.LootQueryResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCatalogEntry;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerToolDeclarationsTest {

    private static final ToolId DECLARED = ToolId.of("test", "declared");

    @AfterEach
    void clean() {
        ServerToolDeclarations.clearForTests();
        TianshuTools.unregister(DECLARED);
        TianshuTools.unregister(LootQueryTool.ID);
        TianshuRuntimePorts.clear();
    }

    /** A catalogue entry that offers the tool and leaves it on, which is the ordinary case. */
    private static ToolCatalogEntry entry(com.rheinmetal.tianshu.api.tool.ToolId id) {
        return new ToolCatalogEntry(id.toString(), true);
    }

    @Test
    void refreshingAfterACollisionRecoversWithoutTakingOwnershipOfAReplacement() {
        var id = CapabilityId.parse(DECLARED.toString());
        var declaration = new StubTool();
        var tools = List.of(entry(DECLARED), entry(LootQueryTool.ID));
        ServerToolDeclarations.declare(declaration);
        ServerToolDeclarations.declare(new LootQueryTool());
        CapabilityCatalog.register(new CapabilityDescriptor(id, CapabilityKind.RESOURCE, CapabilityBinding.LOCAL,
                "resource", ToolSide.CLIENT, ToolPermission.EVERYONE, "test", new JsonObject()),
                (context, args) -> CompletableFuture.completedFuture(new JsonObject()));
        try {
            ServerToolDeclarations.synchronize(tools);
            assertFalse(ServerToolDeclarations.activated().contains(DECLARED));
            assertTrue(ServerToolDeclarations.activated().contains(LootQueryTool.ID));
        } finally {
            CapabilityCatalog.unregister(id);
        }
        ServerToolDeclarations.synchronize(tools);
        assertEquals(declaration, TianshuTools.find(DECLARED));
        TianshuTools.unregister(declaration);
        var replacement = new StubTool();
        TianshuTools.register(replacement);
        TianshuTools.setEnabled(DECLARED, false);
        ServerToolDeclarations.synchronize(tools);
        assertFalse(TianshuTools.isEnabled(DECLARED));
        assertFalse(ServerToolDeclarations.activated().contains(DECLARED));
        ServerToolDeclarations.deactivateAll();
        assertEquals(replacement, TianshuTools.find(DECLARED));
    }

    @Test
    void aDeclarationAppearsOnlyAfterTheServerNamesIt() {
        ServerToolDeclarations.declare(new StubTool());
        assertNull(TianshuTools.find(DECLARED), "declaring alone must not register the tool");

        ServerToolDeclarations.activate(List.of(entry(DECLARED)));
        assertTrue(TianshuTools.find(DECLARED) != null);

        ServerToolDeclarations.deactivateAll();
        assertNull(TianshuTools.find(DECLARED));
    }

    @Test
    void unknownIdsAndMalformedIdsAreSkipped() {
        ServerToolDeclarations.declare(new StubTool());
        ServerToolDeclarations.activate(List.of(
                new com.rheinmetal.tianshu.api.tool.ToolCatalogEntry("test:unknown", true),
                new com.rheinmetal.tianshu.api.tool.ToolCatalogEntry("not-a-tool-id", true),
                entry(DECLARED)));
        assertTrue(TianshuTools.find(DECLARED) != null);
        assertTrue(TianshuTools.find(ToolId.of("test", "unknown")) == null);
    }

    @Test
    void repeatedActivationIsIdempotent() {
        ServerToolDeclarations.declare(new StubTool());
        ServerToolDeclarations.activate(List.of(entry(DECLARED)));
        ServerToolDeclarations.activate(List.of(entry(DECLARED)));
        assertEquals(1, ServerToolDeclarations.activated().size());
    }

    @Test
    void aSecondDeclarationOfTheSameIdIsRejected() {
        ServerToolDeclarations.declare(new StubTool());
        assertThrows(IllegalArgumentException.class, () -> ServerToolDeclarations.declare(new StubTool()));
    }

    @Test
    void theLootDeclarationIsShippedAndCanRunOnTheServer() throws Exception {
        TianshuRuntimePorts.register(LootQueryPort.class, query -> CompletableFuture.completedFuture(
                new LootQueryResult(List.of("minecraft:cobblestone"))));
        TianshuTools.register(new LootQueryTool());

        JsonObject result = TianshuTools.callJson(LootQueryTool.ID,
                        JsonParser.parseString("{\"blockId\":\"minecraft:stone\"}"),
                        ToolCaller.player("p", false), ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals("minecraft:cobblestone",
                result.getAsJsonObject("result").getAsJsonArray("items").get(0).getAsString());
    }

    @Test
    void theLootDeclarationRefusesAMissingSide() throws Exception {
        TianshuTools.register(new LootQueryTool());
        JsonObject result = TianshuTools.callJson(LootQueryTool.ID,
                        JsonParser.parseString("{\"blockId\":\"minecraft:stone\"}"),
                        ToolCaller.player("p", false), ToolSide.SERVER)
                .get(2, TimeUnit.SECONDS);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("UNAVAILABLE", result.get("code").getAsString());
    }

    private static final class StubTool implements TianshuTool<JsonObject, JsonObject> {
        @Override
        public ToolId id() {
            return DECLARED;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.SERVER_READ;
        }

        @Override
        public String description() {
            return "A declared-only tool; a test double.";
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
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }
}