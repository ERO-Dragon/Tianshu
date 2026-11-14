package com.rheinmetal.tianshu.function.auxilium.storage;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.nio.file.Path;

public final class AXStorageLayout {
    private final Path root;

    public AXStorageLayout(AuxiliumStorageConfiguration configuration) {
        if (configuration == null || configuration.storageRoot() == null) {
            throw new IllegalArgumentException("AX storage configuration is required");
        }
        this.root = configuration.storageRoot().normalize();
    }

    public Path root() {
        return root;
    }

    public Path sharedRoot() {
        return root.resolve("shared");
    }

    public Path worldsRoot() {
        return root.resolve("worlds");
    }

    /**
     * 玩家级存储根目录：{@code players/{playerId}/}。
     *
     * <p>与 {@link #worldsRoot()} 并列而不是嵌套——玩家偏好跨存档/跨世界保留，键内不含 worldId
     * （记忆设计 §4.5）。</p>
     */
    public Path playersRoot() {
        return root.resolve("players");
    }

    public Path playerRoot(String playerId) {
        return playersRoot().resolve(safeName(playerId));
    }

    public Path playerPreferencesFile(String playerId) {
        return playerRoot(playerId).resolve("preferences.jsonl");
    }

    public Path worldRoot(AXScope scope) {
        String worldId = scope == null ? "unknown_world" : scope.worldId();
        return worldsRoot().resolve(safeName(worldId));
    }

    public Path worldManifestFile(AXScope scope) {
        return worldRoot(scope).resolve("manifest.json");
    }

    public Path promptsRoot() {
        return sharedRoot().resolve("prompts");
    }

    public Path memoryTaskPromptsFile() {
        return promptsRoot().resolve("memory_tasks.json");
    }

    public Path promptTextsFile() {
        return promptsRoot().resolve("prompt_texts.json");
    }

    public Path rawTurnsRoot(AXScope scope) {
        return worldRoot(scope).resolve("raw_turns");
    }

    public Path rawTurnCheckpointFile(AXScope scope) {
        return rawTurnsRoot(scope).resolve("raw_turn_checkpoint.jsonl");
    }

    public Path stmBlocksRoot(AXScope scope) {
        return worldRoot(scope).resolve("stm_blocks");
    }

    public Path stmBlocksFile(AXScope scope) {
        return stmBlocksRoot(scope).resolve("stm_blocks.jsonl");
    }

    public Path summaryRoot(AXScope scope) {
        return worldRoot(scope).resolve("summary");
    }

    /**
     * 摘要块账本：每次换代追加一条"本代晋升进块的单元摘要 id + 代次"。
     *
     * <p>append-only：换代只追加，不改写、不删除，被预算淘汰的摘要因此永远留在记忆库里。</p>
     */
    public Path summaryBlockFile(AXScope scope) {
        return summaryRoot(scope).resolve("summary_block.jsonl");
    }

    public Path eventsRoot(AXScope scope) {
        return worldRoot(scope).resolve("events");
    }

    public Path retryRoot(AXScope scope) {
        return worldRoot(scope).resolve("retry");
    }

    /**
     * 失败账本：还没办成、玩家可能还想再来一次的后台工作。
     *
     * <p>放在世界目录下而不是共享目录：一条失败的对话轮属于它发生的那个世界，换世界不该在另一个世界里
     * 看到它。</p>
     */
    public Path retryLedgerFile(AXScope scope) {
        return retryRoot(scope).resolve("pending_retries.jsonl");
    }
    public Path eventsFile(AXScope scope) {
        return eventsRoot(scope).resolve("events.jsonl");
    }

    public Path vectorsRoot(AXScope scope) {
        return worldRoot(scope).resolve("vectors");
    }

    public Path eventVectorsRoot(AXScope scope, String embeddingNamespace) {
        return vectorsRoot(scope).resolve(safeName(embeddingNamespace));
    }

    public Path eventVectorsFile(AXScope scope, String embeddingNamespace) {
        return eventVectorsRoot(scope, embeddingNamespace).resolve("event_vectors.jsonl");
    }

    public Path indexesRoot(AXScope scope) {
        return worldRoot(scope).resolve("indexes");
    }

    public Path retrievalIndexRoot(AXScope scope, String embeddingNamespace) {
        return indexesRoot(scope).resolve(safeName(embeddingNamespace));
    }

    public Path retrievalIndexSnapshotFile(AXScope scope, String embeddingNamespace) {
        return retrievalIndexRoot(scope, embeddingNamespace).resolve("retrieval_index_snapshot.json");
    }

    public Path statsRoot(AXScope scope) {
        return worldRoot(scope).resolve("stats");
    }

    public Path memoryStatsFile(AXScope scope) {
        return statsRoot(scope).resolve("memory_stats.json");
    }

    public static String safeName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
