package com.rheinmetal.tianshu.client.presence.diagnostics;

import com.rheinmetal.tianshu.client.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.llm.LlmProtocolAdapter;
import com.rheinmetal.tianshu.function.tts.TtsProtocolAdapter;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.List;

public final class PresenceDebugPipelineSnapshot {
    private static final List<ModuleEntry> MODULES = List.of(
            new ModuleEntry(AsrProtocolAdapter.MODULE_ID, "tianshu.gui.presence.debug.module.asr"),
            new ModuleEntry(IaProtocolAdapter.MODULE_ID, "tianshu.gui.presence.debug.module.ia"),
            new ModuleEntry(AXProtocolAdapter.MODULE_ID, "tianshu.gui.presence.debug.module.ax"),
            new ModuleEntry(LlmProtocolAdapter.MODULE_ID, "tianshu.gui.presence.debug.module.llm"),
            new ModuleEntry(TtsProtocolAdapter.MODULE_ID, "tianshu.gui.presence.debug.module.tts"),
            new ModuleEntry(PresenceProtocolAdapter.MODULE_ID, "tianshu.gui.presence.debug.module.presence")
    );

    private final TianshuCoreManager coreManager;

    public PresenceDebugPipelineSnapshot(TianshuCoreManager coreManager) {
        this.coreManager = coreManager;
    }

    public List<Row> rows() {
        return MODULES.stream().map(this::row).toList();
    }

    private Row row(ModuleEntry entry) {
        ModuleStatus status = null;
        if (coreManager != null) {
            status = coreManager.latestModuleStatus(entry.moduleId()).orElse(null);
        }
        return new Row(entry.moduleId(), entry.labelKey(), status);
    }

    public record Row(String moduleId, String labelKey, ModuleStatus status) {
        public boolean available() {
            return status != null;
        }
    }

    private record ModuleEntry(String moduleId, String labelKey) {
    }
}
