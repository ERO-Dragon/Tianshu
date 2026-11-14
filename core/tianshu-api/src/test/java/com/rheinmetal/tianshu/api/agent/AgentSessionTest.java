package com.rheinmetal.tianshu.api.agent;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionTest {

    private static final ToolId TOOL = ToolId.of("test", "session.tool");
    private static final CapabilityId SKILL = CapabilityId.of("test", "session.skill");

    @AfterEach
    void clean() {
        TianshuTools.unregister(TOOL);
        CapabilityCatalog.unregister(SKILL);
        CapabilityCatalog.unregister(CapabilityId.of("test", "session.extra"));
    }

    @Test
    void theSessionComposesDiscoveryInvocationReadsAndChanges() throws Exception {
        TianshuTools.register(new SessionTool());
        CapabilityCatalog.register(new CapabilityDescriptor(SKILL, CapabilityKind.SKILL, CapabilityBinding.LOCAL,
                        "a test skill", ToolSide.CLIENT, ToolPermission.EVERYONE, "test", new JsonObject()),
                (context, args) -> {
                    JsonObject envelope = new JsonObject();
                    envelope.addProperty("ok", true);
                    envelope.addProperty("text", "skill body");
                    return CompletableFuture.completedFuture(envelope);
                });

        AgentSession session = AgentSession.client(ToolCaller.agent(false));
        assertTrue(session.tools().stream().anyMatch(spec -> spec.id().equals(TOOL)));
        assertTrue(session.capabilities().stream().anyMatch(row -> row.id().equals(SKILL)));

        JsonObject contract = session.describe();
        assertEquals(AgentSession.PROTOCOL, contract.get("protocol").getAsString());
        assertEquals(8, contract.getAsJsonObject("limits").get("maxInFlightPerCaller").getAsInt());
        assertTrue(contract.getAsJsonArray("tools").toString().contains(TOOL.toString()));

        JsonObject call = session.call(TOOL, new JsonObject()).get(2, TimeUnit.SECONDS);
        assertTrue(call.get("ok").getAsBoolean());

        JsonObject read = session.read(SKILL, new JsonObject()).get(2, TimeUnit.SECONDS);
        assertEquals("skill body", read.get("text").getAsString());

        AtomicInteger changes = new AtomicInteger();
        Runnable unsubscribe = session.onChange(change -> changes.incrementAndGet());
        try {
            CapabilityCatalog.register(new CapabilityDescriptor(CapabilityId.of("test", "session.extra"),
                            CapabilityKind.RESOURCE, CapabilityBinding.LOCAL, "extra", ToolSide.CLIENT,
                            ToolPermission.EVERYONE, "test", new JsonObject()),
                    (context, args) -> CompletableFuture.completedFuture(new JsonObject()));
            assertEquals(1, changes.get());
        } finally {
            unsubscribe.run();
        }
    }

    private record SessionTool() implements TianshuTool<JsonObject, JsonObject> {

        @Override
        public ToolId id() {
            return TOOL;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Agent session test tool.";
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
            JsonObject result = new JsonObject();
            result.addProperty("value", "ok");
            return CompletableFuture.completedFuture(result);
        }
    }
}
