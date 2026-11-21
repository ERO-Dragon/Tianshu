package com.rheinmetal.tianshu.dubhe.module.memory.summary;

import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;

import java.util.List;

/**
 * 摘要块账本（{@code worlds/{worldId}/summary/summary_block.jsonl}）。
 *
 * <p>只做两件事：追加一条换代记录、按代次读出全部记录。它<b>不删除</b>任何东西——被预算淘汰的
 * 摘要只是不再渲染，账本与记忆库都还在，其事件也照常可检索（记忆设计 §4.4 规则 7）。</p>
 */
public final class DubheMemorySummaryBlockStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubheMemorySummaryBlockStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    /** 全部换代记录，按写入顺序（旧代在后）。 */
    public List<DubheMemorySummaryGeneration> loadGenerations(DubheScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.summaryBlockFile(scope)).stream()
                .map(DubheMemorySummaryGeneration::fromJson)
                .filter(generation -> !generation.isEmpty())
                .toList();
    }

    /** 当前代次：账本里最后一条记录的代次，没有记录时为 0。 */
    public int generation(DubheScope scope) {
        List<DubheMemorySummaryGeneration> generations = loadGenerations(scope);
        return generations.isEmpty() ? 0 : generations.get(generations.size() - 1).generation();
    }

    /** 换代时追加一条记录；代次必须比账本里的最后一条大。 */
    public boolean append(DubheScope scope, int generation, List<String> stmIds) {
        if (!usable(scope)) {
            return false;
        }
        DubheMemorySummaryGeneration record = new DubheMemorySummaryGeneration(
                DubheMemorySummaryGeneration.SCHEMA_VERSION,
                generation,
                stmIds,
                0L
        );
        if (record.isEmpty() || generation <= generation(scope)) {
            return false;
        }
        return jsonStore.tryAppendJsonLine(layout.summaryBlockFile(scope), record.toJson());
    }

    private boolean usable(DubheScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
