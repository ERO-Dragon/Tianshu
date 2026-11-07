package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientPresentationPort;
import com.rheinmetal.tianshu.client.host.ClientScheduler;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.Objects;

/**
 * The dev-only bench module: two participants that can own a spoken turn.
 *
 * <p>It registers through the public IA protocol and receives the body on its own capabilities, so from
 * IA's point of view it is indistinguishable from a third-party NPC or assistant module. The only special
 * thing is its purpose: it exists so a developer can watch arbitration pick a participant, deliver the
 * turn and finish a session without needing another real module installed.</p>
 */
public final class LabModule implements TianshuManagedModule, LabModuleControl {
    public static final String MODULE_ID = LabProtocolAdapter.MODULE_ID;

    private final LabProtocolAdapter adapter;
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

    public LabModule(ModuleRuntimeAccess runtime, ClientPorts ports) {
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
        // One capability per participant: the capability that received a delivery names its owner, which
        // is also the participant id a session-control request has to carry.
        adapter.registerDelivery(LabProtocolAdapter.ALPHA_CAPABILITY,
                (envelope, protocolContext) -> handleDelivery(envelope, protocolContext, LabParticipants.ALPHA));
        adapter.registerDelivery(LabProtocolAdapter.BETA_CAPABILITY,
                (envelope, protocolContext) -> handleDelivery(envelope, protocolContext, LabParticipants.BETA));
    }

    @Override
    public void start(ModuleRuntimeContext context) {
        if (debugEnabled) {
            registerParticipants();
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
    public synchronized void registerParticipants() {
        if (!debugEnabled) {
            lastError = "debug switch is off";
            return;
        }
        adapter.registerParticipant(LabParticipants.alpha(displayName("tianshu.lab.participant.alpha")));
        adapter.registerParticipant(LabParticipants.beta(displayName("tianshu.lab.participant.beta")));
        participantsRegistered = true;
        lastError = "";
    }

    @Override
    public synchronized void unregisterParticipants() {
        if (!participantsRegistered) {
            return;
        }
        adapter.unregisterParticipants();
        participantsRegistered = false;
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
        return new LabStatus(debugEnabled, participantsRegistered, sessionHeld(), sessionId,
                ownerParticipantId, lastDeliveryText, lastError);
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
        lastError = "";
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
}