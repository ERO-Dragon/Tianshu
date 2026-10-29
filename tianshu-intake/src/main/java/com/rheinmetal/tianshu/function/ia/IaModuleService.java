package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsSnapshot;
import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsView;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantContractValidator;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueParticipantLifecycleCoordinator;
import com.rheinmetal.tianshu.protocol.registry.ValidationResult;

import java.util.Objects;

/**
 * The service face of the arbiter: who is registered, and the two moments something outside changed that the
 * arbiter has to react to immediately (a participant appearing, the default-owner setting being edited).
 */
public final class IaModuleService {
    private final DialogueParticipantRegistry participantRegistry;
    private final DialogueDiagnosticsView diagnosticsView;
    private final DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator;
    private final DialogueParticipantContractValidator participantContractValidator;
    private final Runnable participantChangeListener;
    private final Runnable defaultOwnerChangeListener;

    public IaModuleService(DialogueParticipantRegistry participantRegistry, DialogueDiagnosticsView diagnosticsView, DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator, DialogueParticipantContractValidator participantContractValidator) {
        this(participantRegistry, diagnosticsView, participantLifecycleCoordinator, participantContractValidator, () -> {
        }, () -> {
        });
    }

    public IaModuleService(DialogueParticipantRegistry participantRegistry, DialogueDiagnosticsView diagnosticsView, DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator, DialogueParticipantContractValidator participantContractValidator, Runnable participantChangeListener) {
        this(participantRegistry, diagnosticsView, participantLifecycleCoordinator, participantContractValidator, participantChangeListener, () -> {
        });
    }

    public IaModuleService(DialogueParticipantRegistry participantRegistry, DialogueDiagnosticsView diagnosticsView, DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator, DialogueParticipantContractValidator participantContractValidator, Runnable participantChangeListener, Runnable defaultOwnerChangeListener) {
        this.participantRegistry = Objects.requireNonNull(participantRegistry, "participantRegistry");
        this.diagnosticsView = Objects.requireNonNull(diagnosticsView, "diagnosticsView");
        this.participantLifecycleCoordinator = Objects.requireNonNull(participantLifecycleCoordinator, "participantLifecycleCoordinator");
        this.participantContractValidator = Objects.requireNonNull(participantContractValidator, "participantContractValidator");
        this.participantChangeListener = participantChangeListener == null ? () -> {
        } : participantChangeListener;
        this.defaultOwnerChangeListener = defaultOwnerChangeListener == null ? () -> {
        } : defaultOwnerChangeListener;
    }

    public void registerParticipant(DialogueParticipantDescriptor descriptor) {
        ValidationResult validation = participantContractValidator.validate(descriptor);
        if (!validation.accepted()) {
            throw new IllegalArgumentException(validation.code() + ": " + validation.message());
        }
        participantRegistry.register(descriptor);
        participantChangeListener.run();
    }

    public void unregisterParticipant(String moduleId, String participantId) {
        participantLifecycleCoordinator.unregisterParticipant(null, moduleId, participantId, System.currentTimeMillis());
        participantChangeListener.run();
    }

    public void unregisterModule(String moduleId) {
        participantLifecycleCoordinator.unregisterModule(null, moduleId, System.currentTimeMillis());
        participantChangeListener.run();
    }

    /**
     * Tells the arbiter that the default-owner setting was edited, so the marker stops showing the old owner.
     *
     * <p>The setting itself is read by the arbiter when it needs it; this call is only about what is already on
     * screen at the moment the player presses save.</p>
     */
    public void defaultOwnerChanged() {
        defaultOwnerChangeListener.run();
    }

    public DialogueDiagnosticsSnapshot snapshot() {
        return diagnosticsView.snapshot();
    }
}