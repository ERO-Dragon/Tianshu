package com.rheinmetal.tianshu.function.presence.diagnostics;

import com.rheinmetal.tianshu.api.assembly.ModuleRuntimeServices;
import com.rheinmetal.tianshu.function.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatusQuery;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

import java.util.Comparator;
import java.util.List;

/**
 * A read-only view of the module pipeline, for the debugging page.
 *
 * <p>The pipeline this view reports on is addressed by protocol module ids, so the page degrades to "no status" for an
 * absent module instead of failing to load. It reads the kernel's status cache through the module-services port: no
 * subscription, no history, no privileged access.</p>
 */
public final class PresenceDebugPipelineSnapshot {
    private static final List<ModuleEntry> MODULES = List.of(
            new ModuleEntry(ProtocolModuleIds.ASR, "tianshu.gui.presence.debug.module.asr"),
            new ModuleEntry(ProtocolModuleIds.IA, "tianshu.gui.presence.debug.module.ia"),
            new ModuleEntry(ProtocolModuleIds.AX, "tianshu.gui.presence.debug.module.ax"),
            new ModuleEntry(ProtocolModuleIds.LLM, "tianshu.gui.presence.debug.module.llm"),
            new ModuleEntry(ProtocolModuleIds.TTS, "tianshu.gui.presence.debug.module.tts"),
            new ModuleEntry(PresenceProtocolAdapter.MODULE_ID, "tianshu.gui.presence.debug.module.presence")
    );

    private final ModuleRuntimeServices services;

    public PresenceDebugPipelineSnapshot(ModuleRuntimeServices services) {
        this.services = services;
    }

    public List<Row> rows() {
        return MODULES.stream().map(this::row).toList();
    }

    private Row row(ModuleEntry entry) {
        if (services == null) {
            return new Row(entry.moduleId(), entry.labelKey(), null, null);
        }
        return new Row(
                entry.moduleId(),
                entry.labelKey(),
                latest(services.queryModuleStatuses(new ModuleStatusQuery(entry.moduleId(), ModuleStatuses.TYPE_LIFECYCLE))),
                latest(services.queryModuleStatuses(new ModuleStatusQuery(entry.moduleId(), "")).stream()
                        .filter(status -> !ModuleStatuses.TYPE_LIFECYCLE.equals(status.statusType()))
                        .toList())
        );
    }

    private static ModuleStatus latest(List<ModuleStatus> statuses) {
        return statuses.stream()
                .max(Comparator.comparingLong(ModuleStatus::updatedAtMillis))
                .orElse(null);
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
