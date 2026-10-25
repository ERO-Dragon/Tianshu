package com.rheinmetal.tianshu.client.presence.diagnostics;

import com.rheinmetal.tianshu.client.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;

import java.util.List;

public final class PresenceDebugPipelineSnapshot {
    // The pipeline this view reports on is addressed by protocol module ids, so the HUD degrades to
    // "no status" for an absent module instead of failing to load.
    private static final List<ModuleEntry> MODULES = List.of(
            new ModuleEntry(ProtocolModuleIds.ASR, "tianshu.gui.presence.debug.module.asr"),
            new ModuleEntry(ProtocolModuleIds.IA, "tianshu.gui.presence.debug.module.ia"),
            new ModuleEntry(ProtocolModuleIds.AX, "tianshu.gui.presence.debug.module.ax"),
            new ModuleEntry(ProtocolModuleIds.LLM, "tianshu.gui.presence.debug.module.llm"),
            new ModuleEntry(ProtocolModuleIds.TTS, "tianshu.gui.presence.debug.module.tts"),
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
        ModuleStatus lifecycleStatus = null;
        ModuleStatus operationStatus = null;
        if (coreManager != null) {
            lifecycleStatus = coreManager.latestModuleStatus(entry.moduleId()).orElse(null);
            operationStatus = coreManager.latestModuleOperationStatus(entry.moduleId()).orElse(null);
        }
        return new Row(entry.moduleId(), entry.labelKey(), lifecycleStatus, operationStatus);
    }

    public record Row(
            String moduleId,
            String labelKey,
            ModuleStatus lifecycleStatus,
            ModuleStatus operationStatus
    ) {
        public ModuleStatus status() {
            return lifecycleStatus != null ? lifecycleStatus : operationStatus;
        }

        public boolean available() {
            return lifecycleStatus != null || operationStatus != null;
        }
    }

    private record ModuleEntry(String moduleId, String labelKey) {
    }
}
