package com.rheinmetal.tianshu.dubhe.failure;

import com.rheinmetal.tianshu.dubhe.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.storage.DubheStorageLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 失败账本的落盘位置与读写。
 *
 * <p>按世界隔离，与 raw 历史同一个层级：一条失败的对话轮属于它发生的那个世界，换世界不该在另一个世界里
 * 发现它。</p>
 *
 * <p>整份重写而不是追加：账本上限只有几十条，一次全量写比"追加 + 压缩 + 墓碑"少三种状态；写盘走
 * {@link DubheJsonStore#writeJsonLines} 的临时文件 + 原子替换，所以崩溃不会留下半份账本。</p>
 */
public final class DubheRetryLedgerStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubheRetryLedgerStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<DubheRetryEntry> load(DubheScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        List<DubheRetryEntry> entries = new ArrayList<>();
        for (var json : jsonStore.readJsonLines(layout.retryLedgerFile(scope))) {
            DubheRetryEntry entry = DubheRetryEntry.fromJson(json);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    public void write(DubheScope scope, List<DubheRetryEntry> entries) {
        if (!usable(scope)) {
            return;
        }
        List<DubheRetryEntry> safe = entries == null ? List.of() : entries.stream()
                .filter(entry -> entry != null)
                .toList();
        jsonStore.writeJsonLines(
                layout.retryLedgerFile(scope),
                safe.stream().map(DubheRetryEntry::toJson).toList()
        );
    }

    private boolean usable(DubheScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}