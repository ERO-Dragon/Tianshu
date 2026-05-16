package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsSnapshot;
import com.rheinmetal.tianshu.function.ia.diagnostics.DialogueDiagnosticsView;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.runtime.DialogueParticipantLifecycleCoordinator;

import java.util.Objects;

public final class IaModuleService {
    private final DialogueParticipantRegistry participantRegistry;
    private final DialogueDiagnosticsView diagnosticsView;
    private final DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator;

    public IaModuleService(DialogueParticipantRegistry participantRegistry, DialogueDiagnosticsView diagnosticsView, DialogueParticipantLifecycleCoordinator participantLifecycleCoordinator) {
        this.participantRegistry = Objects.requireNonNull(participantRegistry, "participantRegistry");
        this.diagnosticsView = Objects.requireNonNull(diagnosticsView, "diagnosticsView");
        this.participantLifecycleCoordinator = Objects.requireNonNull(participantLifecycleCoordinator, "participantLifecycleCoordinator");
    }

    public void registerParticipant(DialogueParticipantDescriptor descriptor) {
        participantRegistry.register(descriptor);
    }

    public void unregisterParticipant(String moduleId, String participantId) {
        participantLifecycleCoordinator.unregisterParticipant(null, moduleId, participantId, System.currentTimeMillis());
    }

    public void unregisterModule(String moduleId) {
        participantLifecycleCoordinator.unregisterModule(null, moduleId, System.currentTimeMillis());
    }

    public DialogueDiagnosticsSnapshot snapshot() {
        return diagnosticsView.snapshot();
    }
}
