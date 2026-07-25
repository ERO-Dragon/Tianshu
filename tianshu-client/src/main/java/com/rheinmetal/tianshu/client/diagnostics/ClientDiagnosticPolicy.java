package com.rheinmetal.tianshu.client.diagnostics;

import com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ir.IrProtocolAdapter;
import com.rheinmetal.tianshu.function.llm.LlmProtocolAdapter;
import com.rheinmetal.tianshu.function.tts.TtsProtocolAdapter;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class ClientDiagnosticPolicy implements Predicate<String> {
    private static final Set<String> DIAGNOSTIC_MODULES = Set.of(
            AsrProtocolAdapter.MODULE_ID,
            IrProtocolAdapter.MODULE_ID,
            IaProtocolAdapter.MODULE_ID,
            AXModule.MODULE_ID,
            LlmProtocolAdapter.MODULE_ID,
            TtsProtocolAdapter.MODULE_ID
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
