package com.rheinmetal.tianshu.function.auxilium.module.memory.preference;

import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStoreWriteResult;

import java.util.List;

/**
 * 玩家偏好的 append-only 存储，键是玩家 UUID。
 *
 * <p>与 {@code AXMemoryEventStore} 一样<b>不做去重</b>：偏好是"玩家明示过什么"的客观记录，重复
 * 明示就是两条。文件位置由 {@link AXStorageLayout#playerPreferencesFile(String)} 给出，路径里
 * 不含 worldId，因此偏好跨存档保留（记忆设计 §4.5）。</p>
 */
public final class AXPlayerPreferenceStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXPlayerPreferenceStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXPlayerPreference> loadAll(String playerId) {
        if (!usable(playerId)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.playerPreferencesFile(playerId)).stream()
                .map(AXPlayerPreference::fromJson)
                .filter(preference -> !preference.isEmpty())
                .toList();
    }

    public AXStoreWriteResult appendAll(String playerId, List<AXPlayerPreference> preferences) {
        if (!usable(playerId) || preferences == null || preferences.isEmpty()) {
            return AXStoreWriteResult.failed();
        }
        List<AXPlayerPreference> normalized = preferences.stream()
                .filter(preference -> preference != null && !preference.isEmpty())
                .toList();
        if (normalized.isEmpty()) {
            return AXStoreWriteResult.success(0);
        }
        boolean written = jsonStore.tryAppendJsonLines(
                layout.playerPreferencesFile(playerId),
                normalized.stream().map(AXPlayerPreference::toJson).toList()
        );
        return written ? AXStoreWriteResult.success(normalized.size()) : AXStoreWriteResult.failed();
    }

    private boolean usable(String playerId) {
        return playerId != null && !playerId.isBlank() && layout != null && jsonStore != null;
    }
}
