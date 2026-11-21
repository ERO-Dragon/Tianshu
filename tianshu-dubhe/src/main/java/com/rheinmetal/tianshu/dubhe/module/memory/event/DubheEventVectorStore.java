package com.rheinmetal.tianshu.dubhe.module.memory.event;

import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class DubheEventVectorStore {
    private final DubheStorageLayout layout;
    private final DubheJsonStore jsonStore;

    public DubheEventVectorStore(DubheStorageLayout layout, DubheJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<DubheEventVector> load(DubheScope scope, String embeddingNamespace) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.eventVectorsFile(scope, embeddingNamespace)).stream()
                .map(DubheEventVector::fromJson)
                .filter(vector -> !vector.isEmpty())
                .toList();
    }

    public List<DubheEventVector> loadAllNamespaces(DubheScope scope) {
        if (!usable(scope)) {
            return List.of();
        }
        Path root = layout.vectorsRoot(scope);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root, 2)) {
            return paths
                    .filter(path -> path != null && Files.isRegularFile(path) && "event_vectors.jsonl".equals(path.getFileName().toString()))
                    .flatMap(path -> jsonStore.readJsonLines(path).stream())
                    .map(DubheEventVector::fromJson)
                    .filter(vector -> !vector.isEmpty())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public void append(DubheScope scope, DubheEventVector vector) {
        if (!usable(scope) || vector == null || vector.isEmpty()) {
            return;
        }
        appendAll(scope, List.of(vector));
    }

    public void appendAll(DubheScope scope, List<DubheEventVector> vectors) {
        if (!usable(scope) || vectors == null || vectors.isEmpty()) {
            return;
        }
        vectors.stream()
                .filter(vector -> vector != null && !vector.isEmpty())
                .collect(java.util.stream.Collectors.groupingBy(DubheEventVector::embeddingNamespace))
                .forEach((namespace, values) -> {
                    List<DubheEventVector> normalized = notYetIndexed(scope, namespace, values);
                    jsonStore.appendJsonLines(
                            layout.eventVectorsFile(scope, namespace),
                            normalized.stream().map(DubheEventVector::toJson).toList()
                    );
                });
    }

    /**
     * 索引写入的<b>幂等闸门</b>：只放行索引里还没有的 {@link DubheEventVector#idempotencyKey()}。
     *
     * <p>挡的是"同一次写入被重放"（批次重试、维护补齐重跑、进程重启后续写），<b>不是事件去重</b>：
     * 事件入库从不去重，同一件事发生两次是两条 eventId 不同的事件，两条都会进索引。也正因为判据是
     * {@code (eventId, factHash)} 这对键，同一批次内部的重复向量会在这里一起被去掉。</p>
     */
    private List<DubheEventVector> notYetIndexed(DubheScope scope, String namespace, List<DubheEventVector> values) {
        Set<String> indexed = load(scope, namespace).stream()
                .map(DubheEventVector::idempotencyKey)
                .collect(Collectors.toSet());
        return values.stream()
                .filter(vector -> indexed.add(vector.idempotencyKey()))
                .toList();
    }

    private boolean usable(DubheScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
