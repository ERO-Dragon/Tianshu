package com.rheinmetal.tianshu.lab.acceptance;

import com.rheinmetal.tianshu.lab.LabModuleRegistration;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.agent.AgentSession;
import com.rheinmetal.tianshu.api.capability.CapabilityBinding;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityExposure;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.api.tool.ToolSpec;

import com.rheinmetal.tianshu.api.entity.EntityActor;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.host.game.GameInventoryEntry;
import com.rheinmetal.tianshu.model.ModelDownloadHttpClient;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolCapabilityRegistration;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.lab.LabHostTrace;
import com.rheinmetal.tianshu.lab.LabModule;
import com.rheinmetal.tianshu.lab.LabProtocolAdapter;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSessionBuilder;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The bench's acceptance suite: the properties of the platform that can be checked from inside the running client,
 * without a second player, a dedicated server, or an ear.
 *
 * <p>Every case is reversible. The ones that need a switched-off tool or a probe capability put the table back the
 * way they found it in a {@code finally}, because an acceptance run that leaves the session altered makes the next
 * manual check a lie about the state it is checking.</p>
 *
 * <p>What is <em>not</em> here is listed by {@link AcceptanceReport#uncovered()} with the reason and the place that
 * does cover it. Adding a case that pretends to check one of those would be worse than the gap.</p>
 */
public final class LabAcceptanceCases {

    private static final ToolId READ_TOOL = ToolId.of("tianshu", "capability.read");
    private static final CapabilityId READ_CAPABILITY = CapabilityId.parse(READ_TOOL.toString());
    private static final ToolId FULL_PROBE = ToolId.of("lab", "acceptance-full");
    private static final CapabilityId SKILL_PROBE = CapabilityId.of("lab", "acceptance-skill");
    private static final ToolId SESSION_PROBE = ToolId.of("lab", "acceptance-session");
    private static final ToolId STRICT_PROBE = ToolId.of("lab", "acceptance-strict");
    private static final CapabilityId SESSION_SKILL_PROBE = CapabilityId.of("lab", "acceptance-session-skill");
    private static final CapabilityId HANG_PROBE = CapabilityId.of("lab", "acceptance-hang");
    private static final CapabilityId LONG_PROBE = CapabilityId.of("lab", "acceptance-long");
    private static final CapabilityId FAILING_PROBE = CapabilityId.of("lab", "acceptance-failing");
    private static final String ENABLED = "enabled";
    private static final String CODE = "code";
    private static final String MESSAGE = "message";
    private static final long CALL_TIMEOUT_SECONDS = 5L;

    private LabAcceptanceCases() {
    }

    public static List<AcceptanceCase> all() {
        List<AcceptanceCase> cases = new ArrayList<>();
        cases.add(twoViewsAgree());
        cases.add(disabledToolStaysVisible());
        cases.add(fullTierIsHiddenFromAConservativeCaller());
        cases.add(serverToolsHiddenWhileNotConnected());
        cases.add(unknownFieldIsRejectedWithItsPath());
        cases.add(unknownCapabilityAndToolIdDiffer());
        cases.add(publishedSchemasDeclareStrictness());
        cases.add(theContractNamesEveryToolTheSessionLists());
        cases.add(anOlderSnapshotKeepsItsOwnRows());
        cases.add(theReadToolListsWithoutAnId());
        cases.add(aThrowingChangeListenerDoesNotStarveTheOthers());
        cases.add(anUnknownCapabilityIsAnEnvelopeNotAnException());
        cases.add(aFailingHandlerBecomesAFailedEnvelope());
        cases.add(toolIdsRoundTripThroughTheSharedGrammar());
        cases.add(everyToolRowIsUsableByAConsumer());
        cases.add(flippingAToolReachesTheListeners());
        cases.add(higherTiersOnlyEverAddTools());
        cases.add(twoReadsOfTheTableAgree());
        cases.add(aNullAttributeValueIsNormalized());
        cases.add(everyCapabilityKindResolves());
        cases.add(aDuplicateRegistrationIsRefused());
        cases.add(aFailedEnvelopeHasNoText());
        cases.add(anEventWithoutSeverityIsRefused());
        cases.add(everyBodyToolIsReachableAndAnswers());
        cases.add(aModuleTaskRunsAndItsLaneSurvivesAFailure());
        cases.add(theHostAssemblesAnOutsideModuleOnce());
        cases.add(aModuleReachesItsOwnCapabilityThroughTheCentre());
        cases.add(aBodyCoordinateOutsideTheWorldIsRefused());
        cases.add(aBodyThatFailsVerificationIsNeverPromoted());
        cases.add(aCorruptBodyIsRetriedBeforeTheInstallFails());
        cases.add(theGameContextDescribesALoadedWorld());
        cases.add(theGameContextCapturesDoNotThrow());
        cases.add(aPlayerCommandsTheBodyItOwns());
        cases.add(aClientModuleCannotDriveAnNpc());
        cases.add(aBodyRefusesAnUnregisteredBody());
        cases.add(theAdapterTakesTheBodyTypeDeclarationsAtStartup());
        cases.add(anActionReachesTheServerAndComesBackExecuted());
        cases.add(anActionIsRefusedByTheServerSwitchWhenItsOff());
        cases.add(anActionWithBadArgumentsIsRefusedByTheServerSideValidation());
        cases.add(theOpenContainerComesBackAsAValue());
        cases.add(handingItemsOverNeedsAReceiver());
        cases.add(aPlacementLineNeedsAKnownDirection());
        cases.add(registeringMovesTheRevisionAndNotifies());
        cases.add(sessionComposesDiscoveryCallAndRead());
        cases.add(sessionDescribesItsContract());
        cases.add(sessionStopsNotifyingAfterUnsubscribe());
        cases.add(aCapabilityThatHangsIsCutOffByTheBudget());
        cases.add(theReadToolPassesThePageToTheProvider());
        cases.add(diagnosticEventsValidateTheirIdentity());
        cases.add(aSettingsSessionMustDeclareWhereItPersists());
        cases.add(deliberateFailure());
        return List.copyOf(cases);
    }

    /**
     * The catalogue the agent reads and the table a consumer renders must name the same tools.
     *
     * <p>They are two views of one table, filtered by the same permission/side/autonomy rules in two places. When
     * they drift, a model is told a capability exists and then refused it 鈥?the failure mode recorded as the MCP
     * dead-gate.</p>
     */
    private static AcceptanceCase twoViewsAgree() {
        final String id = "ACC-EXP-1";
        final String title = "the tool catalogue and the capability table list the same tools";
        return AcceptanceCase.of(id, title, () -> {
            ToolCaller caller = ToolCaller.player("lab", true);
            Set<String> catalogue = new TreeSet<>();
            for (ToolSpec spec : TianshuTools.catalog(caller, ToolSide.CLIENT)) {
                catalogue.add(spec.id().toString());
            }
            Set<String> exposed = new TreeSet<>();
            for (CapabilityDescriptor descriptor : CapabilityExposure.exposed(caller, ToolSide.CLIENT)) {
                if (descriptor.kind().equals(CapabilityKind.TOOL)) {
                    exposed.add(descriptor.id().toString());
                }
            }
            if (catalogue.isEmpty()) {
                return AcceptanceOutcome.skip(id, title,
                        "no tool is registered yet; two empty views would agree for the wrong reason");
            }
            if (catalogue.equals(exposed)) {
                return AcceptanceOutcome.pass(id, title, catalogue.size() + " tools, both views agree");
            }
            Set<String> onlyCatalogue = new TreeSet<>(catalogue);
            onlyCatalogue.removeAll(exposed);
            Set<String> onlyTable = new TreeSet<>(exposed);
            onlyTable.removeAll(catalogue);
            return AcceptanceOutcome.fail(id, title,
                    "only in the tool catalogue: " + onlyCatalogue + "; only in the capability table: " + onlyTable);
        });
    }

    /**
     * A switched-off tool stays discoverable and refuses the call with {@code DISABLED}.
     *
     * <p>Both halves matter: hiding it makes a model claim the capability does not exist, and reporting it but
     * executing it anyway would be worse. The switch is restored whether the case passes or not.</p>
     */
    private static AcceptanceCase disabledToolStaysVisible() {
        final String id = "ACC-EXP-2";
        final String title = "a switched-off tool stays listed as disabled and its call reports DISABLED";
        return AcceptanceCase.of(id, title, () -> {
            ToolCaller caller = ToolCaller.player("lab", true);
            TianshuTools.setEnabled(READ_TOOL, false);
            try {
                boolean listedInCatalogue = false;
                for (ToolSpec spec : TianshuTools.catalog(caller, ToolSide.CLIENT)) {
                    if (spec.id().equals(READ_TOOL) && !spec.enabled()) {
                        listedInCatalogue = true;
                    }
                }
                boolean listedInTable = false;
                for (CapabilityDescriptor descriptor : CapabilityExposure.exposed(caller, ToolSide.CLIENT)) {
                    if (!descriptor.id().equals(READ_CAPABILITY)) {
                        continue;
                    }
                    JsonElement enabled = descriptor.attributes().get(ENABLED);
                    listedInTable = enabled != null && enabled.isJsonPrimitive() && !enabled.getAsBoolean();
                }
                JsonObject args = new JsonObject();
                args.addProperty("id", "tianshu:game-basics");
                String code = codeOf(call(READ_TOOL, args, caller));
                if (listedInCatalogue && listedInTable && "DISABLED".equals(code)) {
                    return AcceptanceOutcome.pass(id, title,
                            "listed with enabled=false in both views; the call returned DISABLED");
                }
                return AcceptanceOutcome.fail(id, title, "catalogue=" + listedInCatalogue
                        + " table=" + listedInTable + " call=" + code);
            } finally {
                TianshuTools.clearEnabledOverride(READ_TOOL);
            }
        });
    }

    /**
     * A tool that declares the FULL tier must not reach a caller whose tier is conservative.
     *
     * <p>This is the whole point of the tier: a module can ask for the survival tier and the platform has to honour
     * it without that module doing its own filtering. The probe tool exists only for the duration of the case.</p>
     */
    private static AcceptanceCase fullTierIsHiddenFromAConservativeCaller() {
        final String id = "ACC-EXP-3";
        final String title = "a FULL-tier tool is invisible to a conservative caller and visible to a FULL one";
        return AcceptanceCase.of(id, title, () -> {
            TianshuTools.register(new FullProbeTool());
            try {
                ToolCaller conservative = ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.CONSERVATIVE);
                ToolCaller full = ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.FULL);
                boolean hidden = !lists(conservative, FULL_PROBE);
                boolean shown = lists(full, FULL_PROBE);
                if (hidden && shown) {
                    return AcceptanceOutcome.pass(id, title,
                            "conservative caller: absent; FULL caller: present");
                }
                return AcceptanceOutcome.fail(id, title,
                        "conservative caller saw it: " + !hidden + "; FULL caller saw it: " + shown);
            } finally {
                TianshuTools.unregister(FULL_PROBE);
            }
        });
    }

    /**
     * Server-side rows reach a client caller exactly when the server channel is available.
     *
     * <p>Written as an equivalence rather than "absent while disconnected", because a development client has the
     * channel up from the first tick: the earlier shape could only ever skip here, which is a permanent green light
     * that produces no information. When no server row exists on this side at all, the case still skips — but now
     * that is a statement about the table, not about a state nobody can reach.</p>
     */
    private static AcceptanceCase serverToolsHiddenWhileNotConnected() {
        final String id = "ACC-EXP-4";
        final String title = "server rows reach a client caller exactly when the server channel is available";
        return AcceptanceCase.of(id, title, () -> {
            boolean available = TianshuTools.serverAvailable();
            boolean anyServerRow = CapabilityCatalog.snapshot().descriptors().stream()
                    .anyMatch(descriptor -> descriptor.side() == ToolSide.SERVER);
            if (!anyServerRow) {
                return AcceptanceOutcome.skip(id, title,
                        "no server-side capability is registered on this side, so the filter has nothing to decide");
            }
            ToolCaller caller = ToolCaller.player("lab", true);
            int exposedServerRows = 0;
            for (CapabilityDescriptor descriptor : CapabilityExposure.exposed(caller, ToolSide.CLIENT)) {
                if (descriptor.side() == ToolSide.SERVER) {
                    exposedServerRows++;
                }
            }
            boolean consistent = available == (exposedServerRows > 0);
            if (consistent) {
                return AcceptanceOutcome.pass(id, title, "channel available=" + available
                        + ", server rows offered=" + exposedServerRows);
            }
            return AcceptanceOutcome.fail(id, title, "channel available=" + available
                    + " but server rows offered=" + exposedServerRows);
        });
    }

    /**
     * The validator honours a schema that declares its strictness.
     *
     * <p>Scoped to a probe tool on purpose. A sweep over every registered tool would have to send junk arguments to
     * write tools, and the platform's actual rule is "the schema decides": tools that do not declare
     * {@code additionalProperties: false} tolerate extra fields, which is a policy question recorded as an open
     * finding rather than a case that fails every run.</p>
     */
    private static AcceptanceCase unknownFieldIsRejectedWithItsPath() {
        final String id = "ACC-ARG-1";
        final String title = "a strict schema makes the validator refuse an undeclared field and name it";
        return AcceptanceCase.of(id, title, () -> {
            TianshuTools.register(new StrictProbeTool());
            try {
                JsonObject args = new JsonObject();
                args.addProperty("value", "kept");
                args.addProperty("bogus", true);
                JsonObject envelope = call(STRICT_PROBE, args, ToolCaller.player("lab", true));
                String code = codeOf(envelope);
                String message = messageOf(envelope);
                if ("INVALID_ARGS".equals(code) && message.contains("bogus")) {
                    return AcceptanceOutcome.pass(id, title, "INVALID_ARGS naming bogus");
                }
                return AcceptanceOutcome.fail(id, title, "code=" + code + " message=" + message);
            } finally {
                TianshuTools.unregister(STRICT_PROBE);
            }
        });
    }

    /**
     * Two rejections that must stay distinct: an id nobody registered, and a tool id handed to the reader.
     *
     * <p>Collapsing them into one code costs the model the difference between "this does not exist, stop asking" and
     * "you used the wrong door".</p>
     */
    private static AcceptanceCase unknownCapabilityAndToolIdDiffer() {
        final String id = "ACC-ARG-2";
        final String title = "an unknown capability and a tool id are refused with different codes";
        return AcceptanceCase.of(id, title, () -> {
            ToolCaller caller = ToolCaller.player("lab", true);
            JsonObject unknownArgs = new JsonObject();
            unknownArgs.addProperty("id", "tianshu:there-is-no-such-capability");
            JsonObject toolArgs = new JsonObject();
            toolArgs.addProperty("id", READ_TOOL.toString());
            String unknown = codeOf(call(READ_TOOL, unknownArgs, caller));
            String tool = codeOf(call(READ_TOOL, toolArgs, caller));
            if ("UNKNOWN_TOOL".equals(unknown) && "INVALID_ARGS".equals(tool)) {
                return AcceptanceOutcome.pass(id, title, "unknown id -> UNKNOWN_TOOL; tool id -> INVALID_ARGS");
            }
            return AcceptanceOutcome.fail(id, title,
                    "unknown id -> " + unknown + "; tool id -> " + tool);
        });
    }

    /**
     * Registering and removing a capability moves the revision and reaches in-process listeners.
     *
     * <p>The revision is what lets a consumer decide to refresh; a table that changes without moving it makes every
     * cached view silently stale. The probe capability is removed in the same case, so the run leaves nothing.</p>
     */
    private static AcceptanceCase registeringMovesTheRevisionAndNotifies() {
        final String id = "ACC-CAT-1";
        final String title = "registering and removing a capability moves the revision and notifies listeners";
        return AcceptanceCase.of(id, title, () -> {
            CapabilityCatalog.unregister(SKILL_PROBE);
            long before = CapabilityCatalog.revision();
            List<String> seen = new CopyOnWriteArrayList<>();
            Runnable unsubscribe = CapabilityCatalog.subscribe(change -> {
                if (SKILL_PROBE.equals(change.descriptor().id())) {
                    seen.add(change.action().name());
                }
            });
            try {
                CapabilityCatalog.register(new CapabilityDescriptor(
                                SKILL_PROBE, CapabilityKind.SKILL, CapabilityBinding.LOCAL,
                                "Registered by the lab acceptance run", ToolSide.CLIENT,
                                ToolPermission.EVERYONE, "lab", new JsonObject()),
                        (context, args) -> CompletableFuture.completedFuture(textEnvelope("acceptance skill body")));
                long afterRegister = CapabilityCatalog.revision();
                String text = CapabilityExposure.textOf(CapabilityCatalog
                        .invoke(SKILL_PROBE, new JsonObject(), ToolCaller.player("lab", true), ToolSide.CLIENT)
                        .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                CapabilityCatalog.unregister(SKILL_PROBE);
                long afterRemove = CapabilityCatalog.revision();
                boolean moved = afterRegister > before && afterRemove > afterRegister;
                boolean notified = seen.contains("ADDED") && seen.contains("REMOVED");
                boolean readable = text.contains("acceptance skill body");
                if (moved && notified && readable) {
                    return AcceptanceOutcome.pass(id, title,
                            "revision " + before + " -> " + afterRegister + " -> " + afterRemove
                                    + "; events " + seen);
                }
                return AcceptanceOutcome.fail(id, title, "revisionMoved=" + moved + " notified=" + notified
                        + " readable=" + readable + " events=" + seen);
            } finally {
                unsubscribe.run();
                CapabilityCatalog.unregister(SKILL_PROBE);
            }
        });
    }

    /**
     * The case that proves the bench can fail.
     *
     * <p>It must report FAIL. If it ever passes, {@link AcceptanceRunner} voids the whole run 鈥?a suite whose only
     * known-bad case comes back green is measuring nothing, which is exactly what four of the build gates were doing
     * before a mutation probe caught them.</p>
     */
    private static AcceptanceCase deliberateFailure() {
        final String id = AcceptanceReport.SELF_CHECK_ID;
        final String title = "the bench can detect a failure: this case must fail";
        return AcceptanceCase.of(id, title, () -> AcceptanceOutcome.fail(id, title,
                "deliberate failure; a PASS here means the runner cannot tell a broken case from a working one"));
    }

    /**
     * The agent facade composes discovery, invocation and reading over the live table.
     *
     * <p>The matrix listed this as the largest gap: every feature module touches {@code AgentSession} and nothing had
     * ever run it against the real registry. A probe tool and a probe skill exist only for the duration of the case.</p>
     */
    private static AcceptanceCase sessionComposesDiscoveryCallAndRead() {
        final String id = "ACC-AGT-1";
        final String title = "the agent session discovers, calls and reads through the live registry";
        return AcceptanceCase.of(id, title, () -> {
            TianshuTools.register(new SessionProbeTool());
            registerProbe(SESSION_SKILL_PROBE, CapabilityKind.SKILL, "agent probe body");
            try {
                AgentSession session = AgentSession.client(ToolCaller.agent(LabModuleRegistration.MOD_ID, false, ToolCaller.DEFAULT_AUTONOMY));
                boolean discovered = session.tools().stream()
                        .anyMatch(spec -> spec.id().equals(SESSION_PROBE));
                JsonObject call = session.call(SESSION_PROBE, new JsonObject())
                        .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                JsonObject read = session.read(SESSION_SKILL_PROBE, new JsonObject())
                        .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                boolean called = call.has("ok") && call.get("ok").getAsBoolean();
                boolean readOk = "agent probe body".equals(CapabilityExposure.textOf(read));
                if (discovered && called && readOk) {
                    return AcceptanceOutcome.pass(id, title, "discovered, called and read through one session");
                }
                return AcceptanceOutcome.fail(id, title, "discovered=" + discovered
                        + " called=" + called + " read=" + readOk);
            } finally {
                TianshuTools.unregister(SESSION_PROBE);
                CapabilityCatalog.unregister(SESSION_SKILL_PROBE);
            }
        });
    }

    /**
     * The session's published contract keeps the wire protocol name and the in-flight limit the channels enforce.
     *
     * <p>The limit is read from the document rather than from the channel on purpose: a consumer sizes its own
     * batching from this number, so it has to be the number the channel really applies.</p>
     */
    private static AcceptanceCase sessionDescribesItsContract() {
        final String id = "ACC-AGT-2";
        final String title = "the session contract names the protocol and the in-flight limit";
        return AcceptanceCase.of(id, title, () -> {
            TianshuTools.register(new SessionProbeTool());
            try {
                JsonObject contract = AgentSession.client(ToolCaller.agent(LabModuleRegistration.MOD_ID, false, ToolCaller.DEFAULT_AUTONOMY)).describe();
                String protocol = contract.has("protocol") ? contract.get("protocol").getAsString() : "";
                int limit = contract.getAsJsonObject("limits").get("maxInFlightPerCaller").getAsInt();
                boolean namesProbe = contract.getAsJsonArray("tools").toString().contains(SESSION_PROBE.toString());
                if (AgentSession.PROTOCOL.equals(protocol) && limit == 8 && namesProbe) {
                    return AcceptanceOutcome.pass(id, title,
                            "protocol=" + protocol + " maxInFlightPerCaller=" + limit + ", probe listed");
                }
                return AcceptanceOutcome.fail(id, title, "protocol=" + protocol + " limit=" + limit
                        + " namesProbe=" + namesProbe);
            } finally {
                TianshuTools.unregister(SESSION_PROBE);
            }
        });
    }

    /** A subscription must stop delivering once it is cancelled, or every consumer leaks a listener per session. */
    private static AcceptanceCase sessionStopsNotifyingAfterUnsubscribe() {
        final String id = "ACC-AGT-3";
        final String title = "a session stops notifying a change listener after it unsubscribes";
        return AcceptanceCase.of(id, title, () -> {
            AgentSession session = AgentSession.client(ToolCaller.agent(LabModuleRegistration.MOD_ID, false, ToolCaller.DEFAULT_AUTONOMY));
            java.util.concurrent.atomic.AtomicInteger delivered = new java.util.concurrent.atomic.AtomicInteger();
            Runnable unsubscribe = session.onChange(change -> delivered.incrementAndGet());
            try {
                registerProbe(SESSION_SKILL_PROBE, CapabilityKind.SKILL, "one");
                if (delivered.get() == 0) {
                    return AcceptanceOutcome.fail(id, title, "the subscription never received the first change");
                }
                unsubscribe.run();
                int mark = delivered.get();
                CapabilityCatalog.unregister(SESSION_SKILL_PROBE);
                registerProbe(SESSION_SKILL_PROBE, CapabilityKind.SKILL, "two");
                if (delivered.get() == mark) {
                    return AcceptanceOutcome.pass(id, title,
                            "first change delivered, nothing delivered after unsubscribe");
                }
                return AcceptanceOutcome.fail(id, title,
                        (delivered.get() - mark) + " change(s) delivered after unsubscribing");
            } finally {
                unsubscribe.run();
                CapabilityCatalog.unregister(SESSION_SKILL_PROBE);
            }
        });
    }

    /**
     * A handler that never answers must be cut off by the five-second budget instead of pinning the caller.
     *
     * <p>This is what makes the budget real: the handler runs on the capability pool precisely so the caller's
     * future can time out when the handler does not return one of its own.</p>
     */
    private static AcceptanceCase aCapabilityThatHangsIsCutOffByTheBudget() {
        final String id = "ACC-CAP-1";
        final String title = "a capability that never answers is cut off by the five-second budget";
        return AcceptanceCase.of(id, title, () -> {
            CapabilityCatalog.unregister(HANG_PROBE);
            CapabilityCatalog.register(probeDescriptor(HANG_PROBE, CapabilityKind.SKILL, "never answers"),
                    (context, args) -> new CompletableFuture<>());
            long start = System.nanoTime();
            try {
                JsonObject envelope = CapabilityCatalog
                        .invoke(HANG_PROBE, new JsonObject(), ToolCaller.player("lab", true), ToolSide.CLIENT)
                        .get(20, TimeUnit.SECONDS);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                boolean refused = !envelope.has("ok") || !envelope.get("ok").getAsBoolean();
                boolean inBudget = elapsedMs >= 4_500L && elapsedMs <= 12_000L;
                if (refused && inBudget) {
                    return AcceptanceOutcome.pass(id, title,
                            "returned code " + codeOf(envelope) + " after " + elapsedMs + " ms");
                }
                return AcceptanceOutcome.fail(id, title,
                        "refused=" + refused + " elapsedMs=" + elapsedMs + " envelope=" + envelope);
            } finally {
                CapabilityCatalog.unregister(HANG_PROBE);
            }
        });
    }

    /**
     * The read tool hands the requested page to the provider, unchanged.
     *
     * <p>Only the plumbing is checked here, on purpose: the navigation footer belongs to the providers'
     * {@code TextPages} helper and is tested with it. This case covers the plumbing only: the tool's {@code page} argument survives the boundary as a provider argument rather than being dropped or rewritten.</p>
     */
    private static AcceptanceCase theReadToolPassesThePageToTheProvider() {
        final String id = "ACC-CAP-2";
        final String title = "the read tool hands the requested page to the provider";
        return AcceptanceCase.of(id, title, () -> {
            CapabilityCatalog.unregister(LONG_PROBE);
            CapabilityCatalog.register(probeDescriptor(LONG_PROBE, CapabilityKind.RESOURCE, "a paged text"),
                    (context, args) -> {
                        JsonObject envelope = new JsonObject();
                        envelope.addProperty("ok", true);
                        envelope.addProperty("text", args.has("page")
                                ? "received page " + args.get("page").getAsInt() : "received no page");
                        return CompletableFuture.completedFuture(envelope);
                    });
            try {
                String first = resultText(readPage(1));
                String second = resultText(readPage(2));
                if (first.contains("received page 1") && second.contains("received page 2")) {
                    return AcceptanceOutcome.pass(id, title, "page 1 and page 2 both reached the provider");
                }
                return AcceptanceOutcome.fail(id, title, "page 1 gave \"" + first + "\", page 2 gave \"" + second + "\"");
            } finally {
                CapabilityCatalog.unregister(LONG_PROBE);
            }
        });
    }

    /**
     * A diagnostic event must refuse a blank identity and keep its attributes.
     *
     * <p>The bench relies on this itself: every run publishes {@code acceptance.run}, and an event that silently lost
     * its code or its attributes would leave a green run with no evidence that it happened.</p>
     */
    private static AcceptanceCase diagnosticEventsValidateTheirIdentity() {
        final String id = "ACC-DIAG-1";
        final String title = "a diagnostic event refuses a blank identity and keeps its attributes";
        return AcceptanceCase.of(id, title, () -> {
            DiagnosticEvent event = DiagnosticEvent.now(LabModule.MODULE_ID, "acceptance.probe",
                    DiagnosticSeverity.INFO, DiagnosticPrivacy.PUBLIC, Map.of("k", "v"));
            boolean kept = "v".equals(event.attributes().get("k"));
            boolean blankCodeRefused = refusesIllegalArgument(() -> DiagnosticEvent.now(LabModule.MODULE_ID, " ",
                    DiagnosticSeverity.INFO, DiagnosticPrivacy.PUBLIC, Map.of()));
            boolean blankKeyRefused = refusesIllegalArgument(() -> DiagnosticEvent.now(LabModule.MODULE_ID,
                    "acceptance.probe", DiagnosticSeverity.INFO, DiagnosticPrivacy.PUBLIC, Map.of(" ", "v")));
            if (kept && blankCodeRefused && blankKeyRefused) {
                return AcceptanceOutcome.pass(id, title, "attributes kept; blank code and blank key refused");
            }
            return AcceptanceOutcome.fail(id, title, "attributesKept=" + kept
                    + " blankCodeRefused=" + blankCodeRefused + " blankKeyRefused=" + blankKeyRefused);
        });
    }

    /**
     * A settings session must declare where its values reach disk.
     *
     * <p>Before this was mandatory, a module could build a session that reported "saved" while nothing was ever
     * written — the UI said success and a restart proved it wrong. The refusal has to keep naming the module, or the
     * module author reads "a session must declare persist" and still does not know which page is at fault.</p>
     */
    private static AcceptanceCase aSettingsSessionMustDeclareWhereItPersists() {
        final String id = "ACC-SET-1";
        final String title = "a settings session refuses to build without a persist step, naming the module";
        return AcceptanceCase.of(id, title, () -> {
            String moduleId = "lab-acceptance-probe";
            boolean refused = false;
            String message = "";
            try {
                new ModuleSettingsSessionBuilder(moduleId).build();
            } catch (IllegalStateException expected) {
                refused = true;
                message = String.valueOf(expected.getMessage());
            }
            boolean built = true;
            try {
                new ModuleSettingsSessionBuilder(moduleId).persist(() -> {
                }).build();
            } catch (RuntimeException failure) {
                built = false;
            }
            if (refused && built && message.contains(moduleId)) {
                return AcceptanceOutcome.pass(id, title, "refused without persist and accepted with one");
            }
            return AcceptanceOutcome.fail(id, title, "refused=" + refused + " built=" + built
                    + " message=" + message);
        });
    }

    private static void registerProbe(CapabilityId probe, CapabilityKind kind, String text) {
        CapabilityCatalog.unregister(probe);
        CapabilityCatalog.register(probeDescriptor(probe, kind, "registered by the lab acceptance run"),
                (context, args) -> CompletableFuture.completedFuture(textEnvelope(text)));
    }

    private static CapabilityDescriptor probeDescriptor(CapabilityId probe, CapabilityKind kind, String description) {
        return new CapabilityDescriptor(probe, kind, CapabilityBinding.LOCAL, description, ToolSide.CLIENT,
                ToolPermission.EVERYONE, "lab", new JsonObject());
    }

    private static JsonObject readPage(int page) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("id", LONG_PROBE.toString());
        args.addProperty("page", page);
        return call(READ_TOOL, args, ToolCaller.player("lab", true));
    }

    private static String resultText(JsonObject envelope) {
        JsonElement result = envelope.get("result");
        if (result != null && result.isJsonObject()) {
            JsonElement text = result.getAsJsonObject().get("text");
            if (text != null && text.isJsonPrimitive()) {
                return text.getAsString();
            }
        }
        return CapabilityExposure.textOf(envelope);
    }


    private static boolean refusesIllegalArgument(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    /**
     * Every tool schema a model can read declares how it treats undeclared fields.
     *
     * <p>Reads the table, calls nothing — so it is safe to point at write tools. This is the lock on the platform rule
     * that the first in-game run exposed: the validator defaults to refusing undeclared fields, and the published
     * schema has to say so, or the document and the check disagree.</p>
     */
    private static AcceptanceCase publishedSchemasDeclareStrictness() {
        final String id = "ACC-ARG-3";
        final String title = "every published tool schema declares how it treats undeclared fields";
        return AcceptanceCase.of(id, title, () -> {
            int checked = 0;
            List<String> loose = new ArrayList<>();
            for (CapabilityDescriptor descriptor : CapabilityCatalog.list()) {
                if (!descriptor.kind().equals(CapabilityKind.TOOL)) {
                    continue;
                }
                JsonElement published = descriptor.attributes().get("argsSchema");
                if (published == null || !published.isJsonObject()) {
                    continue;
                }
                JsonObject schema = published.getAsJsonObject();
                if (!schema.has("properties")) {
                    continue;
                }
                checked++;
                if (!schema.has("additionalProperties")) {
                    loose.add(descriptor.id().toString());
                }
            }
            if (checked == 0) {
                return AcceptanceOutcome.skip(id, title, "no tool publishes an object argument schema");
            }
            if (loose.isEmpty()) {
                return AcceptanceOutcome.pass(id, title, checked + " schemas all declare their strictness");
            }
            return AcceptanceOutcome.fail(id, title, loose.size() + " of " + checked
                    + " do not declare it: " + loose.subList(0, Math.min(loose.size(), 5)));
        });
    }

    /**
     * The published contract names every tool the session lists.
     *
     * <p>Checked as containment, not equality: the contract is a document a consumer batches from, and a document that
     * silently drops a tool while {@code tools()} still returns it is the same two-views disagreement as ACC-EXP-1.</p>
     */
    private static AcceptanceCase theContractNamesEveryToolTheSessionLists() {
        final String id = "ACC-AGT-4";
        final String title = "the session contract names every tool the session itself lists";
        return AcceptanceCase.of(id, title, () -> {
            AgentSession session = AgentSession.client(ToolCaller.agent(LabModuleRegistration.MOD_ID, false, ToolCaller.DEFAULT_AUTONOMY));
            JsonObject contract = session.describe();
            if (!contract.has("tools") || !contract.get("tools").isJsonArray()) {
                return AcceptanceOutcome.fail(id, title, "the contract has no tools array");
            }
            String published = contract.getAsJsonArray("tools").toString();
            List<String> missing = new ArrayList<>();
            for (ToolSpec spec : session.tools()) {
                if (!published.contains(spec.id().toString())) {
                    missing.add(spec.id().toString());
                }
            }
            int listed = session.tools().size();
            if (listed == 0) {
                return AcceptanceOutcome.skip(id, title, "the session lists no tool at all");
            }
            if (missing.isEmpty()) {
                return AcceptanceOutcome.pass(id, title, listed + " tools all named in the contract");
            }
            return AcceptanceOutcome.fail(id, title, "missing from the contract: " + missing);
        });
    }

    /**
     * A snapshot a consumer already holds keeps the rows it was taken with.
     *
     * <p>This is what lets a consumer quote a revision and its content together. A mutable view here would make every
     * cached document describe a table that never existed at the revision it claims.</p>
     */
    private static AcceptanceCase anOlderSnapshotKeepsItsOwnRows() {
        final String id = "ACC-CAT-2";
        final String title = "a snapshot taken before a change keeps its own rows and revision";
        return AcceptanceCase.of(id, title, () -> {
            CapabilityCatalog.Snapshot before = CapabilityCatalog.snapshot();
            int sizeBefore = before.descriptors().size();
            registerProbe(SKILL_PROBE, CapabilityKind.SKILL, "snapshot probe");
            try {
                boolean keptSize = before.descriptors().size() == sizeBefore;
                boolean keptRows = before.descriptors().stream()
                        .noneMatch(descriptor -> descriptor.id().equals(SKILL_PROBE));
                CapabilityCatalog.Snapshot after = CapabilityCatalog.snapshot();
                if (keptSize && keptRows && after.revision() > before.revision()) {
                    return AcceptanceOutcome.pass(id, title,
                            "revision " + before.revision() + " kept " + sizeBefore + " rows");
                }
                return AcceptanceOutcome.fail(id, title, "keptSize=" + keptSize + " keptRows=" + keptRows
                        + " revisionMoved=" + (after.revision() > before.revision()));
            } finally {
                CapabilityCatalog.unregister(SKILL_PROBE);
            }
        });
    }

    /** Called with no id, the read tool answers with a listing instead of a result. */
    private static AcceptanceCase theReadToolListsWithoutAnId() {
        final String id = "ACC-EXP-5";
        final String title = "the read tool answers a listing when it is called without an id";
        return AcceptanceCase.of(id, title, () -> {
            String listing = resultText(call(READ_TOOL, new JsonObject(), ToolCaller.player("lab", true)));
            if (listing == null || listing.isBlank()) {
                return AcceptanceOutcome.fail(id, title, "the listing was empty");
            }
            boolean namesSomething = listing.contains("tianshu:");
            if (namesSomething) {
                return AcceptanceOutcome.pass(id, title, listing.length() + " characters of listing");
            }
            return AcceptanceOutcome.fail(id, title, "the listing names no capability: " + listing);
        });
    }

    /**
     * One listener that throws must not stop the others from being told.
     *
     * <p>Third-party consumers subscribe too; a broken one taking the notification path down would turn a broken
     * integration into a broken registry.</p>
     */
    private static AcceptanceCase aThrowingChangeListenerDoesNotStarveTheOthers() {
        final String id = "ACC-DIAG-2";
        final String title = "a throwing change listener does not stop the listener registered after it";
        return AcceptanceCase.of(id, title, () -> {
            java.util.concurrent.atomic.AtomicInteger delivered = new java.util.concurrent.atomic.AtomicInteger();
            Runnable exploding = CapabilityCatalog.subscribe(change -> {
                throw new IllegalStateException("listener exploded on purpose");
            });
            Runnable healthy = CapabilityCatalog.subscribe(change -> delivered.incrementAndGet());
            try {
                registerProbe(SKILL_PROBE, CapabilityKind.SKILL, "listener isolation probe");
                if (delivered.get() > 0) {
                    return AcceptanceOutcome.pass(id, title,
                            "the later listener was told " + delivered.get() + " time(s) despite the throw");
                }
                return AcceptanceOutcome.fail(id, title, "the throwing listener starved the one after it");
            } finally {
                exploding.run();
                healthy.run();
                CapabilityCatalog.unregister(SKILL_PROBE);
            }
        });
    }

    /** An id nobody registered answers with an envelope code, so a consumer reads a result instead of catching. */
    private static AcceptanceCase anUnknownCapabilityIsAnEnvelopeNotAnException() {
        final String id = "ACC-CAP-3";
        final String title = "invoking an unregistered capability answers UNKNOWN_CAPABILITY, not a throw";
        return AcceptanceCase.of(id, title, () -> {
            CapabilityId missing = CapabilityId.of("lab", "acceptance-missing");
            CapabilityCatalog.unregister(missing);
            JsonObject envelope = CapabilityCatalog
                    .invoke(missing, new JsonObject(), ToolCaller.player("lab", true), ToolSide.CLIENT)
                    .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String code = codeOf(envelope);
            if ("UNKNOWN_CAPABILITY".equals(code)) {
                return AcceptanceOutcome.pass(id, title, "UNKNOWN_CAPABILITY envelope");
            }
            return AcceptanceOutcome.fail(id, title, "code=" + code + " envelope=" + envelope);
        });
    }

    /**
     * A provider that fails becomes a failed envelope on the same path as a provider that succeeds.
     *
     * <p>Otherwise every consumer needs two error paths — one for a code and one for an exception — and the second one
     * is the one nobody writes.</p>
     */
    private static AcceptanceCase aFailingHandlerBecomesAFailedEnvelope() {
        final String id = "ACC-CAP-4";
        final String title = "a provider that fails is reported as a failed envelope";
        return AcceptanceCase.of(id, title, () -> {
            CapabilityCatalog.unregister(FAILING_PROBE);
            CapabilityCatalog.register(probeDescriptor(FAILING_PROBE, CapabilityKind.RESOURCE, "always fails"),
                    (context, args) -> CompletableFuture.failedFuture(new IllegalStateException("provider failed")));
            try {
                JsonObject envelope = CapabilityCatalog
                        .invoke(FAILING_PROBE, new JsonObject(), ToolCaller.player("lab", true), ToolSide.CLIENT)
                        .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                boolean failed = envelope.has("ok") && !envelope.get("ok").getAsBoolean();
                if (failed) {
                    return AcceptanceOutcome.pass(id, title, "failed envelope with code " + codeOf(envelope));
                }
                return AcceptanceOutcome.fail(id, title, "envelope=" + envelope);
            } finally {
                CapabilityCatalog.unregister(FAILING_PROBE);
            }
        });
    }

    /** Both id families share one grammar, so every registered tool id must survive a parse round trip. */
    private static AcceptanceCase toolIdsRoundTripThroughTheSharedGrammar() {
        final String id = "ACC-EXP-6";
        final String title = "every registered tool id round-trips through the shared id grammar";
        return AcceptanceCase.of(id, title, () -> {
            List<String> broken = new ArrayList<>();
            int checked = 0;
            for (CapabilityDescriptor descriptor : CapabilityCatalog.list()) {
                if (!descriptor.kind().equals(CapabilityKind.TOOL)) {
                    continue;
                }
                checked++;
                try {
                    if (!CapabilityId.parse(descriptor.id().toString()).equals(descriptor.id())) {
                        broken.add(descriptor.id().toString());
                    }
                } catch (RuntimeException rejected) {
                    broken.add(descriptor.id() + " (" + rejected.getClass().getSimpleName() + ")");
                }
            }
            if (checked == 0) {
                return AcceptanceOutcome.skip(id, title, "no tool is registered yet");
            }
            if (broken.isEmpty()) {
                return AcceptanceOutcome.pass(id, title, checked + " ids round-trip");
            }
            return AcceptanceOutcome.fail(id, title, "not parseable: " + broken);
        });
    }

    /** A consumer renders every row it is given; a blank description or provider makes one row unreadable. */
    private static AcceptanceCase everyToolRowIsUsableByAConsumer() {
        final String id = "ACC-EXP-7";
        final String title = "every tool row has a description and a provider a consumer can show";
        return AcceptanceCase.of(id, title, () -> {
            List<String> unusable = new ArrayList<>();
            int checked = 0;
            for (CapabilityDescriptor descriptor : CapabilityCatalog.list()) {
                if (!descriptor.kind().equals(CapabilityKind.TOOL)) {
                    continue;
                }
                checked++;
                boolean described = descriptor.description() != null && !descriptor.description().isBlank();
                boolean provided = descriptor.provider() != null && !descriptor.provider().isBlank();
                if (!described || !provided) {
                    unusable.add(descriptor.id() + (described ? "" : " (no description)")
                            + (provided ? "" : " (no provider)"));
                }
            }
            if (checked == 0) {
                return AcceptanceOutcome.skip(id, title, "no tool is registered yet");
            }
            if (unusable.isEmpty()) {
                return AcceptanceOutcome.pass(id, title, checked + " rows are renderable");
            }
            return AcceptanceOutcome.fail(id, title, "unusable rows: " + unusable);
        });
    }

    /**
     * Switching a tool off and back on reaches the listeners as updates.
     *
     * <p>The mirror in the table is what a consumer reads, so a switch that only changed the tool registry would leave
     * every cached view showing the old state — and the state restored here is whatever it was before the case.</p>
     */
    private static AcceptanceCase flippingAToolReachesTheListeners() {
        final String id = "ACC-CAT-3";
        final String title = "switching a tool off and on again reaches the change listeners";
        return AcceptanceCase.of(id, title, () -> {
            java.util.concurrent.atomic.AtomicInteger updates = new java.util.concurrent.atomic.AtomicInteger();
            Runnable unsubscribe = CapabilityCatalog.subscribe(change -> {
                if (change.descriptor().id().equals(READ_CAPABILITY)
                        && change.action() == com.rheinmetal.tianshu.api.capability.CapabilityChange.Action.UPDATED) {
                    updates.incrementAndGet();
                }
            });
            try {
                TianshuTools.setEnabled(READ_TOOL, false);
                TianshuTools.clearEnabledOverride(READ_TOOL);
                if (updates.get() >= 2) {
                    return AcceptanceOutcome.pass(id, title, updates.get() + " UPDATED events delivered");
                }
                return AcceptanceOutcome.fail(id, title, "only " + updates.get() + " UPDATED event(s) delivered");
            } finally {
                unsubscribe.run();
                TianshuTools.clearEnabledOverride(READ_TOOL);
            }
        });
    }

    /**
     * A wider tier never hides a tool a narrower one could see.
     *
     * <p>The tiers are ordered for a reason: a module that grants itself more autonomy must not lose capabilities it
     * had, or every decision to widen becomes a trade the author cannot predict.</p>
     */
    private static AcceptanceCase higherTiersOnlyEverAddTools() {
        final String id = "ACC-EXP-8";
        final String title = "a wider autonomy tier only ever adds tools, never removes them";
        return AcceptanceCase.of(id, title, () -> {
            Set<String> readOnly = listedIds(ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.READ_ONLY));
            Set<String> conservative = listedIds(ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.CONSERVATIVE));
            Set<String> full = listedIds(ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.FULL));
            if (readOnly.isEmpty()) {
                return AcceptanceOutcome.skip(id, title, "no tool is visible even at the widest tier");
            }
            Set<String> missingFromConservative = new TreeSet<>(readOnly);
            missingFromConservative.removeAll(conservative);
            Set<String> missingFromFull = new TreeSet<>(conservative);
            missingFromFull.removeAll(full);
            if (missingFromConservative.isEmpty() && missingFromFull.isEmpty()) {
                return AcceptanceOutcome.pass(id, title, "read-only " + readOnly.size()
                        + " ⊆ conservative " + conservative.size() + " ⊆ full " + full.size());
            }
            return AcceptanceOutcome.fail(id, title, "lost when widening: " + missingFromConservative
                    + " then " + missingFromFull);
        });
    }

    /** Reading the table must not change it; two identical reads are the cheapest proof of that. */
    private static AcceptanceCase twoReadsOfTheTableAgree() {
        final String id = "ACC-EXP-9";
        final String title = "two consecutive reads of the table agree and change nothing";
        return AcceptanceCase.of(id, title, () -> {
            ToolCaller caller = ToolCaller.player("lab", true);
            long firstRevision = CapabilityCatalog.revision();
            Set<String> first = listedIds(caller);
            Set<String> exposedFirst = new TreeSet<>();
            for (CapabilityDescriptor descriptor : CapabilityExposure.exposed(caller, ToolSide.CLIENT)) {
                exposedFirst.add(descriptor.id().toString());
            }
            Set<String> second = listedIds(caller);
            long secondRevision = CapabilityCatalog.revision();
            if (first.isEmpty()) {
                return AcceptanceOutcome.skip(id, title, "no tool is registered yet");
            }
            boolean stable = first.equals(second);
            boolean untouched = firstRevision == secondRevision;
            boolean exposureStable = exposedFirst.containsAll(second);
            if (stable && untouched && exposureStable) {
                return AcceptanceOutcome.pass(id, title,
                        first.size() + " ids stable across two reads, revision " + firstRevision);
            }
            return AcceptanceOutcome.fail(id, title, "stable=" + stable + " untouched=" + untouched
                    + " exposureCoversCatalogue=" + exposureStable);
        });
    }

    /** A null attribute value is normalised, not carried as null into the diagnostics writer. */
    private static AcceptanceCase aNullAttributeValueIsNormalized() {
        final String id = "ACC-DIAG-3";
        final String title = "a null diagnostic attribute value is normalised to an empty string";
        return AcceptanceCase.of(id, title, () -> {
            java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
            attributes.put("present", "value");
            attributes.put("absent", null);
            DiagnosticEvent event = DiagnosticEvent.now(LabModule.MODULE_ID, "acceptance.probe.null",
                    DiagnosticSeverity.INFO, DiagnosticPrivacy.PUBLIC, attributes);
            String normalized = event.attributes().get("absent");
            if ("".equals(normalized) && "value".equals(event.attributes().get("present"))) {
                return AcceptanceOutcome.pass(id, title, "null became \"\", the other value was kept");
            }
            return AcceptanceOutcome.fail(id, title, "normalized=" + normalized
                    + " attributes=" + event.attributes());
        });
    }

    /**
     * A skill, a resource and a prompt each resolve through the one read tool, and a prompt's arguments reach it.
     *
     * <p>One tool serves three kinds; a kind that cannot be read is a capability a model is told exists and cannot
     * use. The prompt pair is there because rendering is the only kind-specific work left in that path.</p>
     */
    private static AcceptanceCase everyCapabilityKindResolves() {
        final String id = "ACC-CAP-5";
        final String title = "a skill, a resource and a prompt all resolve, and prompt arguments change the text";
        return AcceptanceCase.of(id, title, () -> {
            String skill = resultText(readCapability("tianshu:minecraft-tools", null));
            String resource = resultText(readCapability("tianshu:game-basics", null));
            JsonObject twoLines = new JsonObject();
            twoLines.addProperty("lines", "2");
            JsonObject fiveLines = new JsonObject();
            fiveLines.addProperty("lines", "5");
            String shortPrompt = resultText(readCapability("tianshu:concise", twoLines));
            String longPrompt = resultText(readCapability("tianshu:concise", fiveLines));
            boolean skillOk = !skill.isBlank();
            boolean resourceOk = !resource.isBlank();
            boolean promptTemplated = !shortPrompt.isBlank() && !longPrompt.isBlank()
                    && !shortPrompt.equals(longPrompt);
            if (skillOk && resourceOk && promptTemplated) {
                return AcceptanceOutcome.pass(id, title, "skill " + skill.length() + "c, resource "
                        + resource.length() + "c, prompt varies with its argument");
            }
            return AcceptanceOutcome.fail(id, title, "skill=" + skillOk + " resource=" + resourceOk
                    + " promptTemplated=" + promptTemplated);
        });
    }

    /** Two capabilities under one id would make the id ambiguous; the table has to refuse the second one. */
    private static AcceptanceCase aDuplicateRegistrationIsRefused() {
        final String id = "ACC-CAT-4";
        final String title = "registering one capability id twice is refused and the first row survives";
        return AcceptanceCase.of(id, title, () -> {
            registerProbe(SKILL_PROBE, CapabilityKind.SKILL, "first");
            try {
                boolean refused = false;
                try {
                    CapabilityCatalog.register(probeDescriptor(SKILL_PROBE, CapabilityKind.SKILL, "second"),
                            (context, args) -> CompletableFuture.completedFuture(textEnvelope("second")));
                } catch (IllegalArgumentException expected) {
                    refused = true;
                }
                String text = CapabilityExposure.textOf(CapabilityCatalog
                        .invoke(SKILL_PROBE, new JsonObject(), ToolCaller.player("lab", true), ToolSide.CLIENT)
                        .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                boolean firstSurvived = "first".equals(text);
                if (refused && firstSurvived) {
                    return AcceptanceOutcome.pass(id, title, "second registration refused, first handler intact");
                }
                return AcceptanceOutcome.fail(id, title, "refused=" + refused + " firstSurvived=" + firstSurvived);
            } finally {
                CapabilityCatalog.unregister(SKILL_PROBE);
            }
        });
    }

    /** A failed envelope has no text; a consumer that renders it must get an empty string, not a code. */
    private static AcceptanceCase aFailedEnvelopeHasNoText() {
        final String id = "ACC-EXP-10";
        final String title = "a failed envelope yields no text for a consumer";
        return AcceptanceCase.of(id, title, () -> {
            JsonObject failed = new JsonObject();
            failed.addProperty("ok", false);
            failed.addProperty("code", "FAILED");
            failed.addProperty("message", "boom");
            String text = CapabilityExposure.textOf(failed);
            if (text.isEmpty()) {
                return AcceptanceOutcome.pass(id, title, "empty text, as declared");
            }
            return AcceptanceOutcome.fail(id, title, "a failed envelope yielded text: " + text);
        });
    }

    /** An event with no severity is a programming error and must not be silently dropped at the sink. */
    private static AcceptanceCase anEventWithoutSeverityIsRefused() {
        final String id = "ACC-DIAG-4";
        final String title = "a diagnostic event without a severity is refused at construction";
        return AcceptanceCase.of(id, title, () -> {
            boolean refused = false;
            try {
                DiagnosticEvent.now(LabModule.MODULE_ID, "acceptance.probe.null-severity", null,
                        DiagnosticPrivacy.PUBLIC, Map.of());
            } catch (RuntimeException expected) {
                refused = true;
            }
            if (refused) {
                return AcceptanceOutcome.pass(id, title, "construction refused it");
            }
            return AcceptanceOutcome.fail(id, title, "an event with no severity was accepted");
        });
    }

    /**
     * Every {@code body.*} tool is registered, documented, and answers with an envelope when called empty.
     *
     * <p>Deliberately an envelope-level check: an empty argument object should be refused by the schema, and a refusal
     * is the evidence wanted here — it proves the tool is registered, reachable from a client caller and wired to the
     * validator. Asserting behaviour would need a live body in a loaded world, which is the separate fixture this
     * bench does not have yet.</p>
     */
    private static AcceptanceCase everyBodyToolIsReachableAndAnswers() {
        final String id = "ACC-BDY-1";
        final String title = "every body tool is registered, documented and answers an envelope when called empty";
        return AcceptanceCase.of(id, title, () -> {
            List<ToolSpec> bodyTools = new ArrayList<>();
            ToolCaller caller = ToolCaller.player("lab", true);
            for (ToolSpec spec : TianshuTools.catalog(caller, ToolSide.CLIENT)) {
                if (spec.id().toString().contains(":body.")) {
                    bodyTools.add(spec);
                }
            }
            if (bodyTools.isEmpty()) {
                return AcceptanceOutcome.skip(id, title,
                        "no body tool is registered on this side, so there is nothing to call");
            }
            List<String> undocumented = new ArrayList<>();
            boolean missingSchema = false;
            for (ToolSpec spec : bodyTools) {
                if (spec.description() == null || spec.description().isBlank()) {
                    undocumented.add(spec.id().toString());
                }
                if (spec.argsSchema() == null) {
                    missingSchema = true;
                }
            }
            List<String> unanswered = new ArrayList<>();
            for (ToolSpec spec : bodyTools) {
                try {
                    JsonObject envelope = TianshuTools
                            .callJson(spec.id(), new JsonObject(), caller, ToolSide.CLIENT)
                            .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (envelope == null || !envelope.has("ok")) {
                        unanswered.add(spec.id().toString());
                    }
                } catch (Exception failure) {
                    unanswered.add(spec.id() + " (" + failure.getClass().getSimpleName() + ")");
                }
            }
            if (undocumented.isEmpty() && !missingSchema && unanswered.isEmpty()) {
                return AcceptanceOutcome.pass(id, title,
                        bodyTools.size() + " tools registered, documented, and every call answered an envelope");
            }
            return AcceptanceOutcome.fail(id, title, "undocumented=" + undocumented
                    + " missingSchema=" + missingSchema + " unanswered=" + unanswered);
        });
    }

    /**
     * A module task runs on the runtime's lane, and a task that throws does not poison it.
     *
     * <p>The second half is the part worth having: an executor that abandons its lane after one failure takes every
     * later task with it, and from outside that looks exactly like "the module stopped responding".</p>
     */
    private static AcceptanceCase aModuleTaskRunsAndItsLaneSurvivesAFailure() {
        final String id = "ACC-PRT-1";
        final String title = "a module task runs on its lane, and a throwing task does not poison the lane";
        return AcceptanceCase.of(id, title, () -> {
            ModuleRuntimeAccess runtime = AcceptanceEnvironment.runtime();
            if (runtime == null) {
                return AcceptanceOutcome.skip(id, title, "no module runtime is installed; execution needs one");
            }
            CountDownLatch firstRan = new CountDownLatch(1);
            runtime.submit(ProtocolTaskSpec.builder().moduleId(LabModule.MODULE_ID).build(), firstRan::countDown);
            boolean ran = firstRan.await(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            runtime.submit(ProtocolTaskSpec.builder().moduleId(LabModule.MODULE_ID).build(),
                    (Runnable) () -> {
                        throw new IllegalStateException("task failed on purpose");
                    });
            CountDownLatch secondRan = new CountDownLatch(1);
            runtime.submit(ProtocolTaskSpec.builder().moduleId(LabModule.MODULE_ID).build(), secondRan::countDown);
            boolean survived = secondRan.await(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (ran && survived) {
                return AcceptanceOutcome.pass(id, title, "first task ran; the lane still ran one after a failure");
            }
            return AcceptanceOutcome.fail(id, title, "firstRan=" + ran + " survivedAFailure=" + survived);
        });
    }

    /**
     * A block position outside the world border is refused as invalid arguments.
     *
     * <p>Found by schema rather than by id, so it cannot drift when a tool is renamed: the first body tool whose
     * required fields are nothing but a position is the one to ask. A coordinate at 40,000,000 has to come back as
     * {@code INVALID_ARGS} — the alternative, reaching the adapter, is an unexplainable failure somewhere else
     * (`DEC-3`).</p>
     */
    private static AcceptanceCase aBodyCoordinateOutsideTheWorldIsRefused() {
        final String id = "ACC-BDY-2";
        final String title = "a body tool refuses a block position outside the world border";
        return AcceptanceCase.of(id, title, () -> {
            ToolCaller caller = ToolCaller.player("lab", true);
            ToolSpec target = null;
            for (ToolSpec spec : TianshuTools.catalog(caller, ToolSide.CLIENT)) {
                if (!spec.id().toString().contains(":body.") || spec.argsSchema() == null) {
                    continue;
                }
                JsonObject schema = spec.argsSchema();
                if (!schema.has("properties") || !schema.get("properties").isJsonObject()) {
                    continue;
                }
                JsonObject properties = schema.getAsJsonObject("properties");
                if (!properties.has("x") || !properties.has("y") || !properties.has("z")) {
                    continue;
                }
                if (requiresMoreThan(schema, Set.of("x", "y", "z"))) {
                    continue;
                }
                // A switched-off tool refuses before it validates anything, so the switch is handled by callEnabled.
                target = spec;
                break;
            }
            if (target == null) {
                return AcceptanceOutcome.skip(id, title,
                        "every body tool that takes a block position is operator-gated, so its validator cannot be "
                                + "reached from a module-scoped caller");
            }
            JsonObject args = new JsonObject();
            args.addProperty("body", AcceptanceBodies.BODY_ID);
            args.addProperty("x", 40_000_000);
            args.addProperty("y", 64);
            args.addProperty("z", 0);
            AcceptanceBodies bodies = new AcceptanceBodies(localPlayerId());
            Runnable unregister = EntityActors.register(bodies);
            try {
                JsonObject envelope = callEnabled(target.id(), args, caller);
                String code = codeOf(envelope);
                if ("INVALID_ARGS".equals(code)) {
                    return AcceptanceOutcome.pass(id, title, target.id() + " refused the coordinate as INVALID_ARGS");
                }
                if ("UNAVAILABLE".equals(code) && shortEnvelope(envelope).contains("not present")) {
                    // The bench's body provider is a test double inside this process; a remote server has no such
                    // body, so the tool correctly reports UNAVAILABLE there. This case is single-player scoped.
                    return AcceptanceOutcome.skip(id, title,
                            "the bench's body provider runs in this process, so a remote server has no such body");
                }
                return AcceptanceOutcome.fail(id, title, target.id() + " answered " + code + " instead"
                        + "; envelope=" + shortEnvelope(envelope));
            } finally {
                unregister.run();
            }
        });
    }

    /** Whether a schema demands anything besides the fields the caller intends to send. */
    private static boolean requiresMoreThan(JsonObject schema, Set<String> provided) {
        if (!schema.has("required") || !schema.get("required").isJsonArray()) {
            return false;
        }
        for (com.google.gson.JsonElement required : schema.getAsJsonArray("required")) {
            if (!required.isJsonPrimitive() || !provided.contains(required.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A body that fails verification never reaches the final path.
     *
     * <p>The whole point of verifying on the {@code .downloading} file: a promoted file is one a model load can read,
     * and a corrupt one is a crash much later with nothing in the log to explain it (`MDL-1`).</p>
     */
    private static AcceptanceCase aBodyThatFailsVerificationIsNeverPromoted() {
        final String id = "ACC-MDL-1";
        final String title = "a body that fails verification is not promoted and leaves nothing behind";
        return AcceptanceCase.of(id, title, () -> {
            Path target = downloadTarget();
            deleteQuietly(target);
            String expected = DownloadFixtures.sha256OfText("model-content");
            try (DownloadFixtures.ScriptedServer server = new DownloadFixtures.ScriptedServer()) {
                server.enqueue("wrong-content");
                ModelDownloadHttpClient client = new ModelDownloadHttpClient(
                        new DownloadFixtures.StubEnvironment(target.getParent()),
                        DownloadFixtures.loopbackPolicy());
                boolean refused = false;
                try {
                    client.download(List.of(server.uri()), target, retryPolicy(1), () -> {
                    }, (downloaded, total) -> {
                    }, file -> {
                        if (!DownloadFixtures.sha256(file).equals(expected)) {
                            throw new IOException("checksum mismatch");
                        }
                    });
                } catch (IOException expectedFailure) {
                    refused = true;
                }
                boolean absent = !Files.exists(target);
                boolean noTemporary = !Files.exists(temporaryOf(target));
                if (refused && absent && noTemporary) {
                    return AcceptanceOutcome.pass(id, title, "refused, nothing promoted, no temporary left");
                }
                return AcceptanceOutcome.fail(id, title, "refused=" + refused
                        + " promoted=" + !absent + " temporaryLeft=" + !noTemporary);
            } finally {
                deleteQuietly(target);
            }
        });
    }

    /**
     * A corrupt body is re-downloaded rather than turned into a failed install.
     *
     * <p>The verification sits inside the attempt loop, so the second answer is fetched from the same host instead of
     * giving up (`MDL-1`). An install that fails because one response was corrupt is a bug the user reads as "the
     * model will not download".</p>
     */
    private static AcceptanceCase aCorruptBodyIsRetriedBeforeTheInstallFails() {
        final String id = "ACC-MDL-2";
        final String title = "a corrupt body is retried inside the attempt loop and the good bytes are installed";
        return AcceptanceCase.of(id, title, () -> {
            Path target = downloadTarget();
            deleteQuietly(target);
            String expected = DownloadFixtures.sha256OfText("model-content");
            try (DownloadFixtures.ScriptedServer server = new DownloadFixtures.ScriptedServer()) {
                server.enqueue("wrong-content");
                server.enqueue("model-content");
                ModelDownloadHttpClient client = new ModelDownloadHttpClient(
                        new DownloadFixtures.StubEnvironment(target.getParent()),
                        DownloadFixtures.loopbackPolicy());
                boolean installed = false;
                try {
                    client.download(List.of(server.uri()), target, retryPolicy(2), () -> {
                    }, (downloaded, total) -> {
                    }, file -> {
                        if (!DownloadFixtures.sha256(file).equals(expected)) {
                            throw new IOException("checksum mismatch");
                        }
                    });
                    installed = true;
                } catch (IOException failure) {
                    return AcceptanceOutcome.fail(id, title, "the retry did not rescue the install: "
                            + failure.getMessage() + " served=" + server.servedCount()
                            + (failure.getSuppressed().length == 0 ? ""
                                    : " cause=" + failure.getSuppressed()[0].getMessage()));
                }
                String content = installed && Files.exists(target)
                        ? Files.readString(target, java.nio.charset.StandardCharsets.UTF_8) : "";
                boolean good = "model-content".equals(content);
                if (installed && good && server.servedCount() == 2) {
                    return AcceptanceOutcome.pass(id, title, "two responses served, the second one installed");
                }
                return AcceptanceOutcome.fail(id, title, "installed=" + installed + " content=\"" + content
                        + "\" served=" + server.servedCount());
            } finally {
                deleteQuietly(target);
            }
        });
    }

    /**
     * With a world loaded, the game context names the player and every capture it offers is a real value.
     *
     * <p>This is the read side of every client tool: a capture that returns null or a blank id makes a tool answer
     * something invented. Needs a world, so it skips when the bench runs at boot.</p>
     */
    private static AcceptanceCase theGameContextDescribesALoadedWorld() {
        final String id = "ACC-GAME-1";
        final String title = "with a world loaded the game context names the player and fills every capture";
        return AcceptanceCase.of(id, title, () -> {
            ClientGameContextPort context = AcceptanceEnvironment.gameContext();
            if (context == null) {
                return AcceptanceOutcome.skip(id, title, "no game context is installed in this run");
            }
            return AcceptanceEnvironment.readGame(game -> {
                String player = game.playerId();
                if (player == null || player.isBlank()) {
                    return AcceptanceOutcome.skip(id, title, "no world is loaded, so there is no player to describe");
                }
                List<String> empty = new ArrayList<>();
                if (game.capturePlayerStatus() == null) {
                    empty.add("status");
                }
                List<GameInventoryEntry> inventory = game.captureInventory();
                if (inventory == null) {
                    empty.add("inventory");
                }
                if (game.captureEnvironment() == null) {
                    empty.add("environment");
                }
                if (game.capturePlayerPose() == null) {
                    empty.add("pose");
                }
                if (game.captureVitals() == null) {
                    empty.add("vitals");
                }
                if (empty.isEmpty()) {
                    return AcceptanceOutcome.pass(id, title, "player " + player + ", all captures present, "
                            + inventory.size() + " inventory slots");
                }
                return AcceptanceOutcome.fail(id, title, "null captures: " + empty);
            });
        });
    }

    /**
     * The captures a caller may take at any moment do not throw.
     *
     * <p>Tools call these from several threads; a capture that throws on an empty hand or a closed container turns a
     * query into an exception instead of an answer. The pair that legitimately has no value (a block being aimed at, an
     * open container) may be null — this only asserts that asking is safe.</p>
     */
    private static AcceptanceCase theGameContextCapturesDoNotThrow() {
        final String id = "ACC-GAME-2";
        final String title = "every game-context capture answers without throwing";
        return AcceptanceCase.of(id, title, () -> {
            ClientGameContextPort context = AcceptanceEnvironment.gameContext();
            if (context == null) {
                return AcceptanceOutcome.skip(id, title, "no game context is installed in this run");
            }
            List<String> threw = new ArrayList<>();
            return AcceptanceEnvironment.readGame(game -> {
                captureThrowing("interaction", game::captureInteraction, threw);
                captureThrowing("status", game::capturePlayerStatus, threw);
                captureThrowing("inventory", game::captureInventory, threw);
                captureThrowing("equipment", game::captureEquipment, threw);
                captureThrowing("effects", game::captureEffects, threw);
                captureThrowing("environment", game::captureEnvironment, threw);
                captureThrowing("blockTarget", game::captureBlockTarget, threw);
                captureThrowing("pose", game::capturePlayerPose, threw);
                captureThrowing("vitals", game::captureVitals, threw);
                captureThrowing("openContainer", game::captureOpenContainer, threw);
                captureThrowing("playerList", game::capturePlayerList, threw);
                if (threw.isEmpty()) {
                    return AcceptanceOutcome.pass(id, title, "all 11 captures answered");
                }
                return AcceptanceOutcome.fail(id, title, "threw: " + threw);
            });
        });
    }

    /**
     * A player commands a body it owns, and the command reaches the body's port.
     *
     * <p>This is the path the tools actually have: body tools are server tools, and the wire carries a player. The
     * command therefore executes on the authoritative side as that player, which is also what an assistant looks like
     * from the server's point of view.</p>
     */
    private static AcceptanceCase aPlayerCommandsTheBodyItOwns() {
        final String id = "ACC-BDY-3";
        final String title = "a player commands the body it owns through the wire, and the command reaches the port";
        return AcceptanceCase.of(id, title, () -> {
            String owner = localPlayerId();
            if (owner == null) {
                return AcceptanceOutcome.skip(id, title, "no world is loaded, so there is no player to own the body");
            }
            AcceptanceBodies bodies = new AcceptanceBodies(owner);
            Runnable unregister = EntityActors.register(bodies);
            try {
                JsonObject args = new JsonObject();
                args.addProperty("body", AcceptanceBodies.BODY_ID);
                args.addProperty("x", 10);
                args.addProperty("y", 64);
                args.addProperty("z", 10);
                JsonObject envelope = callEnabled(ToolId.of("tianshu", "body.look"), args,
                        ToolCaller.player(owner, true));
                String code = codeOf(envelope);
                boolean ok = envelope.has("ok") && envelope.get("ok").getAsBoolean();
                boolean reachedPort = !bodies.looks().isEmpty();
                if (ok && reachedPort) {
                    return AcceptanceOutcome.pass(id, title, "the owner's look reached the body port");
                }
                if ("UNAVAILABLE".equals(code) || "UNSUPPORTED_SIDE".equals(code)) {
                    return AcceptanceOutcome.skip(id, title,
                            "no authoritative side in this session: " + shortEnvelope(envelope));
                }
                return AcceptanceOutcome.fail(id, title, "ok=" + ok + " reachedPort=" + reachedPort
                        + " envelope=" + shortEnvelope(envelope));
            } finally {
                unregister.run();
            }
        });
    }

    /**
     * A client-side module cannot drive an NPC: the wire authenticates players only.
     *
     * <p>The rule is the whole reason a body argument is not a way to reach someone else's NPC. A module that owns
     * bodies drives them on the authoritative side, in-process; here what has to hold is the refusal, taken before any
     * port runs.</p>
     */
    private static AcceptanceCase aClientModuleCannotDriveAnNpc() {
        final String id = "ACC-BDY-7";
        final String title = "a client-side module's body call is refused before any port runs";
        return AcceptanceCase.of(id, title, () -> {
            String owner = localPlayerId();
            if (owner == null) {
                return AcceptanceOutcome.skip(id, title, "no world is loaded, so there is no body to drive");
            }
            AcceptanceBodies bodies = new AcceptanceBodies(owner);
            Runnable unregister = EntityActors.register(bodies);
            try {
                JsonObject args = new JsonObject();
                args.addProperty("body", AcceptanceBodies.BODY_ID);
                args.addProperty("x", 10);
                args.addProperty("y", 64);
                args.addProperty("z", 10);
                JsonObject envelope = callEnabled(ToolId.of("tianshu", "body.look"), args,
                        ToolCaller.agent(LabModuleRegistration.MOD_ID, false, ToolAutonomy.FULL));
                boolean refused = envelope.has("ok") && !envelope.get("ok").getAsBoolean();
                String text = String.valueOf(envelope);
                // Two refusals are correct here and which one arrives depends on whether the server has body tools
                // switched on: the switch is checked first, and the wire's rule is what stops the call when it is on.
                // What the case locks is that neither reaches the port on a module's word.
                boolean named = text.contains("body.controller.unverified") || text.contains("is switched off");
                boolean nothingHappened = bodies.looks().isEmpty();
                if (refused && named && nothingHappened) {
                    return AcceptanceOutcome.pass(id, title, "refused before the port: "
                            + codeOf(envelope) + " " + (text.contains("body.controller.unverified")
                                    ? "body.controller.unverified" : "tool switched off"));
                }
                return AcceptanceOutcome.fail(id, title, "refused=" + refused + " named=" + named
                        + " portRan=" + !nothingHappened + " envelope=" + shortEnvelope(envelope));
            } finally {
                unregister.run();
            }
        });
    }

    /**
     * A body tool refuses a body that was never registered, and the registered body's port never runs.
     *
     * <p>The slot the caller is resolved against is the real local player (the platform rewrites client-side callers
     * to that identity), so a case that invented a commandable name would be testing the harness rather than the
     * platform. What can be observed exactly is the resolve step: an unknown body id is refused before any port runs.</p>
     */
    private static AcceptanceCase aBodyRefusesAnUnregisteredBody() {
        final String id = "ACC-BDY-4";
        final String title = "a body tool refuses a body that was never registered, and no port runs";
        return AcceptanceCase.of(id, title, () -> {
            String owner = localPlayerId();
            if (owner == null) {
                return AcceptanceOutcome.skip(id, title, "no world is loaded, so there is no player to drive a body");
            }
            AcceptanceBodies bodies = new AcceptanceBodies(owner);
            Runnable unregister = EntityActors.register(bodies);
            try {
                JsonObject args = new JsonObject();
                args.addProperty("body", "lab:never-registered");
                args.addProperty("x", 10);
                args.addProperty("y", 64);
                args.addProperty("z", 10);
                JsonObject envelope = callEnabled(ToolId.of("tianshu", "body.look"), args,
                        ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.FULL));
                boolean refused = envelope.has("ok") && !envelope.get("ok").getAsBoolean();
                boolean nothingHappened = bodies.looks().isEmpty();
                if (refused && nothingHappened) {
                    return AcceptanceOutcome.pass(id, title, "refused with " + codeOf(envelope)
                            + ", and the registered body's port never ran");
                }
                return AcceptanceOutcome.fail(id, title, "refused=" + refused + " code=" + codeOf(envelope)
                        + " portRan=" + !nothingHappened);
            } finally {
                unregister.run();
            }
        });
    }

    /**
     * The adapter took the body-type declarations during the loader's registration pass.
     *
     * <p>The bench ships {@code META-INF/tianshu/body_types.json} itself, so a run in a development client proves the
     * whole path: the file was found inside a module jar, parsed, and drained before the window closed. A declaration
     * left over here would mean the adapter scanned too late — and the type would simply never exist.</p>
     */
    private static AcceptanceCase theAdapterTakesTheBodyTypeDeclarationsAtStartup() {
        final String id = "ACC-ENT-1";
        final String title = "the adapter takes the body-type declarations during the registration pass";
        return AcceptanceCase.of(id, title, () -> {
            boolean closed = com.rheinmetal.tianshu.api.entity.EntityTypes.closed();
            List<com.rheinmetal.tianshu.api.entity.EntityTypeSpec> taken =
                    com.rheinmetal.tianshu.api.entity.EntityTypes.drainedForTests();
            int pending = com.rheinmetal.tianshu.api.entity.EntityTypes.declared().size();
            boolean scannedThisJar = taken.stream()
                    .anyMatch(spec -> spec.name().equals("acceptance_orb"));
            if (!closed) {
                return AcceptanceOutcome.fail(id, title,
                        "the adapter never drained the declarations, so no declared type can exist");
            }
            if (scannedThisJar && pending == 0) {
                return AcceptanceOutcome.pass(id, title,
                        "the bench's own declaration was read from its jar and registered ("
                                + taken.size() + " taken)");
            }
            return AcceptanceOutcome.fail(id, title, "scannedThisJar=" + scannedThisJar
                    + " pending=" + pending
                    + " taken=" + taken.stream().map(com.rheinmetal.tianshu.api.entity.EntityTypeSpec::id).toList());
        });
    }

    /**
     * A cheat-grade action travels to the server and comes back executed.
     *
     * <p>The whole chain is under test here: the tool, the action channel, the server ceiling, the operator check, the
     * adapter's own handler and the envelope mapping. The action chosen is harmless when it runs — experience is
     * changed by zero levels.</p>
     */
    private static AcceptanceCase anActionReachesTheServerAndComesBackExecuted() {
        final String id = "ACC-ACT-1";
        final String title = "a cheat-grade action reaches the server and comes back executed";
        return AcceptanceCase.of(id, title, () -> {
            String owner = localPlayerId();
            if (owner == null) {
                return AcceptanceOutcome.skip(id, title, "no world is loaded, so there is no server component");
            }
            JsonObject args = new JsonObject();
            args.addProperty("levels", 0);
            JsonObject envelope = callEnabled(ToolId.of("tianshu", "server.xp"), args,
                    ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.FULL));
            String code = codeOf(envelope);
            if (envelope.has("ok") && envelope.get("ok").getAsBoolean()) {
                return AcceptanceOutcome.pass(id, title, "the server executed the action: " + detail(envelope));
            }
            if ("DISABLED".equals(code)) {
                return AcceptanceOutcome.skip(id, title,
                        "the server has the xp switch off or a ceiling below full, so the action was refused");
            }
            if ("NO_PERMISSION".equals(code)) {
                return AcceptanceOutcome.skip(id, title,
                        "the action was refused as NO_PERMISSION: " + detail(envelope));
            }
            return AcceptanceOutcome.fail(id, title, "the action did not reach the server: " + code
                    + " " + detail(envelope));
        });
    }

    /** The server's switch is the authority: an action it announces as off is refused before it runs. */
    private static AcceptanceCase anActionIsRefusedByTheServerSwitchWhenItsOff() {
        final String id = "ACC-ACT-2";
        final String title = "an action whose server switch is off is refused by the server";
        return AcceptanceCase.of(id, title, () -> {
            String owner = localPlayerId();
            if (owner == null) {
                return AcceptanceOutcome.skip(id, title, "no world is loaded, so there is no server component");
            }
            JsonObject args = new JsonObject();
            args.addProperty("x", 0.0);
            args.addProperty("y", 64.0);
            args.addProperty("z", 0.0);
            JsonObject envelope = callEnabled(ToolId.of("tianshu", "server.teleport"), args,
                    ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.FULL));
            String code = codeOf(envelope);
            if ("DISABLED".equals(code)) {
                return AcceptanceOutcome.pass(id, title, "refused with DISABLED naming the server switch");
            }
            if (envelope.has("ok") && envelope.get("ok").getAsBoolean()) {
                return AcceptanceOutcome.skip(id, title,
                        "the server has teleport switched on, so the off path is not observable");
            }
            if ("NO_PERMISSION".equals(code)) {
                return AcceptanceOutcome.skip(id, title,
                        "the action was refused as NO_PERMISSION: " + detail(envelope));
            }
            return AcceptanceOutcome.fail(id, title, "expected DISABLED, got " + code + " " + detail(envelope));
        });
    }

    /** Arguments the server rejects come back as INVALID_ARGS, not as a generic failure. */
    private static AcceptanceCase anActionWithBadArgumentsIsRefusedByTheServerSideValidation() {
        final String id = "ACC-ACT-3";
        final String title = "an action with an unusable argument is refused as INVALID_ARGS";
        return AcceptanceCase.of(id, title, () -> {
            String owner = localPlayerId();
            if (owner == null) {
                return AcceptanceOutcome.skip(id, title, "no world is loaded, so there is no server component");
            }
            JsonObject args = new JsonObject();
            args.addProperty("x", 0);
            args.addProperty("y", 64);
            args.addProperty("z", 0);
            args.addProperty("block", "tianshu:no_such_block");
            JsonObject envelope = callEnabled(ToolId.of("tianshu", "server.setblock"), args,
                    ToolCaller.agent(LabModuleRegistration.MOD_ID, true, ToolAutonomy.FULL));
            String code = codeOf(envelope);
            if ("INVALID_ARGS".equals(code)) {
                return AcceptanceOutcome.pass(id, title, "the server refused the unknown block id");
            }
            if ("DISABLED".equals(code)) {
                return AcceptanceOutcome.skip(id, title, "the server has the setblock switch off");
            }
            if ("NO_PERMISSION".equals(code)) {
                return AcceptanceOutcome.skip(id, title,
                        "the action was refused as NO_PERMISSION: " + detail(envelope));
            }
            return AcceptanceOutcome.fail(id, title, "expected INVALID_ARGS, got " + code + " " + detail(envelope));
        });
    }

    private static String detail(JsonObject envelope) {
        var element = envelope.get("message");
        if (element == null || !element.isJsonPrimitive()) {
            element = envelope.get("detail");
        }
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    /** The id the platform resolves a client-side call to, or {@code null} before a world is loaded. */
    private static String localPlayerId() {
        ClientGameContextPort context = AcceptanceEnvironment.gameContext();
        if (context == null) {
            return null;
        }
        try {
            String player = AcceptanceEnvironment.readGame(ClientGameContextPort::playerId);
            return player == null || player.isBlank() ? null : player;
        } catch (RuntimeException notReady) {
            return null;
        }
    }

    /**
     * Calls a tool that ships switched off, turning it on for this one call and restoring it afterwards.
     *
     * <p>Body tools are write tools, so they ship off; a switched-off tool refuses before it validates or resolves
     * anything, which would make a case pass for the wrong reason. The switch is restored in a finally.</p>
     */
    private static JsonObject callEnabled(ToolId tool, JsonObject args, ToolCaller caller) throws Exception {
        boolean wasEnabled = TianshuTools.isEnabled(tool);
        try {
            if (!wasEnabled) {
                TianshuTools.setEnabled(tool, true);
            }
            return call(tool, args, caller);
        } finally {
            if (!wasEnabled) {
                TianshuTools.clearEnabledOverride(tool);
            }
        }
    }

    private static String shortEnvelope(JsonObject envelope) {
        String text = String.valueOf(envelope);
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }

    private static void captureThrowing(String name, Runnable capture, List<String> threw) {
        try {
            capture.run();
        } catch (RuntimeException failure) {
            threw.add(name + " (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private static Path downloadTarget() {
        return Path.of("tianshu-lab", "acceptance-download", "model.onnx");
    }

    private static Path temporaryOf(Path target) {
        return target.resolveSibling(target.getFileName() + ".downloading");
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(temporaryOf(file));
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".downloading.meta"));
        } catch (IOException ignored) {
            // Best effort: a leftover fixture file must not fail the case that created it.
        }
    }

    private static ModelDownloadHttpClient.RetryPolicy retryPolicy(int attempts) {
        return new ModelDownloadHttpClient.RetryPolicy(attempts, 2_000, 2_000, 0L);
    }

    private static JsonObject readCapability(String capabilityId, JsonObject arguments) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("id", capabilityId);
        if (arguments != null) {
            args.add("arguments", arguments.deepCopy());
        }
        return call(READ_TOOL, args, ToolCaller.player("lab", true));
    }

    private static Set<String> listedIds(ToolCaller caller) {
        Set<String> ids = new TreeSet<>();
        for (ToolSpec spec : TianshuTools.catalog(caller, ToolSide.CLIENT)) {
            ids.add(spec.id().toString());
        }
        return ids;
    }

    private static boolean lists(ToolCaller caller, ToolId id) {
        for (ToolSpec spec : TianshuTools.catalog(caller, ToolSide.CLIENT)) {
            if (spec.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject call(ToolId tool, JsonObject args, ToolCaller caller) throws Exception {
        return TianshuTools.callJson(tool, args, caller, ToolSide.CLIENT)
                .get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Reading the open container is a local read: it must answer with a value whether or not a container is open.
     *
     * <p>Without this the model has no way to learn the slot indexes {@code container.transfer} takes, which is the
     * whole reason the tool exists.</p>
     */
    private static AcceptanceCase theOpenContainerComesBackAsAValue() {
        final String id = "ACC-CNR-1";
        final String title = "the open container comes back as a value, container or not";
        return AcceptanceCase.of(id, title, () -> {
            JsonObject envelope = call(ToolId.of("tianshu", "container.inspect"), new JsonObject(),
                    ToolCaller.player(localPlayerId() == null ? "lab" : localPlayerId(), true));
            String code = codeOf(envelope);
            JsonElement result = envelope.get("result");
            if (result == null || !result.isJsonObject()) {
                return AcceptanceOutcome.fail(id, title, "no result object: " + code + " " + shortEnvelope(envelope));
            }
            JsonObject view = result.getAsJsonObject();
            if (!view.has("open") || !view.get("open").isJsonPrimitive()) {
                return AcceptanceOutcome.fail(id, title, "open is not a value: " + shortEnvelope(envelope));
            }
            if (!view.has("entries") || !view.get("entries").isJsonArray()) {
                return AcceptanceOutcome.fail(id, title, "entries is not a list: " + shortEnvelope(envelope));
            }
            return AcceptanceOutcome.pass(id, title,
                    "open=" + view.get("open").getAsBoolean() + " entries=" + view.getAsJsonArray("entries").size());
        });
    }

    /** Handing something over is aimed at someone; a call with no receiver is malformed before any world is touched. */
    private static AcceptanceCase handingItemsOverNeedsAReceiver() {
        final String id = "ACC-GIV-1";
        final String title = "a hand-over without a receiver is refused as INVALID_ARGS";
        return AcceptanceCase.of(id, title, () -> {
            AcceptanceBodies bodies = new AcceptanceBodies(localPlayerId());
            Runnable unregister = EntityActors.register(bodies);
            try {
                JsonObject args = new JsonObject();
                args.addProperty("body", AcceptanceBodies.BODY_ID);
                args.addProperty("slot", 0);
                args.addProperty("count", 1);
                JsonObject envelope = callEnabled(ToolId.of("tianshu", "body.give"), args,
                        ToolCaller.body(AcceptanceBodies.BODY_ID, true));
                String code = codeOf(envelope);
                if ("INVALID_ARGS".equals(code)) {
                    return AcceptanceOutcome.pass(id, title,
                            "the missing receiver is refused before anything moves");
                }
                if ("DISABLED".equals(code)) {
                    return AcceptanceOutcome.skip(id, title, "the server has the body actions switch off");
                }
                if ("NO_PERMISSION".equals(code)) {
                    return AcceptanceOutcome.skip(id, title, "the local player is not an operator in this world");
                }
                return AcceptanceOutcome.fail(id, title, "expected INVALID_ARGS, got " + code + " "
                        + shortEnvelope(envelope));
            } finally {
                unregister.run();
            }
        });
    }

    /** A line of blocks grows in one of six directions; an unknown name is refused before any block is placed. */
    private static AcceptanceCase aPlacementLineNeedsAKnownDirection() {
        final String id = "ACC-PLC-1";
        final String title = "a placement line refuses an unknown direction as INVALID_ARGS";
        return AcceptanceCase.of(id, title, () -> {
            AcceptanceBodies bodies = new AcceptanceBodies(localPlayerId());
            Runnable unregister = EntityActors.register(bodies);
            try {
                JsonObject args = new JsonObject();
                args.addProperty("body", AcceptanceBodies.BODY_ID);
                args.addProperty("item", "minecraft:stone");
                args.addProperty("x", 0);
                args.addProperty("y", 64);
                args.addProperty("z", 0);
                args.addProperty("direction", "sideways");
                args.addProperty("count", 3);
                JsonObject envelope = callEnabled(ToolId.of("tianshu", "block.place.line"), args,
                        ToolCaller.body(AcceptanceBodies.BODY_ID, true));
                String code = codeOf(envelope);
                if ("INVALID_ARGS".equals(code)) {
                    return AcceptanceOutcome.pass(id, title,
                            "the unknown direction is refused before any block is placed");
                }
                if ("DISABLED".equals(code)) {
                    return AcceptanceOutcome.skip(id, title, "the server has the block place switch off");
                }
                if ("NO_PERMISSION".equals(code)) {
                    return AcceptanceOutcome.skip(id, title, "the local player is not an operator in this world");
                }
                return AcceptanceOutcome.fail(id, title, "expected INVALID_ARGS, got " + code + " "
                        + shortEnvelope(envelope));
            } finally {
                unregister.run();
            }
        });
    }

    private static String codeOf(JsonObject envelope) {
        JsonElement code = envelope == null ? null : envelope.get(CODE);
        return code != null && code.isJsonPrimitive() ? code.getAsString() : String.valueOf(envelope);
    }

    private static String messageOf(JsonObject envelope) {
        JsonElement message = envelope == null ? null : envelope.get(MESSAGE);
        return message != null && message.isJsonPrimitive() ? message.getAsString() : String.valueOf(envelope);
    }

    private static JsonObject textEnvelope(String text) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("ok", true);
        envelope.addProperty("text", text);
        return envelope;
    }

    /** A tool that declares its argument object strict, so the validator has something unambiguous to answer. */
    private static final class StrictProbeTool implements TianshuTool<JsonObject, JsonObject> {

        @Override
        public ToolId id() {
            return STRICT_PROBE;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Acceptance probe: a client tool whose argument object refuses undeclared fields.";
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
        public JsonObject argsSchema() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            JsonObject value = new JsonObject();
            value.addProperty("type", "string");
            properties.add("value", value);
            schema.add("properties", properties);
            schema.addProperty("additionalProperties", false);
            return schema;
        }

        @Override
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }

    /** A plain client tool the agent-session cases can discover and call; removed again by the case. */
    private static final class SessionProbeTool implements TianshuTool<JsonObject, JsonObject> {

        @Override
        public ToolId id() {
            return SESSION_PROBE;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Acceptance probe: a client tool the agent session can discover and call.";
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
            result.addProperty("ok", true);
            return CompletableFuture.completedFuture(result);
        }
    }

    /**
     * The host runs an outside module's two lifecycle phases exactly once, in order.
     *
     * <p>What a module can honestly say about the host is what happened to it: how many times each phase ran and
     * whether {@code register} came first. Reading the host's own status table instead would test the table, not the
     * module's experience of being assembled.</p>
     */
    private static AcceptanceCase theHostAssemblesAnOutsideModuleOnce() {
        final String id = "ACC-HOST-1";
        final String title = "the host runs an outside module's register and start once, in that order, to the end";
        return AcceptanceCase.of(id, title, () -> {
            LabHostTrace host = AcceptanceEnvironment.host();
            if (host == null) {
                return AcceptanceOutcome.skip(id, title, "no module host in this process");
            }
            if (host.registerCalls() == 1 && host.registerCompleted()
                    && host.startCalls() == 1 && host.registerBeforeStart()) {
                return AcceptanceOutcome.pass(id, title, "register=1 (finished), start=1, register first");
            }
            return AcceptanceOutcome.fail(id, title, "register=" + host.registerCalls()
                    + " completed=" + host.registerCompleted()
                    + " start=" + host.startCalls() + " registerFirst=" + host.registerBeforeStart());
        });
    }

    /**
     * A module's capability is in the centre's registry under its own id, and a command it sends to that capability
     * comes back through the centre.
     *
     * <p>Sender and receiver are the same module on purpose: the check has to mean something with no other module
     * installed. What it locks is the path a module actually uses — declare a capability, see it registered, submit a
     * command, have the handler run — not the broker's internals.</p>
     */
    private static AcceptanceCase aModuleReachesItsOwnCapabilityThroughTheCentre() {
        final String id = "ACC-PRO-1";
        final String title = "a module's capability is registered under its id and a command to it is delivered";
        return AcceptanceCase.of(id, title, () -> {
            ModuleRuntimeAccess runtime = AcceptanceEnvironment.runtime();
            LabHostTrace host = AcceptanceEnvironment.host();
            if (runtime == null || host == null) {
                return AcceptanceOutcome.skip(id, title, "no module runtime in this process");
            }
            List<ProtocolCapabilityRegistration> registered =
                    runtime.capabilityRegistrations(LabProtocolAdapter.ECHO_CAPABILITY);
            if (registered.size() != 1) {
                return AcceptanceOutcome.fail(id, title, "providers=" + registered.size());
            }
            String owner = registered.get(0).moduleId();
            if (!LabProtocolAdapter.MODULE_ID.equals(owner)) {
                return AcceptanceOutcome.fail(id, title, "capability attributed to " + owner);
            }
            int before = host.echoDeliveries();
            String token = "probe-" + System.currentTimeMillis();
            host.echo(token);
            long deadline = System.currentTimeMillis() + CALL_TIMEOUT_SECONDS * 1000L;
            while (System.currentTimeMillis() < deadline
                    && (host.echoDeliveries() <= before || !token.equals(host.lastEcho()))) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (host.echoDeliveries() > before && token.equals(host.lastEcho())) {
                return AcceptanceOutcome.pass(id, title, "registered under " + owner + ", delivered "
                        + (host.echoDeliveries() - before) + " command(s)");
            }
            return AcceptanceOutcome.fail(id, title, "delivered=" + (host.echoDeliveries() - before)
                    + " lastEcho=" + host.lastEcho());
        });
    }

    /** A client tool that declares the FULL tier, so the tier filter has something real to hide. */
    private static final class FullProbeTool implements TianshuTool<JsonObject, JsonObject> {

        @Override
        public ToolId id() {
            return FULL_PROBE;
        }

        @Override
        public ToolCategory category() {
            return ToolCategory.LOCAL;
        }

        @Override
        public String description() {
            return "Acceptance probe: a client tool that declares the FULL tier.";
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
        public ToolAutonomy requiredAutonomy() {
            return ToolAutonomy.FULL;
        }

        @Override
        public CompletableFuture<JsonObject> invoke(ToolContext context, JsonObject args) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
    }
}
