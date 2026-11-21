package com.rheinmetal.tianshu.dubhe.module.recentdialogue;

import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;

import java.util.List;

public final class DubheRawTurnCheckpointStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubheRawTurnCheckpointStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<DubheRawTurn> load(DubheScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.rawTurnCheckpointFile(scope)).stream()
                .map(DubheRawTurn::fromJson)
                .filter(turn -> !turn.isEmpty())
                .toList();
    }

    /**
     * 写前落盘：把刚发生的一轮追加到文件末尾，不动已经在盘上的内容。
     *
     * <p>这是崩溃安全的全部要害。轮次在内存里一落下就追加一行，所以"玩家说了什么"在请求发出之前就已经在
     * 盘上；进程被强杀也丢不掉它。整份重写留到 {@link #write}，只在窗口挤掉旧轮次、换世界、停止时做，
     * 因为那才是文件内容真的变了的时候。</p>
     *
     * @return 是否真的写下去了；不可写的世界或空轮次返回 {@code false}
     */
    public boolean append(DubheScope scope, DubheRawTurn turn) {
        if (!usable(scope) || turn == null || turn.isEmpty()) {
            return false;
        }
        return jsonStore.tryAppendJsonLine(layout.rawTurnCheckpointFile(scope), turn.toJson());
    }

    public void write(DubheScope scope, List<DubheRawTurn> turns) {
        if (!usable(scope)) {
            return;
        }
        List<DubheRawTurn> normalized = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        jsonStore.writeJsonLines(
                layout.rawTurnCheckpointFile(scope),
                normalized.stream().map(DubheRawTurn::toJson).toList()
        );
    }

    private boolean usable(DubheScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
