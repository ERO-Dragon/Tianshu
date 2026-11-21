package com.rheinmetal.tianshu.dubhe.module.memory.preference;

import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStoreWriteResult;

import java.util.List;

/**
 * 玩家偏好的 append-only 存储，键是玩家 UUID。
 *
 * <p>与 {@code DubheMemoryEventStore} 一样<b>不做去重</b>：偏好是"玩家明示过什么"的客观记录，重复
 * 明示就是两条。文件位置由 {@link DubheStorageLayout#playerPreferencesFile(String)} 给出，路径里
 * 不含 worldId，因此偏好跨存档保留（记忆设计 §4.5）。</p>
 */
public final class DubhePlayerPreferenceStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubhePlayerPreferenceStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<DubhePlayerPreference> loadAll(String playerId) {
        if (!usable(playerId)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.playerPreferencesFile(playerId)).stream()
                .map(DubhePlayerPreference::fromJson)
                .filter(preference -> !preference.isEmpty())
                .toList();
    }

    public DubheStoreWriteResult appendAll(String playerId, List<DubhePlayerPreference> preferences) {
        if (!usable(playerId) || preferences == null || preferences.isEmpty()) {
            return DubheStoreWriteResult.failed();
        }
        List<DubhePlayerPreference> normalized = preferences.stream()
                .filter(preference -> preference != null && !preference.isEmpty())
                .toList();
        if (normalized.isEmpty()) {
            return DubheStoreWriteResult.success(0);
        }
        boolean written = jsonStore.tryAppendJsonLines(
                layout.playerPreferencesFile(playerId),
                normalized.stream().map(DubhePlayerPreference::toJson).toList()
        );
        return written ? DubheStoreWriteResult.success(normalized.size()) : DubheStoreWriteResult.failed();
    }

    private boolean usable(String playerId) {
        return playerId != null && !playerId.isBlank() && layout != null && jsonStore != null;
    }
}
