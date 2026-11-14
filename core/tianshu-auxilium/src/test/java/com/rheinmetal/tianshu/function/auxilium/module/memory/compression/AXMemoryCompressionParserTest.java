package com.rheinmetal.tianshu.function.auxilium.module.memory.compression;

import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurn;
import com.rheinmetal.tianshu.function.auxilium.module.recentdialogue.AXRawTurnBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemoryCompressionParserTest {
    private final AXMemoryCompressionParser parser = new AXMemoryCompressionParser();
    private final AXRawTurnBatch batch = batch();

    @Test
    void parsesAllThreeElementsFromOnePassJson() {
        AXMemoryCompressionResult result = parser.parse("""
                {
                  "schemaVersion": 2,
                  "summary": "玩家先在下界挖矿，然后把钻石镐收进末影箱。",
                  "eventChain": [
                    { "event": "玩家在下界挖到远古残骸", "importance": 7, "turnRefs": [1, 2] },
                    { "event": "玩家把钻石镐放进末影箱", "importance": 5, "turnRefs": ["t3"] }
                  ],
                  "preferences": [
                    { "fact": "玩家不喜欢下矿", "confidence": 0.8, "turnRefs": [4] }
                  ]
                }
                """, batch);

        assertEquals(AXMemoryCompressionParser.SUPPORTED_SCHEMA_VERSION, result.schemaVersion());
        assertEquals("玩家先在下界挖矿，然后把钻石镐收进末影箱。", result.summary());
        assertEquals(2, result.events().size());
        assertEquals("玩家在下界挖到远古残骸", result.events().get(0).fact());
        assertEquals(7, result.events().get(0).importance());
        assertEquals(List.of("t1", "t2"), result.events().get(0).turnRefs());
        assertEquals(List.of("t3"), result.events().get(1).turnRefs());
        assertEquals(1, result.preferences().size());
        assertEquals("玩家不喜欢下矿", result.preferences().get(0).fact());
        assertEquals(0.8D, result.preferences().get(0).confidence(), 1e-9);
        assertEquals(List.of("t4"), result.preferences().get(0).turnRefs());
        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
    }

    @Test
    void markdownFencedJsonIsAccepted() {
        AXMemoryCompressionResult result = parser.parse("""
                ```json
                {"schemaVersion": 2, "summary": "玩家修好了铁砧。", "eventChain": [], "preferences": []}
                ```
                """, batch);

        assertEquals("玩家修好了铁砧。", result.summary());
        assertTrue(result.hasSummary());
    }

    @Test
    void missingSummaryStillKeepsEvents() {
        AXMemoryCompressionResult result = parser.parse("""
                {
                  "schemaVersion": 2,
                  "eventChain": [
                    { "event": "玩家死亡一次", "importance": 4, "turnRefs": [2] }
                  ]
                }
                """, batch);

        assertFalse(result.hasSummary());
        assertEquals(1, result.events().size());
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_SUMMARY_MISSING));
        assertFalse(result.isEmpty(), "只有摘要坏了不算整批丢弃");
    }

    @Test
    void truncatedJsonKeepsRawBatchUsable() {
        AXMemoryCompressionResult result = parser.parse("{\"schemaVersion\": 2, \"summary\": \"玩家正在下界", batch);

        assertTrue(result.isEmpty());
        assertEquals("", result.summary(), "截断的 JSON 不能当摘要文本落库");
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_UNPARSEABLE_OUTPUT));
    }

    @Test
    void nonJsonOutputIsSalvagedAsSummaryOnly() {
        AXMemoryCompressionResult result = parser.parse("玩家在调试资源重载日志，并关注缓存和模块加载。", batch);

        assertEquals("玩家在调试资源重载日志，并关注缓存和模块加载。", result.summary());
        assertTrue(result.events().isEmpty());
        assertTrue(result.preferences().isEmpty());
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_SUMMARY_SALVAGED));
    }

    @Test
    void oneBrokenEventDoesNotDiscardTheOthers() {
        AXMemoryCompressionResult result = parser.parse("""
                {
                  "schemaVersion": 2,
                  "summary": "玩家整理了箱子。",
                  "eventChain": [
                    { "event": "玩家整理箱子", "importance": 4, "turnRefs": [1] },
                    { "importance": 3, "turnRefs": [2] },
                    "这不是对象",
                    { "event": "", "importance": 3, "turnRefs": [3] },
                    { "event": "玩家丢掉了多余的石镐", "importance": 4, "turnRefs": [4] }
                  ]
                }
                """, batch);

        assertEquals(2, result.events().size());
        assertEquals("玩家整理箱子", result.events().get(0).fact());
        assertEquals("玩家丢掉了多余的石镐", result.events().get(1).fact());
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_EVENT_EMPTY_FACT));
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_EVENT_INVALID_ITEM));
    }

    @Test
    void eventWithoutTurnRefsIsRejected() {
        AXMemoryCompressionResult result = parser.parse("""
                {
                  "schemaVersion": 2,
                  "summary": "玩家死亡一次。",
                  "eventChain": [
                    { "event": "玩家死亡一次", "importance": 4 },
                    { "event": "玩家死亡一次", "importance": 4, "turnRefs": [] }
                  ]
                }
                """, batch);

        assertTrue(result.events().isEmpty(), "没有轮级溯源的事件一律拒绝");
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_EVENT_WITHOUT_TURN_REFS));
    }

    @Test
    void eventWithTurnRefsOutsideTheBatchIsRejected() {
        AXMemoryCompressionResult result = parser.parse("""
                {
                  "schemaVersion": 2,
                  "eventChain": [
                    { "event": "编造的事实", "importance": 4, "turnRefs": [99] },
                    { "event": "编造的事实", "importance": 4, "turnRefs": ["raw_not_in_batch"] }
                  ]
                }
                """, batch);

        assertTrue(result.events().isEmpty(), "本批之外的轮引用整条拒绝");
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_EVENT_UNKNOWN_TURN_REFS));
    }

    @Test
    void importanceIsClampedAndFallsBackWhenMissing() {
        AXMemoryCompressionResult result = parser.parse("""
                {
                  "schemaVersion": 2,
                  "eventChain": [
                    { "event": "分数过高", "importance": 42, "turnRefs": [1] },
                    { "event": "分数过低", "importance": 0, "turnRefs": [2] },
                    { "event": "没有分数", "turnRefs": [3] },
                    { "event": "分数不是整数", "importance": "不是数字", "turnRefs": [4] }
                  ]
                }
                """, batch);

        assertEquals(4, result.events().size());
        assertEquals(AXMemoryEvent.MAX_IMPORTANCE, result.events().get(0).importance());
        assertEquals(AXMemoryEvent.MIN_IMPORTANCE, result.events().get(1).importance());
        assertEquals(AXMemoryEvent.DEFAULT_IMPORTANCE, result.events().get(2).importance());
        assertEquals(AXMemoryEvent.DEFAULT_IMPORTANCE, result.events().get(3).importance());
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_IMPORTANCE_CLAMPED));
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_IMPORTANCE_FALLBACK));
    }

    @Test
    void duplicateEventsAreAllKept() {
        AXMemoryCompressionResult result = parser.parse("""
                {
                  "schemaVersion": 2,
                  "eventChain": [
                    { "event": "玩家在下界死亡一次", "importance": 4, "turnRefs": [1] },
                    { "event": "玩家在下界死亡一次", "importance": 4, "turnRefs": [2] },
                    { "event": "玩家把钻石放进箱子", "importance": 4, "turnRefs": [3] },
                    { "event": "玩家把钻石放进箱子", "importance": 4, "turnRefs": [3] }
                  ]
                }
                """, batch);

        assertEquals(4, result.events().size(), "事件是客观事实：重复条目必须全部保留");
        assertEquals(List.of("t1"), result.events().get(0).turnRefs());
        assertEquals(List.of("t2"), result.events().get(1).turnRefs());
        assertEquals(List.of("t3"), result.events().get(2).turnRefs());
        assertEquals(List.of("t3"), result.events().get(3).turnRefs());
    }

    @Test
    void unsupportedSchemaVersionRejectsTheWholeOutput() {
        AXMemoryCompressionResult result = parser.parse("""
                {"schemaVersion": 3, "summary": "未来版本的摘要", "eventChain": [], "preferences": []}
                """, batch);

        assertTrue(result.isEmpty());
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_SCHEMA_VERSION_UNSUPPORTED));
    }

    @Test
    void preferenceRequiresExplicitConfidenceAndTurnRefs() {
        AXMemoryCompressionResult result = parser.parse("""
                {
                  "schemaVersion": 2,
                  "preferences": [
                    { "fact": "没有置信度", "turnRefs": [1] },
                    { "fact": "置信度过低", "confidence": 0.2, "turnRefs": [2] },
                    { "fact": "没有轮引用", "confidence": 0.9 },
                    { "fact": "玩家不喜欢下矿", "confidence": 0.9, "turnRefs": [3] }
                  ]
                }
                """, batch);

        assertEquals(1, result.preferences().size());
        assertEquals("玩家不喜欢下矿", result.preferences().get(0).fact());
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_PREFERENCE_WITHOUT_CONFIDENCE));
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_PREFERENCE_LOW_CONFIDENCE));
        assertTrue(result.hasDiagnostic(AXMemoryCompressionParser.DIAGNOSTIC_PREFERENCE_WITHOUT_TURN_REFS));
    }

    private static AXRawTurnBatch batch() {
        return new AXRawTurnBatch("raw_batch_test", List.of(
                turn("t1", "user", "我在下界挖远古残骸。"),
                turn("t2", "assistant", "远古残骸要小心岩浆。"),
                turn("t3", "user", "我把钻石镐放进末影箱了。"),
                turn("t4", "user", "我不喜欢下矿。")
        ), 1_000L, 2_000L, 0, 0);
    }

    private static AXRawTurn turn(String id, String role, String content) {
        return new AXRawTurn(id, role, content, 1_000L, "world", "session", id, 0, 0, "");
    }
}
