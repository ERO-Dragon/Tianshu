package com.rheinmetal.tianshu.diagnostics;

import com.rheinmetal.tianshu.client.config.ClientDiagnosticsConfiguration;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ir.IrProtocolAdapter;
import com.rheinmetal.tianshu.function.llm.LlmProtocolAdapter;
import com.rheinmetal.tianshu.function.tts.TtsProtocolAdapter;

import java.util.Objects;
import java.util.function.Predicate;

public final class ClientDiagnosticPolicy implements Predicate<String> {
    private final ClientDiagnosticsConfiguration config;

    public ClientDiagnosticPolicy(ClientDiagnosticsConfiguration config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean test(String moduleId) {
        if (moduleId == null) {
            return false;
        }
        return switch (moduleId) {
            case AsrProtocolAdapter.MODULE_ID -> config.isAsrDiagnosticsEnabled();
            case IrProtocolAdapter.MODULE_ID -> config.isIrDiagnosticsEnabled();
            case IaProtocolAdapter.MODULE_ID -> config.isIaDiagnosticsEnabled();
            case AXModule.MODULE_ID -> config.isAxDiagnosticsEnabled();
            case LlmProtocolAdapter.MODULE_ID -> config.isLlmDiagnosticsEnabled();
            case TtsProtocolAdapter.MODULE_ID -> config.isTtsDiagnosticsEnabled();
            default -> false;
        };
    }
}
