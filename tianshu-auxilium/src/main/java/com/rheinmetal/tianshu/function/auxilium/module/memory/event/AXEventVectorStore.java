package com.rheinmetal.tianshu.function.auxilium.module.memory.event;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class AXEventVectorStore {
    private final AXStorageLayout layout;
    private final AXJsonStore jsonStore;

    public AXEventVectorStore(AXStorageLayout layout, AXJsonStore jsonStore) {
        this.layout = layout;
        this.jsonStore = jsonStore;
    }

    public List<AXEventVector> load(AXScope scope, String embeddingNamespace) {
        if (!usable(scope)) {
            return List.of();
        }
        return jsonStore.readJsonLines(layout.eventVectorsFile(scope, embeddingNamespace)).stream()
                .map(AXEventVector::fromJson)
                .filter(vector -> !vector.isEmpty())
                .toList();
    }

    public List<AXEventVector> loadAllNamespaces(AXScope scope) {
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
                    .map(AXEventVector::fromJson)
                    .filter(vector -> !vector.isEmpty())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public void append(AXScope scope, AXEventVector vector) {
        if (!usable(scope) || vector == null || vector.isEmpty()) {
            return;
        }
        appendAll(scope, List.of(vector));
    }

    public void appendAll(AXScope scope, List<AXEventVector> vectors) {
        if (!usable(scope) || vectors == null || vectors.isEmpty()) {
            return;
        }
        vectors.stream()
                .filter(vector -> vector != null && !vector.isEmpty())
                .collect(java.util.stream.Collectors.groupingBy(AXEventVector::embeddingNamespace))
                .forEach((namespace, values) -> {
                    List<AXEventVector> normalized = notYetIndexed(scope, namespace, values);
                    jsonStore.appendJsonLines(
                            layout.eventVectorsFile(scope, namespace),
                            normalized.stream().map(AXEventVector::toJson).toList()
                    );
                });
    }

    /**
     * 索引写入的<b>幂等闸门</b>：只放行索引里还没有的 {@link AXEventVector#idempotencyKey()}。
     *
     * <p>挡的是"同一次写入被重放"（批次重试、维护补齐重跑、进程重启后续写），<b>不是事件去重</b>：
     * 事件入库从不去重，同一件事发生两次是两条 eventId 不同的事件，两条都会进索引。也正因为判据是
     * {@code (eventId, factHash)} 这对键，同一批次内部的重复向量会在这里一起被去掉。</p>
     */
    private List<AXEventVector> notYetIndexed(AXScope scope, String namespace, List<AXEventVector> values) {
        Set<String> indexed = load(scope, namespace).stream()
                .map(AXEventVector::idempotencyKey)
                .collect(Collectors.toSet());
        return values.stream()
                .filter(vector -> indexed.add(vector.idempotencyKey()))
                .toList();
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
