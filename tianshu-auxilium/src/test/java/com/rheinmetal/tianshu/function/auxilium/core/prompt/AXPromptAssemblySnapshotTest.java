package com.rheinmetal.tianshu.function.auxilium.core.prompt;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.function.auxilium.core.llm.AXLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.function.auxilium.AuxiliumAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXDynamicFact;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRecentDialogueSnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.gamecontext.AXKnowledgeHit;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemoryBlockView;
import com.rheinmetal.tianshu.function.auxilium.module.memory.AXMemorySnapshot;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.memory.shortterm.AXStmBlock;
import com.rheinmetal.tianshu.function.auxilium.module.memory.summary.AXMemorySummaryBlock;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptResourceRepository;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScopeKind;
import com.rheinmetal.tianshu.function.auxilium.storage.AXJsonStore;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
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
                List.of(new AXMemoryBlockView(
                        stm(scope, "retrieved", "玩家之前问过铁砧。", 1000L),
                        List.of("玩家死亡一次。")
                )),
                new AXMemorySummaryBlock(
                        3,
                        List.of(new AXMemoryBlockView(
                                stm(scope, "recent", "玩家正在整理装备。", 2000L),
                                List.of("玩家解锁成就：Stone Age")
                        )),
                        0,
                        0
                )
        );
        AXContextSnapshot context = new AXContextSnapshot(
                scope,
                memory,
                new AXRecentDialogueSnapshot(List.of(
                        AXRawTurn.worldEvent(scope, "Alex", "聊天栏消息", 3000L, "turn")
                )),
                List.of(AXDynamicFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                "IA delivery 附带上下文"
        );
        AXContextBudget budget = AXContextBudget.DEFAULT;
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(
                null,
                AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN),
                (request, snapshot, contextBudget) -> List.of(AXKnowledgeHit.of(
                        "ax.static_knowledge.mock",
                        List.of("minecraft:anvil | 铁砧可以修复工具。")
                )),
                null
        ));

        LLMPromptRequestPayload payload = builder.buildChatRequest(
                new AXRequest("request", "当前输入：这个怎么修？", ""),
                context,
                budget
        );

        assertEquals(1, payload.chunks().size());
        assertEquals(0, payload.maxTokens());
        assertTrue(!payload.thinking());
        assertEquals("message", payload.chunks().get(0).type());
        List<LLMPromptRequestPayload.MessageItemPayload> messages = payload.chunks().get(0).messageContent();
        // system 在最前且唯一
        assertEquals("system", messages.get(0).role());
        long systemCount = messages.stream().filter(m -> "system".equals(m.role())).count();
        assertEquals(1, systemCount, "system 消息应唯一");
        // 最后一条是当前输入
        assertEquals("user", messages.get(messages.size() - 1).role());
        assertEquals("当前输入：这个怎么修？", messages.get(messages.size() - 1).content());
        // 其他玩家聊天作为对话流中的 user 消息，统一用 <event> 包裹（主体在正文里）
        assertTrue(messages.stream().anyMatch(m -> "user".equals(m.role())
                && m.content().contains("<event>同伴 Alex 说：聊天栏消息</event>")));
        assertTrue(messages.stream().noneMatch(m -> m.content().contains("<chat")),
                "the chat wrapper is retired");

        String joined = messages.stream().map(LLMPromptRequestPayload.MessageItemPayload::content).collect(java.util.stream.Collectors.joining("\n\n"));
        // system 段内顺序：ax_system → game_context → player_memory（recent_dialogue 已展开为对话流）
        assertOrdered(joined, "<ax_system>", "<memory_summary>", "<game_context>", "<player_memory>", "当前输入：这个怎么修？");
        assertTrue(joined.contains("一到三句"));
        assertTrue(joined.contains("玩家准星指向 minecraft:anvil"));
        assertTrue(joined.contains("minecraft:anvil | 铁砧可以修复工具。"));
        assertTrue(joined.contains("你记得此前与玩家发生过这些事情"));
        assertTrue(joined.contains("<memory_summary>"), "累积摘要块必须出现在 prompt 里");
        assertTrue(joined.contains("玩家正在整理装备。"));
        assertTrue(!joined.contains("STM"));
        // 检索命中的摘要带着它当时的世界事件进尾部（player_memory）。
        assertTrue(joined.contains("玩家死亡一次。"));
        // 摘要块只渲染单元摘要正文：挂在块上的世界事件不重复进前缀，它们随检索进入尾部。
        assertTrue(!joined.contains("Stone Age"), "摘要块不得夹带附件事件");
        assertTrue(!joined.contains("IA delivery 附带上下文"));
    }

    @Test
    void chatThinkingSettingIsForwardedWithoutCapturingThinkingContent() {
        AuxiliumAssistantSettings settings = new AuxiliumAssistantSettings() {
            @Override
            public String wakeWord() {
                return DEFAULT_WAKE_WORD;
            }

            @Override
            public boolean chatThinkingEnabled() {
                return true;
            }
        };
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(
                new AXPromptOrchestrator(null, null, null),
                settings
        );

        LLMPromptRequestPayload payload = builder.buildChatRequest(
                new AXRequest("thinking", "请仔细想想", ""),
                AXContextSnapshot.empty(),
                AXContextBudget.DEFAULT
        );

        assertTrue(payload.thinking());
        assertTrue(!payload.captureThinkingContent());
        assertEquals("SIMPLE", payload.lane());
    }

    @Test
    void externalProfileSectionOrderControlsTopLevelContributorOrder() throws Exception {
        AXStorageLayout layout = new AXStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptsRoot().resolve("general_ax.zh_cn.default.json"),
                """
                {
                  "schemaVersion": 4,
                  "systemPrompts": {
                    "system": "测试 system prompt"
                  },
                  "sectionOrder": [
                    "game_context",
                    "ax_system",
                    "player_memory",
                    "current_input"
                  ]
                }
                """,
                StandardCharsets.UTF_8
        );
        AXScope scope = new AXScope("player", "world", "World", AXScopeKind.LOCAL_WORLD, true);
        AXContextSnapshot context = new AXContextSnapshot(
                scope,
                AXMemorySnapshot.empty(),
                AXRecentDialogueSnapshot.empty(),
                List.of(AXDynamicFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                "IA delivery 附带上下文"
        );
        AXContextBudget budget = AXContextBudget.DEFAULT;
        AXLlmPromptRequestBuilder builder = new AXLlmPromptRequestBuilder(new AXPromptOrchestrator(
                new AXPromptResourceRepository(layout, new AXJsonStore(new FakeGameEnvironment())),
                AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN),
                null
        ));

        LLMPromptRequestPayload payload = builder.buildChatRequest(new AXRequest("request", "本轮唯一输入-排序测试", ""), context, budget);

        String joined = payload.chunks().get(0).messageContent().stream()
                .map(LLMPromptRequestPayload.MessageItemPayload::content)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        // 装配位置不再由 sectionOrder 决定：system 恒定在最前，动态段恒定落在当前输入之前。
        java.util.List<LLMPromptRequestPayload.MessageItemPayload> ordered = payload.chunks().get(0).messageContent();
        assertEquals("system", ordered.get(0).role());
        assertTrue(ordered.get(0).content().contains("<ax_system>"));
        assertEquals("本轮唯一输入-排序测试", ordered.get(ordered.size() - 1).content());
        assertTrue(
                ordered.get(ordered.size() - 2).content().contains("<game_context>"),
                "动态上下文必须落在当前输入之前"
        );
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
