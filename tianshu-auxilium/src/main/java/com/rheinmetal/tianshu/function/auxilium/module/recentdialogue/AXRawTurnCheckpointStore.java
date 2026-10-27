package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.util.List;

public final class AXRawTurnCheckpointStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXRawTurnCheckpointStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXRawTurn> load(AXScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.rawTurnCheckpointFile(scope)).stream()
                .map(AXRawTurn::fromJson)
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
    public boolean append(AXScope scope, AXRawTurn turn) {
        if (!usable(scope) || turn == null || turn.isEmpty()) {
            return false;
        }
        return jsonStore.tryAppendJsonLine(layout.rawTurnCheckpointFile(scope), turn.toJson());
    }

    public void write(AXScope scope, List<AXRawTurn> turns) {
        if (!usable(scope)) {
            return;
        }
        List<AXRawTurn> normalized = turns == null ? List.of() : turns.stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
        jsonStore.writeJsonLines(
                layout.rawTurnCheckpointFile(scope),
                normalized.stream().map(AXRawTurn::toJson).toList()
        );
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
