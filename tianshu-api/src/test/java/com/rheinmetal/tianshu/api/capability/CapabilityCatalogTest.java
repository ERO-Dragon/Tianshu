package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The capability table holds every kind, mirrors tools, enforces permission/side and renders an exposure block. */
class CapabilityCatalogTest {

    private static final ToolId ECHO = ToolId.of("test", "echo");

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        CapabilityCatalog.clear();
    }

    @AfterEach
    void clean() {
        CapabilityCatalog.clear();
        TianshuTools.unregister(ECHO);
    }

    private static CapabilityDescriptor descriptor(CapabilityId id, CapabilityKind kind,
                                                   ToolPermission permission, ToolSide side) {
        return new CapabilityDescriptor(id, kind, CapabilityBinding.LOCAL, "a test capability", side, permission,
                "test", new JsonObject());
    }

    private static CapabilityHandler textHandler(String text) {
        return (context, args) -> {
            JsonObject envelope = new JsonObject();
            envelope.addProperty("ok", true);
            envelope.addProperty("text", text);
            return CompletableFuture.completedFuture(envelope);
        };
    }


    @Test
    void aDisabledToolDisappearsFromTheExposure() {
        TianshuTools.register(new EchoTool());
        CapabilityId id = CapabilityId.of("test", "echo");
        assertTrue(CapabilityExposure.exposed(ToolCaller.mod("m"), ToolSide.CLIENT).stream()
                .anyMatch(descriptor -> descriptor.id().equals(id)));

        TianshuTools.setEnabled(ECHO, false);
        assertFalse(CapabilityExposure.exposed(ToolCaller.mod("m"), ToolSide.CLIENT).stream()
                .anyMatch(descriptor -> descriptor.id().equals(id)));
        assertFalse(CapabilityCatalog.find(id).orElseThrow().attributes().get("enabled").getAsBoolean());

        TianshuTools.clearEnabledOverride(ECHO);
        assertTrue(CapabilityExposure.exposed(ToolCaller.mod("m"), ToolSide.CLIENT).stream()
                .anyMatch(descriptor -> descriptor.id().equals(id)));
    }

    @Test
    void toolsAreMirroredIntoTheTableAndExecutedThroughTheRegistry() throws Exception {
        TianshuTools.register(new EchoTool());

        CapabilityDescriptor mirror = CapabilityCatalog.find(CapabilityId.of("test", "echo")).orElseThrow();
        assertEquals(CapabilityKind.TOOL, mirror.kind());
        assertEquals(CapabilityBinding.LOCAL, mirror.binding());
        assertEquals(ToolSide.CLIENT, mirror.side());

        JsonObject result = CapabilityCatalog.invoke(CapabilityId.of("test", "echo"),
                        new JsonObject(), ToolCaller.mod("m"), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertTrue(result.get("ok").getAsBoolean());

        TianshuTools.unregister(ECHO);
        assertTrue(CapabilityCatalog.find(CapabilityId.of("test", "echo")).isEmpty());
    }

    @Test
    void everyNonToolKindIsRegisteredListedAndInvoked() throws Exception {
        for (CapabilityKind kind : List.of(CapabilityKind.SKILL, CapabilityKind.RESOURCE, CapabilityKind.PROMPT,
                CapabilityKind.CONNECTOR)) {
            CapabilityId id = CapabilityId.of("test", kind.value());
            CapabilityCatalog.register(descriptor(id, kind, ToolPermission.EVERYONE, ToolSide.CLIENT),
                    textHandler(kind.value() + " result"));
        }

        assertEquals(4, CapabilityCatalog.list().size());
        assertEquals(1, CapabilityCatalog.list(CapabilityKind.SKILL).size());

        JsonObject result = CapabilityCatalog.invoke(CapabilityId.of("test", "skill"),
                        new JsonObject(), ToolCaller.mod("m"), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals("skill result", result.get("text").getAsString());
    }

    @Test
    void operatorOnlyCapabilitiesAreDeniedAndHiddenFromPlainCallers() throws Exception {
        CapabilityId id = CapabilityId.of("test", "secret");
        CapabilityCatalog.register(descriptor(id, CapabilityKind.SKILL, ToolPermission.OPERATOR, ToolSide.CLIENT),
                textHandler("secret"));

        JsonObject denied = CapabilityCatalog.invoke(id, new JsonObject(), ToolCaller.mod("m"), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertFalse(denied.get("ok").getAsBoolean());
        assertEquals("NO_PERMISSION", denied.get("code").getAsString());

        assertTrue(CapabilityExposure.exposed(ToolCaller.mod("m"), ToolSide.CLIENT).isEmpty());
        assertEquals(1, CapabilityExposure.exposed(ToolCaller.player("op", true), ToolSide.CLIENT).size());
    }

    @Test
    void aServerCapabilityWithoutAChannelIsUnsupported() throws Exception {
        CapabilityId id = CapabilityId.of("test", "remote");
        CapabilityCatalog.register(descriptor(id, CapabilityKind.RESOURCE, ToolPermission.EVERYONE, ToolSide.SERVER),
                textHandler("remote"));

        JsonObject result = CapabilityCatalog.invoke(id, new JsonObject(), ToolCaller.mod("m"), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("UNSUPPORTED_SIDE", result.get("code").getAsString());
        assertTrue(CapabilityExposure.exposed(ToolCaller.mod("m"), ToolSide.CLIENT).isEmpty());
    }

    @Test
    void aSlowHandlerTimesOutInsteadOfHanging() throws Exception {
        CapabilityId id = CapabilityId.of("test", "slow");
        JsonObject attributes = new JsonObject();
        attributes.addProperty("timeoutMillis", 100);
        CapabilityCatalog.register(new CapabilityDescriptor(id, CapabilityKind.SKILL, CapabilityBinding.LOCAL,
                        "slow", ToolSide.CLIENT, ToolPermission.EVERYONE, "test", attributes),
                (context, args) -> new CompletableFuture<>());

        JsonObject result = CapabilityCatalog.invoke(id, new JsonObject(), ToolCaller.mod("m"), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals("TIMEOUT", result.get("code").getAsString());
    }

    @Test
    void unknownIdsAndDuplicateRegistrationsAreRejected() throws Exception {
        JsonObject unknown = CapabilityCatalog.invoke(CapabilityId.of("test", "missing"), new JsonObject(),
                        ToolCaller.mod("m"), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertEquals("UNKNOWN_CAPABILITY", unknown.get("code").getAsString());

        CapabilityId id = CapabilityId.of("test", "dup");
        CapabilityCatalog.register(descriptor(id, CapabilityKind.PROMPT, ToolPermission.EVERYONE, ToolSide.CLIENT),
                textHandler("x"));
        assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.register(
                descriptor(id, CapabilityKind.PROMPT, ToolPermission.EVERYONE, ToolSide.CLIENT), textHandler("y")));
    }

    @Test
    void aNonToolCapabilityCannotShareAnIdWithATool() {
        CapabilityCatalog.register(descriptor(CapabilityId.of("test", "echo"), CapabilityKind.SKILL,
                ToolPermission.EVERYONE, ToolSide.CLIENT), textHandler("taken"));
        assertThrows(IllegalArgumentException.class, () -> TianshuTools.register(new EchoTool()));
        assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.register(
                descriptor(CapabilityId.of("test", "echo"), CapabilityKind.RESOURCE, ToolPermission.EVERYONE,
                        ToolSide.CLIENT), textHandler("dup")));
    }

    @Test
    void toolsCannotBeRegisteredThroughTheCapabilityTable() {
        assertThrows(IllegalArgumentException.class, () -> CapabilityCatalog.register(
                descriptor(CapabilityId.of("test", "forged"), CapabilityKind.TOOL, ToolPermission.EVERYONE,
                        ToolSide.CLIENT), textHandler("x")));
    }

    @Test
    void exposureExcludesConnectorsAndRendersTheRemainingKinds() {
        CapabilityCatalog.register(descriptor(CapabilityId.of("test", "skill"), CapabilityKind.SKILL,
                ToolPermission.EVERYONE, ToolSide.CLIENT), textHandler("skill"));
        CapabilityCatalog.register(descriptor(CapabilityId.of("test", "resource"), CapabilityKind.RESOURCE,
                ToolPermission.EVERYONE, ToolSide.CLIENT), textHandler("resource"));
        CapabilityCatalog.register(descriptor(CapabilityId.of("test", "connector"), CapabilityKind.CONNECTOR,
                ToolPermission.EVERYONE, ToolSide.CLIENT), textHandler("connector"));

        String block = CapabilityExposure.describe(
                CapabilityExposure.exposed(ToolCaller.mod("m"), ToolSide.CLIENT));
        assertTrue(block.contains("test:skill"));
        assertTrue(block.contains("test:resource"));
        assertFalse(block.contains("test:connector"));
    }

    @Test
    void resourceTextIsExtractedFromTheEnvelope() throws Exception {
        CapabilityId id = CapabilityId.of("test", "doc");
        CapabilityCatalog.register(descriptor(id, CapabilityKind.RESOURCE, ToolPermission.EVERYONE, ToolSide.CLIENT),
                textHandler("document body"));

        JsonObject envelope = CapabilityCatalog.invoke(id, new JsonObject(), ToolCaller.mod("m"), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
        assertEquals("document body", CapabilityExposure.textOf(envelope));
    }

    public static final class Args {
    }

    public static final class Reply {
    }

    private static final class EchoTool implements TianshuTool<Args, Reply> {
        @Override
        public ToolId id() {
            return ECHO;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Echo; a test double.";
        }

        @Override
        public Type argsType() {
            return Args.class;
        }

        @Override
        public Type resultType() {
            return Reply.class;
        }

        @Override
        public CompletableFuture<Reply> invoke(ToolContext context, Args args) {
            return CompletableFuture.completedFuture(new Reply());
        }
    }
}