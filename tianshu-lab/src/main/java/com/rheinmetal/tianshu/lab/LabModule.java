package com.rheinmetal.tianshu.lab;

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
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueHoldTier;
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
public final class LabModule implements TianshuManagedModule, LabModuleControl {
    public static final String MODULE_ID = LabProtocolAdapter.MODULE_ID;

    /** How long an unattended run waits for the tool registry before reporting what it has. */
    private static final long AUTO_RUN_REGISTRY_WAIT_MILLIS = 30_000L;
    /** How long an auto-run asked for the world waits for a world and a player before reporting what it has. */
    private static final long AUTO_RUN_WORLD_WAIT_MILLIS = 180_000L;

    private final LabProtocolAdapter adapter;
    /** The runtime this module was assembled with; the acceptance bench borrows it for execution cases. */
    private final com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess runtime;
    /** Read-only view of the game; the acceptance bench borrows it for the world-context cases. */
    private final com.rheinmetal.tianshu.client.host.game.ClientGameContextPort gameContext;
    private final ClientPresentationPort presentation;
    private final ClientScheduler scheduler;
    private final ClientTextProvider texts;
    private final boolean debugEnabled;

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

    public LabModule(ModuleRuntimeAccess runtime, ClientPorts ports) {
        this.runtime = runtime;
        this.gameContext = ports == null ? null : ports.gameContext();
        this.adapter = new LabProtocolAdapter(Objects.requireNonNull(runtime, "runtime"));
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
        // One capability per participant: the capability that received a delivery names its owner, which is
        // also the participant id a session-control request has to carry.
        adapter.registerDelivery(LabProtocolAdapter.ALPHA_CAPABILITY,
                (envelope, protocolContext) -> handleDelivery(envelope, protocolContext, LabParticipants.ALPHA));
        adapter.registerDelivery(LabProtocolAdapter.BETA_CAPABILITY,
                (envelope, protocolContext) -> handleDelivery(envelope, protocolContext, LabParticipants.BETA));
        adapter.registerDelivery(LabProtocolAdapter.GAMMA_CAPABILITY,
                (envelope, protocolContext) -> handleDelivery(envelope, protocolContext, LabParticipants.GAMMA));
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (context != null) {
            this.diagnostics = context.diagnostics();
        }
        com.rheinmetal.tianshu.lab.acceptance.AcceptanceEnvironment.install(runtime, gameContext);
        if (debugEnabled) {
            registerParticipants();
        }
        autoRunAcceptanceIfRequested();
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
        boolean needsWorld = "world".equalsIgnoreCase(requested.trim());
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
                while (System.currentTimeMillis() < worldDeadline && !inWorld()) {
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
        worker.start();
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
            String player = gameContext == null ? null : gameContext.playerId();
            return player != null && !player.isBlank();
        } catch (RuntimeException notReady) {
            return false;
        }
    }

    @Override
    public void stop() {
        if (participantsRegistered) {
            unregisterParticipants();
        }
    }

    @Override
    public void destroy() {
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
            lastError = "tianshu_intake is not installed; dialogue participants stay disabled";
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
        if (!(envelope.payload() instanceof DialogueDeliveryPayload payload)) {
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