package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXLogKnowledgePromptFixtureTest {
    private static final List<String> FALLBACK_SAMPLE_LOG_LINES = List.of(
            "[Render thread/INFO] [com.rheinmetal.tianshu.client.ir.ClientNamedObjectIndexManager/]: IR named object index loaded from cache, objects=1462, reason=client resource reload, file=.\\config\\Tianshu\\module\\ir\\cache\\named-object-ir-cache.bin",
            "[Render thread/WARN] [net.minecraft.client.sounds.SoundEngine/]: Missing sound for event: minecraft:item.goat_horn.play",
            "[modloading-worker-0/INFO] [com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader/]: [jjml] LlamaCpp native: OK",
            "[modloading-worker-0/INFO] [com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader/]: [sherpa-onnx] Native: OK",
            "[Render thread/INFO] [com.rheinmetal.tianshu.client.TianshuClient/]: 检测到客户端退出世界，开始清理...",
            "[Server thread/INFO] [net.minecraft.server.MinecraftServer/]: Preparing start region for dimension minecraft:overworld"
    );

    @Test
    void logKnowledgeIsInjectedIntoGameContextAndSnapshotReportIsWritten() throws Exception {
        List<String> sourceLines = loadLogKnowledgeLines();
        AXScope scope = new AXScope("Dev", "save:Prompt Test", "Prompt Test", AXScopeKind.LOCAL_WORLD, true);
        AXMemorySnapshot memory = new AXMemorySnapshot(
                "玩家偏好简洁说明。",
                List.of(new AXMemoryBlockView(
                        stm(scope, "retrieved", "玩家之前问过资源重载后物品索引为什么会变慢。", 1000L),
                        List.of("玩家在资源重载后等待过 IR 缓存加载。")
                )),
                List.of(new AXMemoryBlockView(
                        stm(scope, "recent", "玩家正在调试 AX 与映迹的上下文展示。", 2000L),
                        List.of()
                ))
        );
        AXContextSnapshot context = new AXContextSnapshot(
                scope,
                memory,
                AXRecentDialogueSnapshot.empty(),
                List.of(
                        AXDynamicFact.of("当前维度：主世界；玩家正在打开调试 HUD。", 90, "presence.fixture"),
                        AXDynamicFact.of("当前准星目标：minecraft:anvil。", 80, "presence.fixture")
                ),
                "IA 上下文：玩家正在进行 AX 一期假数据 RAG 测试。"
        );
        AXRequest request = new AXRequest("ax.fixture.log_rag", "刚才资源重载之后，日志里显示哪个 AX 相关缓存被加载？", "");
        AXContextBudget budget = new AXContextBudget(4000, 4, 8, 4);
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(
                null,
                AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN),
                new LogLineKnowledgePlanner(sourceLines),
                null
        ));

        LLMPromptRequestPayload payload = builder.buildChatRequest(request, context, budget);
        List<LLMPromptRequestPayload.MessageItemPayload> messages = payload.chunks().get(0).messageContent();
        String joined = renderMessages(messages);

        assertEquals(1, payload.chunks().size());
        assertEquals("message", payload.chunks().get(0).type());
        assertOrdered(joined, "<ax_system>", "<game_context>", "<player_memory>", request.userText());
        assertTrue(joined.contains("<game_context>"));
        assertTrue(joined.contains("动态内容"));
        assertTrue(joined.contains("静态内容"));
        assertTrue(joined.contains("IR named object index loaded from cache"));
        assertTrue(joined.contains("当前维度：主世界"));
        assertTrue(joined.contains("玩家之前问过资源重载后物品索引为什么会变慢"));

        writeSnapshotReport(request, sourceLines, messages);
    }

    private static List<String> loadLogKnowledgeLines() {
        List<String> lines = new ArrayList<>();
        lines.addAll(FALLBACK_SAMPLE_LOG_LINES);
        for (Path file : candidateLogFiles()) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (var stream = Files.lines(file, StandardCharsets.UTF_8)) {
                stream.map(line -> line == null ? "" : line.trim())
                        .filter(line -> !line.isBlank())
                        .filter(AXLogKnowledgePromptFixtureTest::isUsefulLogLine)
                        .forEach(lines::add);
            } catch (Exception ignored) {
            }
        }
        return lines.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(12)
                .toList();
    }

    private static List<Path> candidateLogFiles() {
        Path root = workspaceRoot();
        return List.of(
                root.resolve("tianshu-neoforge/run/logs/latest.log"),
                root.resolve("tianshu-neoforge/run/logs/debug.log")
        );
    }

    private static boolean isUsefulLogLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.contains("com.rheinmetal.tianshu")
                || normalized.contains("cache")
                || normalized.contains("reload")
                || normalized.contains("resource")
                || normalized.contains("module")
                || normalized.contains("llm")
                || normalized.contains("rag")
                || normalized.contains("memory")
                || normalized.contains("prompt")
                || normalized.contains("minecraft")
                || normalized.contains("world")
                || normalized.contains("native");
    }

    private static void writeSnapshotReport(AXRequest request, List<String> sourceLines, List<LLMPromptRequestPayload.MessageItemPayload> messages) throws Exception {
        Path commonRoot = commonRoot();
        Path report = commonRoot.resolve("build/reports/ax/log-rag-prompt-snapshot.md");
        Files.createDirectories(report.getParent());
        String markdown = "# AX fake log RAG prompt snapshot\n\n"
                + "## Request\n\n"
                + "```text\n" + request.userText() + "\n```\n\n"
                + "## Fake log knowledge source\n\n"
                + sourceLines.stream()
                .map(line -> "- `" + line.replace("`", "'") + "`")
                .collect(Collectors.joining("\n"))
                + "\n\n## Assembled messages\n\n"
                + messages.stream()
                .map(message -> "### role: " + message.role() + "\n\n```text\n" + message.content() + "\n```")
                .collect(Collectors.joining("\n\n"));
        Files.writeString(report, markdown, StandardCharsets.UTF_8);
    }

    private static Path commonRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("src/test/java")) && current.getFileName() != null && "tianshu-common".equals(current.getFileName().toString())) {
            return current;
        }
        Path common = current.resolve("tianshu-common");
        return Files.isDirectory(common) ? common : current;
    }

    private static Path workspaceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("tianshu-neoforge/run/logs"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("tianshu-neoforge/run/logs"))) {
            return parent;
        }
        return current;
    }

    private static String renderMessages(List<LLMPromptRequestPayload.MessageItemPayload> messages) {
        return messages.stream()
                .map(message -> "[" + message.role() + "]\n" + message.content())
                .collect(Collectors.joining("\n\n"));
    }

    private static AXStmBlock stm(AXScope scope, String id, String content, long createdAtMillis) {
        return new AXStmBlock("stm_" + id, "", scope.worldId(), createdAtMillis, createdAtMillis - 100L, createdAtMillis, "", "", 1, 0, content, List.of());
    }

    private static AXRawTurn raw(AXScope scope, String role, String content, long createdAtMillis) {
        return new AXRawTurn("", role, content, createdAtMillis, scope.worldId(), "session", "turn", 0, 0, "");
    }

    private static void assertOrdered(String text, String... fragments) {
        int cursor = -1;
        for (String fragment : fragments) {
            int index = text.indexOf(fragment);
            assertTrue(index > cursor, "fragment out of order or missing: " + fragment);
            cursor = index;
        }
    }

    private static final class LogLineKnowledgePlanner implements AXStaticKnowledgePlanner {
        private final List<String> lines;

        private LogLineKnowledgePlanner(List<String> lines) {
            this.lines = lines == null ? List.of() : List.copyOf(lines);
        }

        @Override
        public List<AXKnowledgeHit> plan(AXRequest request, AXContextSnapshot context, AXContextBudget budget) {
            Set<String> terms = queryTerms(request == null ? "" : request.userText());
            List<String> matches = lines.stream()
                    .filter(line -> matches(line, terms))
                    .limit(4)
                    .toList();
            return matches.isEmpty() ? List.of() : List.of(AXKnowledgeHit.of("ax.fake_log_knowledge", matches));
        }

        private boolean matches(String line, Set<String> terms) {
            if (line == null || line.isBlank()) {
                return false;
            }
            String normalized = line.toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (normalized.contains(term)) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> queryTerms(String query) {
            String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
            Set<String> terms = new LinkedHashSet<>();
            addTerm(terms, normalized, "resource", "resource");
            addTerm(terms, normalized, "reload", "reload");
            addTerm(terms, normalized, "cache", "cache");
            addTerm(terms, normalized, "ir", "ir");
            addTerm(terms, normalized, "tianshu", "tianshu");
            addTerm(terms, normalized, "资源", "resource");
            addTerm(terms, normalized, "重载", "reload");
            addTerm(terms, normalized, "缓存", "cache");
            return terms.isEmpty() ? Set.of("tianshu") : Set.copyOf(terms);
        }

        private void addTerm(Set<String> terms, String query, String trigger, String term) {
            if (query.contains(trigger)) {
                terms.add(term);
            }
        }
    }
}
