package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.function.auxilium.AXLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.context.AXRuntimeContextFact;
import com.rheinmetal.tianshu.function.auxilium.knowledge.AXKnowledgeHit;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.memory.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXPromptAssemblySnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void chatPromptIsSingleMessageChunkWithExpectedSectionOrder() {
        AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
        AXMemorySnapshot memory = new AXMemorySnapshot(
                "测试人格",
                List.of(new AXMemoryBlockView(
                        stm(scope, "retrieved", "检索命中的 STM：玩家之前问过铁砧。", 1000L),
                        List.of("玩家死亡一次。")
                )),
                List.of(new AXMemoryBlockView(
                        stm(scope, "recent", "近期 STM：玩家正在整理装备。", 2000L),
                        List.of("玩家解锁成就：Stone Age")
                )),
                List.of(
                        raw(scope, "user", "上一句玩家输入", 3000L),
                        AXRawTurn.gameChat(scope, "Alex", "聊天栏消息", 3010L, "chat-1"),
                        raw(scope, "assistant", "AX 的上一句回复", 3020L)
                )
        );
        AXContextSnapshot context = new AXContextSnapshot(
                scope,
                memory,
                List.of(AXRuntimeContextFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                "IA delivery 附带上下文"
        );
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(
                new AXPromptOrchestrator(
                        null,
                        AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN),
                        (request, snapshot, budget) -> List.of(AXKnowledgeHit.of(
                                "ax.static_knowledge.mock",
                                List.of("minecraft:anvil | 铁砧可以修复工具。")
                        )),
                        null
                ),
                new AXContextBudget(4000, 4, 8, 4)
        );

        LLMPromptRequestPayload payload = builder.buildChatRequest(
                new AXRequest("request", "当前输入：这个怎么修？", ""),
                context
        );

        assertEquals(1, payload.chunks().size());
        assertEquals(0, payload.maxTokens());
        assertTrue(!payload.thinking());
        assertEquals("message", payload.chunks().get(0).type());
        List<LLMPromptRequestPayload.MessageItemPayload> messages = payload.chunks().get(0).messageContent();
        assertEquals("user", messages.get(messages.size() - 1).role());
        assertEquals("当前输入：这个怎么修？", messages.get(messages.size() - 1).content());

        String joined = messages.stream().map(LLMPromptRequestPayload.MessageItemPayload::content).collect(java.util.stream.Collectors.joining("\n\n"));
        assertOrdered(joined, "<ax_system>", "<game_context>", "<player_memory>", "<provided_context>", "<recent_dialogue>", "当前输入：这个怎么修？");
        assertTrue(joined.contains("正常对话长度"));
        assertTrue(joined.contains("玩家准星指向 minecraft:anvil"));
        assertTrue(joined.contains("minecraft:anvil | 铁砧可以修复工具。"));
        assertTrue(joined.contains("检索命中的 STM"));
        assertTrue(joined.contains("近期 STM"));
        assertTrue(joined.contains("玩家死亡一次。"));
        assertTrue(joined.contains("玩家解锁成就：Stone Age"));
        assertTrue(joined.contains("Alex：聊天栏消息"));
    }

    @Test
    void externalProfileSectionOrderControlsTopLevelContributorOrder() throws Exception {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptsRoot().resolve("general_ax.zh_cn.default.json"),
                """
                {
                  "schemaVersion": 1,
                  "identity": "测试身份",
                  "behaviorRules": "测试规则",
                  "sectionOrder": [
                    "game_context",
                    "identity",
                    "provided_context",
                    "current_input"
                  ]
                }
                """,
                StandardCharsets.UTF_8
        );
        AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
        AXContextSnapshot context = new AXContextSnapshot(
                scope,
                AXMemorySnapshot.empty(scope),
                List.of(AXRuntimeContextFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                "IA delivery 附带上下文"
        );
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(
                new AXPromptOrchestrator(
                        new AXPromptResourceRepository(layout, new AXJsonStore(new TestLlmSupport.FakeGameEnvironment())),
                        AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN),
                        null
                ),
                new AXContextBudget(4000, 4, 8, 4)
        );

        LLMPromptRequestPayload payload = builder.buildChatRequest(new AXRequest("request", "本轮唯一输入-排序测试", ""), context);

        String joined = payload.chunks().get(0).messageContent().stream()
                .map(LLMPromptRequestPayload.MessageItemPayload::content)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        assertOrdered(joined, "<game_context>", "<ax_system>", "<provided_context>", "本轮唯一输入-排序测试");
    }

    private static AXStmBlock stm(AXScope scope, String id, String content, long createdAtMillis) {
        return new AXStmBlock(id, "", scope.worldId(), createdAtMillis, createdAtMillis - 100L, createdAtMillis, "", "", 1, 0, content, List.of());
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
}
