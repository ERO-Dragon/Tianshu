package com.rheinmetal.tianshu.dubhe.dialogue.prompt;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.dubhe.agent.llm.DubheLlmPromptRequestBuilder;
import com.rheinmetal.tianshu.dubhe.DubheAssistantSettings;
import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.dialogue.gamecontext.DubheDynamicFact;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRecentDialogueSnapshot;
import com.rheinmetal.tianshu.dubhe.dialogue.gamecontext.DubheKnowledgeHit;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemoryBlockView;
import com.rheinmetal.tianshu.dubhe.module.memory.DubheMemorySnapshot;
import com.rheinmetal.tianshu.dubhe.module.recentdialogue.DubheRawTurn;
import com.rheinmetal.tianshu.dubhe.module.memory.shortterm.DubheStmBlock;
import com.rheinmetal.tianshu.dubhe.module.memory.summary.DubheMemorySummaryBlock;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptResourceRepository;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScope;
import com.rheinmetal.tianshu.dubhe.shared.scope.DubheScopeKind;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheJsonStore;
import com.rheinmetal.tianshu.dubhe.shared.storage.DubheStorageLayout;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubhePromptAssemblySnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void chatPromptIsSingleMessageChunkWithExpectedSectionOrder() {
        DubheScope scope = new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);
        DubheMemorySnapshot memory = new DubheMemorySnapshot(
                List.of(new DubheMemoryBlockView(
                        stm(scope, "retrieved", "玩家之前问过铁砧。", 1000L),
                        List.of("玩家死亡一次。")
                )),
                new DubheMemorySummaryBlock(
                        3,
                        List.of(new DubheMemoryBlockView(
                                stm(scope, "recent", "玩家正在整理装备。", 2000L),
                                List.of("玩家解锁成就：Stone Age")
                        )),
                        0,
                        0
                )
        );
        DubheContextSnapshot context = new DubheContextSnapshot(
                scope,
                memory,
                new DubheRecentDialogueSnapshot(List.of(
                        DubheRawTurn.worldEvent(scope, "Alex", "聊天栏消息", 3000L, "turn")
                )),
                List.of(DubheDynamicFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                "IA delivery 附带上下文"
        );
        DubheContextBudget budget = DubheContextBudget.DEFAULT;
        DubheLlmPromptRequestBuilder builder = new DubheLlmPromptRequestBuilder(new DubhePromptOrchestrator(
                null,
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN),
                (request, snapshot, contextBudget) -> List.of(DubheKnowledgeHit.of(
                        "dubhe.static_knowledge.mock",
                        List.of("minecraft:anvil | 铁砧可以修复工具。")
                )),
                null
        ));

        LLMPromptRequestPayload payload = builder.buildChatRequest(
                new DubheRequest("request", "当前输入：这个怎么修？", ""),
                context,
                budget
        );

        assertEquals(0, payload.maxTokens());
        List<LLMPromptRequestPayload.MessageItemPayload> messages = payload.messages();
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
        // system 段内顺序：system_prompt → game_context → player_memory（recent_dialogue 已展开为对话流）
        assertOrdered(joined, "<system_prompt>", "<memory_summary>", "<game_context>", "<player_memory>", "当前输入：这个怎么修？");
        assertTrue(joined.contains("一到三句"));
        assertTrue(joined.contains("我让人去办"), "承诺-兑现人设必须在 system 里（G7）");
        assertTrue(joined.contains("没有真的派出就只给建议"), "没有真实委派时不得空口承诺");
        assertTrue(!joined.contains("涉及游戏动作只给建议"), "旧口径（只给建议）已移除");
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

        // 同一份输入连装两次必须逐字节一致：这是前缀缓存成立的前提（G?：装配确定性）。
        LLMPromptRequestPayload again = builder.buildChatRequest(
                new DubheRequest("request", "当前输入：这个怎么修？", ""),
                context,
                budget
        );
        assertEquals(payload.messages(), again.messages(), "two assemblies of the same snapshot must be identical");
    }

    @Test
    void externalProfileSectionOrderControlsTopLevelContributorOrder() throws Exception {
        DubheStorageLayout layout = new DubheStorageLayout(new TestLlmSupport.FakeConfig(tempDir.resolve("module")));
        Files.createDirectories(layout.promptsRoot());
        Files.writeString(
                layout.promptsRoot().resolve("general_dubhe.zh_cn.default.json"),
                """
                {
                  "schemaVersion": 4,
                  "systemPrompts": {
                    "system": "测试 system prompt"
                  },
                  "sectionOrder": [
                    "game_context",
                    "system_prompt",
                    "player_memory",
                    "current_input"
                  ]
                }
                """,
                StandardCharsets.UTF_8
        );
        DubheScope scope = new DubheScope("player", "world", "World", DubheScopeKind.LOCAL_WORLD, true);
        DubheContextSnapshot context = new DubheContextSnapshot(
                scope,
                DubheMemorySnapshot.empty(),
                DubheRecentDialogueSnapshot.empty(),
                List.of(DubheDynamicFact.of("玩家准星指向 minecraft:anvil", 90, "test")),
                "IA delivery 附带上下文"
        );
        DubheContextBudget budget = DubheContextBudget.DEFAULT;
        DubheLlmPromptRequestBuilder builder = new DubheLlmPromptRequestBuilder(new DubhePromptOrchestrator(
                new DubhePromptResourceRepository(layout, new DubheJsonStore(new FakeGameEnvironment())),
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN),
                null
        ));

        LLMPromptRequestPayload payload = builder.buildChatRequest(new DubheRequest("request", "本轮唯一输入-排序测试", ""), context, budget);

        String joined = payload.messages().stream()
                .map(LLMPromptRequestPayload.MessageItemPayload::content)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        // 装配位置不再由 sectionOrder 决定：system 恒定在最前，动态段恒定落在当前输入之前。
        java.util.List<LLMPromptRequestPayload.MessageItemPayload> ordered = payload.messages();
        assertEquals("system", ordered.get(0).role());
        assertTrue(ordered.get(0).content().contains("<system_prompt>"));
        assertEquals("本轮唯一输入-排序测试", ordered.get(ordered.size() - 1).content());
        assertTrue(
                ordered.get(ordered.size() - 2).content().contains("<game_context>"),
                "动态上下文必须落在当前输入之前"
        );
    }

    private static DubheStmBlock stm(DubheScope scope, String id, String content, long createdAtMillis) {
        return new DubheStmBlock(id, "", scope.worldId(), createdAtMillis, createdAtMillis - 100L, createdAtMillis, "", "", 1, 0, content, List.of());
    }

    private static DubheRawTurn raw(DubheScope scope, String role, String content, long createdAtMillis) {
        return new DubheRawTurn("", role, content, createdAtMillis, scope.worldId(), "session", "turn", 0, 0, "");
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
