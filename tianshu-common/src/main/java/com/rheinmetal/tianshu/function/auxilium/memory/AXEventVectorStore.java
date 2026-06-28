package com.rheinmetal.tianshu.function.auxilium.memory;

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
                    Set<String> existing = load(scope, namespace).stream()
                            .map(vector -> vector.eventId() + "\n" + vector.eventFactHash())
                            .collect(Collectors.toSet());
                    List<AXEventVector> normalized = values.stream()
                            .filter(vector -> existing.add(vector.eventId() + "\n" + vector.eventFactHash()))
                            .toList();
                    jsonStore.appendJsonLines(
                            layout.eventVectorsFile(scope, namespace),
                            normalized.stream().map(AXEventVector::toJson).toList()
                    );
                });
    }

    private boolean usable(AXScope scope) {
        return scope != null && scope.writable() && layout != null && jsonStore != null;
    }
}
