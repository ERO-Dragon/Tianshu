package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.AgentSession;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who sees which capability: the provider's declaration and the consumer's subscription, both required.
 *
 * <p>Two failures are easy to ship and hard to notice, so both are asserted: a base row that a module cannot see (the
 * assistant loses its own tools), and a third-party row that a module sees without asking (installing two mods opens
 * both to each other).</p>
 */
class CapabilityVisibilityTest {

    private static final ToolCaller CONSUMER =
            ToolCaller.agent("changsui", false, ToolAutonomy.CONSERVATIVE);
    private static final ToolCaller BASE = ToolCaller.agent("tianshu", false, ToolAutonomy.CONSERVATIVE);

    private final List<CapabilityId> registered = new ArrayList<>();
    private final List<CapabilityId> registeredTools = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        registeredTools.forEach(id -> TianshuTools.unregister(ToolId.parse(id.toString())));
        registeredTools.clear();
        registered.forEach(CapabilityCatalog::unregister);
        registered.clear();
        CapabilitySubscriptions.clear();
    }

    @Test
    void anOpenBaseRowRequiresASubscription() {
        CapabilityDescriptor row = register("test:base-read", CapabilityKind.RESOURCE, "tianshu", null);

        assertEquals(CapabilitySharing.Scope.ALL, CapabilitySharing.of(row).scope(),
                "the base is what everything is built on: no declaration means open");
        assertFalse(visibleTo(CONSUMER, row));
    }

    @Test
    void aModuleRowIsPrivateUntilItSaysOtherwise() {
        CapabilityDescriptor row = register("test:private", CapabilityKind.RESOURCE, "modA", null);

        assertEquals(CapabilitySharing.Scope.NONE, CapabilitySharing.of(row).scope(),
                "an undeclared share must fail closed");
        assertFalse(visibleTo(CONSUMER, row));
    }

    @Test
    void bothSidesHaveToAgree() {
        JsonObject shareWithChangsui = new JsonObject();
        shareWithChangsui.addProperty("share", "changsui");
        CapabilityDescriptor shared = register("test:shared", CapabilityKind.RESOURCE, "modA", shareWithChangsui);

        assertFalse(visibleTo(CONSUMER, shared));

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modA"));
        assertTrue(visibleTo(CONSUMER, shared));

        // And the other direction: consumer asks, provider never opened.
        CapabilityDescriptor closed = register("test:closed", CapabilityKind.RESOURCE, "modB", null);
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modB"));
        assertFalse(visibleTo(CONSUMER, closed), "asking for something nobody shares gets nothing");
    }

    @Test
    void compositesAreNotSubscribedByDefault() {
        JsonObject open = new JsonObject();
        open.addProperty("share", "all");
        CapabilityDescriptor workflow = register("test:flow", CapabilityKind.WORKFLOW, "modA", open);

        assertEquals(CapabilitySubscription.DEFAULT, CapabilitySubscriptions.ruleFor("changsui"));
        assertFalse(visibleTo(CONSUMER, workflow), "someone else's sequencing does not appear unasked");

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.everythingFrom("modA"));
        assertTrue(visibleTo(CONSUMER, workflow));
    }

    @Test
    void atomicOnlySubscriptionsHideCompositesAndTheReverse() {
        JsonObject open = new JsonObject();
        open.addProperty("share", "all");
        CapabilityDescriptor atomic = register("test:atomic", CapabilityKind.RESOURCE, "modA", open);
        CapabilityDescriptor workflow = register("test:flow", CapabilityKind.WORKFLOW, "modA", open);

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modA"));
        assertTrue(visibleTo(CONSUMER, atomic));
        assertFalse(visibleTo(CONSUMER, workflow));

        CapabilitySubscriptions.declare("changsui",
                new CapabilitySubscription(java.util.Set.of(), java.util.Set.of("modA"), false, false, true));
        assertFalse(visibleTo(CONSUMER, atomic));
        assertTrue(visibleTo(CONSUMER, workflow));
    }

    @Test
    void namingToolsNarrowsTheSubscriptionToThem() {
        CapabilityDescriptor mine = registerTool("named.mine");
        CapabilityDescriptor move = registerTool("named.move");
        CapabilityDescriptor other = registerTool("named.other");

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.only(mine.id(), move.id()));

        assertTrue(visibleTo(CONSUMER, mine));
        assertTrue(visibleTo(CONSUMER, move));
        assertFalse(visibleTo(CONSUMER, other), "a named list is a whitelist, not a hint");
    }

    @Test
    void emptySelectionHidesAllExternalRows() {
        var tool = registerTool("named.empty");
        var skill = register("test:manual", CapabilityKind.SKILL, "tianshu", null);
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.only());
        assertFalse(visibleTo(CONSUMER, tool));
        assertFalse(visibleTo(CONSUMER, skill));
    }

    @Test
    void namingOneToolDoesNotSubscribeToAnIndependentSkill() {
        CapabilityDescriptor tool = registerTool("named.documented");
        CapabilityDescriptor skill = register("test:player-tools", CapabilityKind.SKILL, "tianshu", null);

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.only(tool.id()));

        assertTrue(visibleTo(CONSUMER, tool));
        assertFalse(visibleTo(CONSUMER, skill));
    }

    @Test
    void ordinaryToolsKeepTheirPublisherAndRequireBothSidesToOptIn() {
        var id = CapabilityId.parse("test:private-tool");
        TianshuTools.register(new NamedTool(id));
        registeredTools.add(id);
        var row = CapabilityCatalog.find(id).orElseThrow();
        assertEquals("test", row.provider());
        CapabilitySubscriptions.declare(CONSUMER.id(), CapabilitySubscription.only(id));
        row.attributes().addProperty("share", "all");
        assertFalse(visibleTo(CONSUMER, row));
        assertTrue(visibleTo(ToolCaller.mod("test"), row));
    }

    @Test
    void subscriptionAloneRefreshesBothSessionListsAndReportsTheRemovedRow() {
        var row = registerTool("named.change");
        var session = AgentSession.server(CONSUMER);
        long initial = session.describe().get("revision").getAsLong();
        List<CapabilityChange> changes = new ArrayList<>();
        Runnable unsubscribe = session.onChange(changes::add);
        try {
            CapabilitySubscriptions.declare(CONSUMER.id(), CapabilitySubscription.only(row.id()));
            var document = session.describe();
            assertTrue(document.get("revision").getAsLong() > initial);
            assertTrue(document.getAsJsonArray("tools").toString().contains(row.id().toString()));
            assertTrue(document.getAsJsonArray("capabilities").toString().contains(row.id().toString()));
            CapabilitySubscriptions.withdraw(CONSUMER.id());
            assertTrue(session.describe().get("revision").getAsLong() > document.get("revision").getAsLong());
            assertTrue(session.tools().isEmpty());
            assertEquals(List.of(new CapabilityChange(CapabilityChange.Action.ADDED, row),
                    new CapabilityChange(CapabilityChange.Action.REMOVED, row)), changes);
        } finally {
            unsubscribe.run();
        }
    }

    @Test
    void aNamedSelectionAppliesToEveryKind() {
        for (var kind : List.of(CapabilityKind.SKILL, CapabilityKind.RESOURCE, CapabilityKind.PROMPT,
                CapabilityKind.WORKFLOW)) {
            var row = register("test:" + kind.value(), kind, CapabilitySharing.USER_PROVIDER, null);
            CapabilitySubscriptions.declare(CONSUMER.id(), CapabilitySubscription.only(row.id()));
            assertTrue(visibleTo(CONSUMER, row));
            CapabilitySubscriptions.withdraw(CONSUMER.id());
            assertFalse(visibleTo(CONSUMER, row));
        }
    }

    @Test
    void callsAndDiscoveryUseTheSameSubscription() {
        var row = registerTool("named.calls");
        var id = ToolId.parse(row.id().toString());
        assertEquals("UNKNOWN_TOOL", TianshuTools.callJson(id, new JsonObject(), CONSUMER).join()
                .get("code").getAsString());
        assertEquals("UNKNOWN_TOOL", TianshuTools.callHandled(id, new JsonObject(), CONSUMER, ToolSide.CLIENT)
                .result().join().get("code").getAsString());
        CapabilitySubscriptions.declare(CONSUMER.id(), CapabilitySubscription.only(row.id()));
        assertTrue(TianshuTools.callJson(id, new JsonObject(), CONSUMER).join().get("ok").getAsBoolean());
    }

    @Test
    void namingARowTheProviderKeptPrivateYieldsNothing() {
        JsonObject closed = new JsonObject();
        closed.addProperty("share", "none");
        CapabilityDescriptor named = register("test:named-private", CapabilityKind.WORKFLOW, "modA", closed);

        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.only(named.id()));

        assertFalse(visibleTo(CONSUMER, named), "naming is not a grant: the provider's half still decides");
    }

    @Test
    void theAuditCatchesANameNobodyPublishes() {
        CapabilityId ghost = CapabilityId.parse("test:ghost.tool");
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.only(ghost));

        List<String> problems = CapabilitySubscriptions.audit();

        assertTrue(problems.stream()
                        .anyMatch(line -> line.contains("test:ghost.tool") && line.contains("nothing publishes it")),
                problems.toString());
    }

    @Test
    void aModuleAlwaysSeesWhatItPublishedItself() {
        CapabilityDescriptor own = register("test:own", CapabilityKind.WORKFLOW, "changsui", null);

        assertTrue(visibleTo(ToolCaller.agent("changsui", false, ToolAutonomy.CONSERVATIVE), own),
                "a provider must be able to use what it published");
    }

    @Test
    void theBaseCallerSeesTheBaseRows() {
        CapabilityDescriptor base = register("test:base", CapabilityKind.RESOURCE, "tianshu", null);

        assertTrue(visibleTo(BASE, base), "the assistant must not lose the base's own capabilities");
    }

    @Test
    void aPlayerSessionIsNotNarrowedByModuleDeclarations() {
        CapabilityDescriptor privateRow = register("test:private", CapabilityKind.RESOURCE, "modA", null);

        assertTrue(visibleTo(ToolCaller.player("uuid", true), privateRow),
                "sharing is a module-to-module arrangement, not a restriction on the human's own session");
    }

    @Test
    void theAuditTellsAPromiseApartFromAMissingMod() {
        register("test:silent", CapabilityKind.RESOURCE, "modA", null);
        CapabilitySubscriptions.declare("changsui", CapabilitySubscription.atomicFrom("modA", "modGhost"));

        List<String> problems = CapabilitySubscriptions.audit();

        assertTrue(problems.stream().anyMatch(line -> line.contains("modA") && line.contains("shares nothing")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(line -> line.contains("modGhost") && line.contains("not installed")),
                problems.toString());
    }

    private static boolean visibleTo(ToolCaller caller, CapabilityDescriptor row) {
        return CapabilityExposure.exposed(caller, ToolSide.SERVER).stream()
                .anyMatch(exposed -> exposed.id().equals(row.id()));
    }

    /**
     * Registers a real tool, because a tool row is mirrored from {@link TianshuTools} and cannot be put in the table
     * directly. Its provider is the kernel, so it is open — which is what a base tool is.
     */
    private CapabilityDescriptor registerTool(String name) {
        CapabilityId id = CapabilityId.of("test", name);
        TianshuTools.registerBuiltin(new NamedTool(id));
        registeredTools.add(id);
        return CapabilityCatalog.find(id).orElseThrow();
    }

    private CapabilityDescriptor register(String id, CapabilityKind kind, String provider, JsonObject attributes) {
        CapabilityId capability = CapabilityId.parse(id);
        registered.add(capability);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(capability, kind, CapabilityBinding.LOCAL,
                "a test row", ToolSide.SERVER, ToolPermission.EVERYONE, provider,
                attributes == null ? new JsonObject() : attributes);
        CapabilityCatalog.register(descriptor, (context, args) -> CompletableFuture.completedFuture(new JsonObject()));
        return descriptor;
    }

    @Test
    void invokingWhatYouCannotSeeIsUnknownRatherThanForbidden() {
        CapabilityDescriptor closed = register("test:invoke-closed", CapabilityKind.RESOURCE, "modB", null);

        var answer = CapabilityCatalog.invoke(closed.id(), new com.google.gson.JsonObject(), CONSUMER,
                ToolSide.CLIENT).join();

        // Same answer as "no such capability": a caller that cannot see it must not learn that it exists, and a
        // "forbidden" would be exactly that.
        assertEquals("UNKNOWN_CAPABILITY", answer.get("code").getAsString(), answer.toString());
    }

    /** The smallest tool that can be named in a subscription. */
    private static final class NamedTool implements com.rheinmetal.tianshu.api.tool.TianshuTool<Object, Object> {

        private final ToolId id;

        private NamedTool(CapabilityId id) {
            this.id = ToolId.parse(id.toString());
        }

        @Override
        public ToolId id() {
            return id;
        }

        @Override
        public com.rheinmetal.tianshu.api.tool.ToolCategory category() {
            return com.rheinmetal.tianshu.api.tool.ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "a tool that exists to be named";
        }

        @Override
        public java.lang.reflect.Type argsType() {
            return Object.class;
        }

        @Override
        public java.lang.reflect.Type resultType() {
            return Object.class;
        }

        @Override
        public CompletableFuture<Object> invoke(com.rheinmetal.tianshu.api.tool.ToolContext context, Object args) {
            return CompletableFuture.completedFuture(new Object());
        }
    }
}
