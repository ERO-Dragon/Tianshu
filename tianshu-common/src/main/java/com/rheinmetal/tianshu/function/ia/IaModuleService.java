package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsSnapshot;
import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsView;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantContractValidator;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueParticipantLifecycleCoordinator;
import com.rheinmetal.tianshu.protocol.registry.ValidationResult;

import java.util.Objects;

public final class IaModuleService {
    private final DialogueParticipantRegistry participantRegistry;
    private final DialogueDiagnosticsView diagnosticsView;
    private final DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator;
    private final DialogueParticipantContractValidator participantContractValidator;
    private final Runnable participantChangeListener;

    public IaModuleService(DialogueParticipantRegistry participantRegistry, DialogueDiagnosticsView diagnosticsView, DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator, DialogueParticipantContractValidator participantContractValidator) {
        this(participantRegistry, diagnosticsView, participantLifecycleCoordinator, participantContractValidator, () -> {
        });
    }

    public IaModuleService(DialogueParticipantRegistry participantRegistry, DialogueDiagnosticsView diagnosticsView, DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator, DialogueParticipantContractValidator participantContractValidator, Runnable participantChangeListener) {
        this.participantRegistry = Objects.requireNonNull(participantRegistry, "participantRegistry");
        this.diagnosticsView = Objects.requireNonNull(diagnosticsView, "diagnosticsView");
        this.participantLifecycleCoordinator = Objects.requireNonNull(participantLifecycleCoordinator, "participantLifecycleCoordinator");
        this.participantContractValidator = Objects.requireNonNull(participantContractValidator, "participantContractValidator");
        this.participantChangeListener = participantChangeListener == null ? () -> {
        } : participantChangeListener;
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

    public DialogueDiagnosticsSnapshot snapshot() {
        return diagnosticsView.snapshot();
    }
}
