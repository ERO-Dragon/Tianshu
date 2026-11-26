package com.rheinmetal.tianshu.lab;

import com.rheinmetal.tianshu.api.capability.CapabilitySubscription;
import com.rheinmetal.tianshu.api.capability.CapabilitySubscriptions;
import com.rheinmetal.tianshu.api.capability.CapabilitySharing;
import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientPresentationPort;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.lab.acceptance.AcceptanceEnvironment;
import com.rheinmetal.tianshu.lab.bridge.intake.LabIntakeBridge;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
import com.rheinmetal.tianshu.protocol.payload.TextPayload;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The dev-only bench module: three participants, one per claim kind.
 *
 * <p>It registers through the public IA protocol and receives the body on its own capabilities, so from
 * IA's point of view it is indistinguishable from a third-party NPC or assistant module. The page can change
 * what the context participants look for (crosshair entity ids, held item id, gamma's wake words) and then
 * re-register; the module never reads IA's registry to find out whether that worked, it just sends the
 * commands and reports the last error.</p>
 */
public final class LabModule implements TianshuManagedModule, LabModuleControl, LabHostTrace {
    public static final String MODULE_ID = LabProtocolAdapter.MODULE_ID;

    /** How long an unattended run waits for the tool registry before reporting what it has. */
    private static final long AUTO_RUN_REGISTRY_WAIT_MILLIS = 30_000L;
    /** How long an auto-run asked for the world waits for a world and a player before reporting what it has. */
    private static final long AUTO_RUN_WORLD_WAIT_MILLIS = 180_000L;
    private static final String INTAKE_UNAVAILABLE_MESSAGE =
            "tianshu_intake is not installed; dialogue participants stay disabled";

    private final LabProtocolAdapter adapter;
    /** A second module id over the same runtime: the bench needs two declarers of one capability to see fan-out. */
    private final LabProtocolAdapter peerAdapter;
    /** The runtime this module was assembled with; the acceptance bench borrows it for execution cases. */
    private final com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess runtime;
    /** Read-only view of the game; the acceptance bench borrows it for the world-context cases. */
    private final ClientGameContextPort gameContext;
    private final ClientPresentationPort presentation;
    private final ClientScheduler scheduler;
    private final ClientTextProvider texts;
    private final boolean debugEnabled;
    /** Removes the reference workflow this module published; null when it was never installed. */
    private Runnable workflowRemoval;
    private Thread acceptanceWorker;

    private volatile boolean participantsRegistered;
    private volatile boolean autoRelease = true;
    private volatile String sessionId = "";
    private volatile String ownerParticipantId = "";
    private volatile String lastDeliveryText = "";
    private volatile String lastError = "";
    private volatile String crosshairFilter = "";
    private volatile String heldItemId = "minecraft:diamond";
    private volatile String wakeWords = LabParticipants.DEFAULT_WAKE_WORDS;
    private volatile String registeredParticipants = "";
    /** Filled from the module runtime context at start; the unattended acceptance run reports through it. */
    private volatile DiagnosticSink diagnostics = DiagnosticSink.NOOP;
    /** How long a winner keeps the turn; the page can switch it, and all three participants share it. */
    private volatile DialogueHoldTier holdTier = DialogueHoldTier.TEN_SECONDS;
    /** How many times each host phase ran on this module; the bench's host case reads these. */
    private volatile int registerCalls;
    private volatile boolean registerCompleted;
    private volatile int startCalls;
    private volatile boolean registerBeforeStart;
    /** The last command the bench's own echo capability received, and how many arrived in this session. */
    private volatile String lastEcho = "";
    private final java.util.concurrent.atomic.AtomicInteger sharedDeliveries = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger sharedPeerDeliveries =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Why the peer's duplicate declaration was refused; null when the centre accepted it, which would be the bug. */
    private volatile String peerRefusal;
    /** Feed-topic deliveries: one counter per subscriber, so a broadcast that reached only one is visible as such. */
    private final java.util.concurrent.atomic.AtomicInteger feedDeliveries = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger feedPeerDeliveries =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger echoDeliveries =
            new java.util.concurrent.atomic.AtomicInteger();

    public LabModule(ModuleRuntimeAccess runtime, ClientPorts ports) {
        this.runtime = runtime;
        this.gameContext = ports == null ? null : ports.gameContext();
        this.adapter = new LabProtocolAdapter(Objects.requireNonNull(runtime, "runtime"));
        this.peerAdapter = new LabProtocolAdapter(MODULE_ID + ".peer", runtime);
        this.presentation = ports == null ? null : ports.presentation();
        this.scheduler = ports == null ? null : ports.scheduler();
        this.texts = ports == null ? ClientTextProvider.NOOP : ports.texts();
        this.debugEnabled = ports != null && ports.debugEnabled().getAsBoolean();
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        Objects.requireNonNull(context, "context");
        registerCalls++;
        // The delivery capabilities are intake's vocabulary: registering one resolves intake's payload class, so an
        // installation without intake throws here instead of simply having nothing to deliver to. The guard is the
        // same one the participants use, and the echo capability below is what stays available either way.
        if (intakeInstalled()) {
            // One capability per participant: the capability that received a delivery names its owner, which is
            // also the participant id a session-control request has to carry.
            adapter.registerDelivery(LabProtocolAdapter.ALPHA_CAPABILITY,
                    (envelope, protocolContext) -> handleDelivery(envelope, protocolContext, LabParticipants.ALPHA));
            adapter.registerDelivery(LabProtocolAdapter.BETA_CAPABILITY,
                    (envelope, protocolContext) -> handleDelivery(envelope, protocolContext, LabParticipants.BETA));
            adapter.registerDelivery(LabProtocolAdapter.GAMMA_CAPABILITY,
                    (envelope, protocolContext) -> handleDelivery(envelope, protocolContext, LabParticipants.GAMMA));
        } else {
            lastError = INTAKE_UNAVAILABLE_MESSAGE;
        }
        // One the bench alone sends to and receives from, so the acceptance run can watch the centre work
        // without any other module installed.
        adapter.registerEcho(this::handleEcho);
        // The same capability from two module ids: the centre has to deliver to both, and keeping the second one must
        // not replace the first.
        adapter.registerShared(this::handleShared);
        // A topic is the other half of the centre: one producer, any number of subscribers. The bench registers it once
        // and subscribes with both of its module ids, so one publish has to reach both.
        adapter.registerFeedTopic();
        adapter.subscribeFeed(this::handleFeed);
        peerAdapter.subscribeFeed(this::handleFeedPeer);
        // A capability id belongs to one module, so this must be refused — and the refusal must name what it collided
        // with. Caught here on purpose: the bench is checking the rule, not failing to install.
        try {
            peerAdapter.registerShared(this::handleSharedPeer);
            peerRefusal = null;
        } catch (RuntimeException refused) {
            peerRefusal = String.valueOf(refused.getMessage());
        }
        registerCompleted = true;
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        CapabilitySubscriptions.declare(LabModuleRegistration.MOD_ID, CapabilitySubscription.atomicFrom(CapabilitySharing.BASE_PROVIDER));
        registerBeforeStart = registerCalls > 0;
        startCalls++;
        if (context != null) {
            this.diagnostics = context.diagnostics();
        }
        AcceptanceEnvironment.install(runtime, gameContext, scheduler, this);
        // The bench's reference composite capability: published through the same path a module would use, and removed
        // again on stop. It lives here rather than in the base because the base ships the channel, not workflows.
        if (workflowRemoval == null) {
            workflowRemoval = LabWorkflow.install();
        }
        if (debugEnabled) {
            registerParticipants();
        }
        autoRunAcceptanceIfRequested();
    }

    @Override
    public int registerCalls() {
        return registerCalls;
    }

    @Override
    public boolean registerCompleted() {
        return registerCompleted;
    }

    @Override
    public int startCalls() {
        return startCalls;
    }

    @Override
    public boolean registerBeforeStart() {
        return registerBeforeStart;
    }

    @Override
    public String lastEcho() {
        return lastEcho;
    }

    @Override
    public int echoDeliveries() {
        return echoDeliveries.get();
    }

    @Override
    public void echo(String text) {
        adapter.echo(text);
    }

    /** Records one echo delivery and completes its envelope, the same contract every handler has. */
    private void handleEcho(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof TextPayload text) {
            lastEcho = text.text();
            echoDeliveries.incrementAndGet();
        }
        context.complete(envelope.envelopeId());
    }

    @Override
    public int sharedDeliveries() {
        return sharedDeliveries.get();
    }

    @Override
    public int sharedPeerDeliveries() {
        return sharedPeerDeliveries.get();
    }

    @Override
    public String peerRefusal() {
        return peerRefusal;
    }

    @Override
    public void submitShared(String text) {
        adapter.submitShared(text);
    }

    @Override
    public int feedDeliveries() {
        return feedDeliveries.get();
    }

    @Override
    public int feedPeerDeliveries() {
        return feedPeerDeliveries.get();
    }

    @Override
    public void publishFeed(String text) {
        adapter.publishFeed(text);
    }

    private void handleFeed(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof TextPayload) {
            feedDeliveries.incrementAndGet();
        }
        context.complete(envelope.envelopeId());
    }

    private void handleFeedPeer(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof TextPayload) {
            feedPeerDeliveries.incrementAndGet();
        }
        context.complete(envelope.envelopeId());
    }

    /** One delivery of the capability both declarers claim; the peer's handler follows the same contract. */
    private void handleShared(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof TextPayload) {
            sharedDeliveries.incrementAndGet();
        }
        context.complete(envelope.envelopeId());
    }

    private void handleSharedPeer(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof TextPayload) {
            sharedPeerDeliveries.incrementAndGet();
        }
        context.complete(envelope.envelopeId());
    }

    /**
     * Runs the acceptance bench once, unattended, when the launcher asked for it.
     *
     * <p>Triggered by the {@code tianshu.lab.acceptance} system property, which the development run sets from
     * {@code -PtianshuLabAcceptance=1}. Two things make this honest rather than convenient: the bench waits for the
     * tool registry to fill in (a run against an empty table would compare two empty views and call it a pass), and
     * it reports whatever it finds — a boot that never registers anything produces a failing run, not a silent
     * no-op.</p>
     *
     * <p>Its evidence is the JSON report beside the run <em>and</em> the {@code acceptance.run} diagnostics event,
     * both taken from the same run: the sink comes from the module runtime context, so an unattended run lands in
     * {@code tianshu-diagnostics.log} exactly like the page's button does.</p>
     */
    private void autoRunAcceptanceIfRequested() {
        String requested = System.getProperty("tianshu.lab.acceptance");
        if (requested == null || requested.isBlank()) {
            return;
        }
        boolean needsCatalogue = requiresServerCatalogue();
        boolean needsWorld = needsCatalogue || "world".equalsIgnoreCase(requested.trim());
        Thread worker = new Thread(() -> {
            long deadline = System.currentTimeMillis() + AUTO_RUN_REGISTRY_WAIT_MILLIS;
            while (System.currentTimeMillis() < deadline && isRegistryEmpty()) {
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (needsWorld) {
                long worldDeadline = System.currentTimeMillis() + AUTO_RUN_WORLD_WAIT_MILLIS;
                while (System.currentTimeMillis() < worldDeadline
                        && (!inWorld() || (needsCatalogue && !TianshuTools.serverAvailable()))) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            new LabAcceptance(diagnostics).run();
        }, "tianshu-lab-acceptance-auto");
        worker.setDaemon(true);
        acceptanceWorker = worker;
        worker.start();
    }

    public static boolean requiresServerCatalogue() {
        String mode = System.getProperty("tianshu.lab.acceptance", "").trim();
        return "server".equalsIgnoreCase(mode) || requiresCheatCatalogue();
    }

    public static boolean requiresCheatCatalogue() {
        return "server-cheat".equalsIgnoreCase(System.getProperty("tianshu.lab.acceptance", "").trim());
    }

    private static boolean isRegistryEmpty() {
        try {
            return TianshuTools.catalog(ToolCaller.player("lab", true), ToolSide.CLIENT).isEmpty();
        } catch (RuntimeException notReady) {
            return true;
        }
    }

    /** Whether a world is loaded and a player is in it; the world-context cases need both. */
    private boolean inWorld() {
        try {
            String player = AcceptanceEnvironment.readGame(ClientGameContextPort::playerId);
            return player != null && !player.isBlank();
        } catch (RuntimeException notReady) {
            return false;
        }
    }

    @Override
    public void stop() {
        if (acceptanceWorker != null) {
            acceptanceWorker.interrupt();
            acceptanceWorker = null;
        }
        CapabilitySubscriptions.withdraw(LabModuleRegistration.MOD_ID);
        if (participantsRegistered) {
            unregisterParticipants();
        }
        if (workflowRemoval != null) {
            // Both registrations go: a stopped module must not leave a tool the model can call, nor a row pointing at
            // a handler whose module is gone.
            workflowRemoval.run();
            workflowRemoval = null;
        }
    }

    @Override
    public void destroy() {
        AcceptanceEnvironment.clear();
        sessionId = "";
        ownerParticipantId = "";
        lastDeliveryText = "";
        registeredParticipants = "";
        participantsRegistered = false;
    }

    // ---------------------------------------------------------------- LabModuleControl

    @Override
    public boolean debugEnabled() {
        return debugEnabled;
    }

    @Override
    public boolean participantsRegistered() {
        return participantsRegistered;
    }

    @Override
    public boolean sessionHeld() {
        return !sessionId.isBlank();
    }

    @Override
    public boolean autoRelease() {
        return autoRelease;
    }

    @Override
    public void setAutoRelease(boolean autoRelease) {
        this.autoRelease = autoRelease;
    }

    @Override
    public String crosshairFilter() {
        return crosshairFilter;
    }

    @Override
    public void setCrosshairFilter(String value) {
        this.crosshairFilter = value == null ? "" : value.trim();
    }

    @Override
    public String heldItemId() {
        return heldItemId;
    }

    @Override
    public void setHeldItemId(String value) {
        this.heldItemId = value == null ? "" : value.trim();
    }

    @Override
    public String wakeWords() {
        return wakeWords;
    }

    @Override
    public void setWakeWords(String value) {
        this.wakeWords = value == null ? "" : value.trim();
    }

    @Override
    public DialogueHoldTier holdTier() {
        return holdTier;
    }

    @Override
    public void setHoldTier(DialogueHoldTier holdTier) {
        this.holdTier = holdTier == null ? DialogueHoldTier.NONE : holdTier;
    }

    @Override
    public String registeredParticipants() {
        return registeredParticipants;
    }

    /**
     * Re-registers with whatever the page currently holds.
     *
     * <p>Registration upserts by {@code (moduleId, participantId)}, so a changed rule replaces the old one
     * without an unregister in between. A participant whose field became invalid is unregistered explicitly
     * instead, so an empty held item or wake-word list cannot leave a stale "any item / any word" descriptor
     * behind.</p>
     */
    @Override
    public synchronized void registerParticipants() {
        if (!debugEnabled) {
            lastError = "debug switch is off";
            return;
        }
        if (!intakeInstalled()) {
            // Participants are Intake's vocabulary: registering them into nothing would leave the page claiming a
            // participant exists, which is the one thing the bench must never do.
            lastError = INTAKE_UNAVAILABLE_MESSAGE;
            return;
        }
        List<String> registered = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        try {
            adapter.registerParticipant(LabParticipants.alpha(
                    displayName("tianshu.lab.participant.alpha"),
                    LabParticipants.parseList(crosshairFilter), holdTier));
            registered.add(LabParticipants.ALPHA);
        } catch (RuntimeException failure) {
            appendError(errors, "alpha", failure);
        }
        if (heldItemId.isBlank()) {
            adapter.unregisterParticipant(LabParticipants.BETA);
        } else {
            try {
                adapter.registerParticipant(LabParticipants.beta(
                        displayName("tianshu.lab.participant.beta"), heldItemId, holdTier));
                registered.add(LabParticipants.BETA);
            } catch (RuntimeException failure) {
                appendError(errors, "beta", failure);
            }
        }
        List<String> words = LabParticipants.parseList(wakeWords);
        if (words.isEmpty()) {
            adapter.unregisterParticipant(LabParticipants.GAMMA);
        } else {
            try {
                adapter.registerParticipant(LabParticipants.gamma(
                        displayName("tianshu.lab.participant.gamma"), words, holdTier));
                registered.add(LabParticipants.GAMMA);
            } catch (RuntimeException failure) {
                appendError(errors, "gamma", failure);
            }
        }
        registeredParticipants = String.join(",", registered);
        participantsRegistered = !registered.isEmpty();
        lastError = errors.toString();
    }

    @Override
    public synchronized void unregisterParticipants() {
        if (!participantsRegistered) {
            return;
        }
        adapter.unregisterParticipants();
        participantsRegistered = false;
        registeredParticipants = "";
        sessionId = "";
        ownerParticipantId = "";
    }

    @Override
    public void releaseCurrentSession() {
        String current = sessionId;
        String owner = ownerParticipantId;
        if (current.isBlank() || owner.isBlank()) {
            return;
        }
        adapter.releaseSession(current, owner);
        sessionId = "";
        ownerParticipantId = "";
    }

    @Override
    public void extendCurrentSession() {
        String current = sessionId;
        String owner = ownerParticipantId;
        if (current.isBlank() || owner.isBlank()) {
            return;
        }
        adapter.extendSession(current, owner, 10_000L);
    }

    @Override
    public LabStatus status() {
        return new LabStatus(debugEnabled, participantsRegistered, registeredParticipants, sessionHeld(),
                sessionId, ownerParticipantId, lastDeliveryText, lastError);
    }

    // ---------------------------------------------------------------- delivery

    private void handleDelivery(TianshuEnvelope envelope, ProtocolContext context, String participantId) {
        if (!intakeInstalled()) {
            lastError = INTAKE_UNAVAILABLE_MESSAGE;
            context.fail(envelope.envelopeId(), "INTAKE_UNAVAILABLE", INTAKE_UNAVAILABLE_MESSAGE, null);
            return;
        }
        LabIntakeBridge.Delivery payload = LabIntakeBridge.delivery(envelope).orElse(null);
        if (payload == null) {
            lastError = "invalid delivery payload";
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Lab dialogue delivery payload is invalid", null);
            return;
        }
        sessionId = payload.sessionId();
        ownerParticipantId = participantId;
        lastDeliveryText = payload.repairedText();
        String text = payload.repairedText().isBlank() ? payload.normalizedText() : payload.repairedText();
        chat(UiText.key("tianshu.chat.progress.lab.delivery", participantId, text, payload.sessionId()));
        context.complete(envelope.envelopeId());
        if (autoRelease) {
            // Releasing right away keeps the next test turn flowing; with the toggle off the page holds the
            // session so RELEASE and EXTEND can be exercised by hand.
            adapter.releaseSession(payload.sessionId(), participantId);
            sessionId = "";
            ownerParticipantId = "";
        }
    }

    private void chat(UiText text) {
        if (presentation == null || text == null) {
            return;
        }
        Runnable line = () -> presentation.chat(text);
        if (scheduler != null && !scheduler.isOnMainThread()) {
            scheduler.execute(line);
        } else {
            line.run();
        }
    }

    private String displayName(String key) {
        return texts.hasKey(key) ? texts.text(UiText.key(key)) : key;
    }

    /**
     * Whether Intake is installed.
     *
     * <p>The bench's three dialogue participants are Intake vocabulary: without Intake there is no arbitration to
     * register them with, and pretending otherwise would make the page show participants that can never be delivered
     * to. The platform port answers {@code false} when nothing is registered, which is the safe reading.</p>
     */
    private static boolean intakeInstalled() {
        return TianshuRuntimePorts.find(InstalledMods.class)
                .orElseGet(InstalledMods::none)
                .isLoaded("tianshu_intake");
    }
    private static void appendError(StringBuilder errors, String participant, RuntimeException failure) {
        if (errors.length() > 0) {
            errors.append("; ");
        }
        errors.append(participant).append(": ").append(failure.getMessage());
    }
}
