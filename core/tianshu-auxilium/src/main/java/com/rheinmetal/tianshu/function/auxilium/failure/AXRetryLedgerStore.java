package com.rheinmetal.tianshu.function.auxilium.failure;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 失败账本的落盘位置与读写。
 *
 * <p>按世界隔离，与 raw 历史同一个层级：一条失败的对话轮属于它发生的那个世界，换世界不该在另一个世界里
 * 发现它。</p>
 *
 * <p>整份重写而不是追加：账本上限只有几十条，一次全量写比"追加 + 压缩 + 墓碑"少三种状态；写盘走
 * {@link AXJsonStore#writeJsonLines} 的临时文件 + 原子替换，所以崩溃不会留下半份账本。</p>
 */
public final class AXRetryLedgerStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXRetryLedgerStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXRetryEntry> load(AXScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        List<AXRetryEntry> entries = new ArrayList<>();
        for (var json : jsonStore.readJsonLines(layout.retryLedgerFile(scope))) {
            AXRetryEntry entry = AXRetryEntry.fromJson(json);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    public void write(AXScope scope, List<AXRetryEntry> entries) {
        if (!usable(scope)) {
            return;
        }
        List<AXRetryEntry> safe = entries == null ? List.of() : entries.stream()
                .filter(entry -> entry != null)
                .toList();
        jsonStore.writeJsonLines(
                layout.retryLedgerFile(scope),
                safe.stream().map(AXRetryEntry::toJson).toList()
        );
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}