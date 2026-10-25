package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class ClientDiagnosticPolicy implements Predicate<String> {
    // Ids come from the protocol's single source of truth: the kernel names the modules it expects in
    // the pipeline without importing the jars that implement them.
    private static final Set<String> DIAGNOSTIC_MODULES = Set.of(
            ProtocolModuleIds.ASR,
            ProtocolModuleIds.IA,
            ProtocolModuleIds.AX,
            ProtocolModuleIds.LLM,
            ProtocolModuleIds.TTS
    );

    private final ClientDiagnosticsConfiguration config;

    public ClientDiagnosticPolicy(ClientDiagnosticsConfiguration config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean test(String moduleId) {
        return moduleId != null && config.isDebugEnabled() && DIAGNOSTIC_MODULES.contains(moduleId);
    }
}
