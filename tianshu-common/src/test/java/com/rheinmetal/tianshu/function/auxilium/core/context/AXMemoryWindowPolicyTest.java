package com.rheinmetal.tianshu.function.auxilium.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The numbers below are not preferences: every window field is a pure function of the window
 * {@code W}, so a change here means the formula changed. Each expectation states the formula it
 * encodes, and {@link #memorySegmentsAlwaysFitUnderTheWatermark()} guards the identity that makes
 * the compression trigger reachable at all.
 *
 * <p>There is exactly one ladder: {@code W} sets the window segments (summary block, kept window,
 * watermark, compression window) and nothing else. The tail is a fixed size, so the item limits
 * derived from it are fixed too — the second ladder that produced a 0.60 input share and five
 * proportional slots is gone, and {@link #retrievalLimitsDoNotFollowTheWindow()} is the guard that
 * keeps it gone.</p>
 */
class AXMemoryWindowPolicyTest {

    private static final int W_100K = 102_400;
    private static final int W_50K = 51_200;
    private static final int W_200K = 204_800;
    private static final int W_500K = 512_000;

    @Test
    void inputBudgetIsTheWatermarkAndTaskHangsOffIt() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(W_100K, 3, 60000L);

        // Input is the watermark (0.70 x W), not a share of its own: the watermark already is "the
        // prompt size the compression trigger fires at", so a second share would be a second ladder.
        assertEquals(W_100K, p.totalContextTokenBudget());
        assertEquals(71_680, p.compressionWatermarkTokenBudget());
        assertEquals(71_680, p.chatInputTokenBudget());
        assertEquals(30_720, p.chatOutputReserveTokenBudget());

        // TASK keeps its own instruction/payload split, but off that same input ceiling.
        assertEquals(71_680, p.taskInputTokenBudget());
        assertEquals(30_720, p.taskOutputReserveTokenBudget());
        assertEquals(8_960, p.taskInstructionTokenBudget());
        assertEquals(53_760, p.taskPayloadTokenBudget());

        // The tail is a size, not a ratio: every tier pays exactly these three parts.
        assertEquals(900, AXMemoryWindowPolicy.TAIL_TOKEN_BUDGET);
        assertEquals(256, AXMemoryWindowPolicy.TAIL_STATE_SNAPSHOT_TOKENS);
        assertEquals(128, AXMemoryWindowPolicy.TAIL_CURRENT_INPUT_TOKENS);
        assertEquals(512, p.tailRetrievalTokenBudget());
        assertEquals(128, p.tailCurrentInputTokenBudget());
        assertTrue(AXMemoryWindowPolicy.TAIL_STATE_SNAPSHOT_TOKENS
                        + p.tailRetrievalTokenBudget()
                        + p.tailCurrentInputTokenBudget()
                        <= AXMemoryWindowPolicy.TAIL_TOKEN_BUDGET,
                "the three tail parts must fit the tail cap");
        assertEquals(896, AXMemoryWindowPolicy.TAIL_STATE_SNAPSHOT_TOKENS
                        + p.tailRetrievalTokenBudget()
                        + p.tailCurrentInputTokenBudget(),
                "T1 + T2 + Q = 896, which is the 896 the design prices into C_low");
    }

    @Test
    void memorySegmentsAtThe100kTierAreDerivedFromTheWindowAlone() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(W_100K, 3, 60000L);

        // SB = clamp(0.05 x W, 2048, 24576)
        assertEquals(5_120, p.summaryBlockTokenBudget());
        // KEEP_MAX = clamp(0.10 x W, 2048, 49152); KEEP_TARGET = round(0.8 x KEEP_MAX)
        assertEquals(10_240, p.recentRawKeepTokenMax());
        assertEquals(8_192, p.recentRawKeepTokenTarget());
        // WATERMARK = 0.70 x W
        assertEquals(71_680, p.compressionWatermarkTokenBudget());
        // COMPRESS_MAX = WATERMARK - SB - KEEP_MAX - TAIL  (the derived remainder, not a knob)
        assertEquals(55_420, p.shortTermCompressTokenMax());
        // COMPRESS_TARGET = round(0.8 x COMPRESS_MAX)
        assertEquals(44_336, p.shortTermCompressTokenTarget());
        // UNIT = min(8192, COMPRESS_MAX): the unit fits inside the compression window
        assertEquals(8_192, p.compressionUnitTokenBudget());
        // MAX_RAW_TOKENS = KEEP_MAX + COMPRESS_MAX; MAX_RAW_CHARS = MAX_RAW_TOKENS x CHARS_PER_TOKEN
        assertEquals(65_660, p.maxRawTokenCount());
        assertEquals(262_640, p.maxRawCharacters());
    }

    @Test
    void memorySegmentsAlwaysFitUnderTheWatermark() {
        for (int window : new int[]{AXContextTier.COMPACT.maxTokens(), W_50K, W_100K, W_200K, W_500K}) {
            AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(window, 3, 60000L);

            // The watermark is what the memory segments have to share: spending exactly it is the
            // whole point of deriving COMPRESS_MAX from it instead of scaling a fixed baseline.
            assertEquals(p.compressionWatermarkTokenBudget(),
                    p.summaryBlockTokenBudget() + p.recentRawKeepTokenMax()
                            + p.shortTermCompressTokenMax() + AXMemoryWindowPolicy.TAIL_TOKEN_BUDGET,
                    "memory segments must add up to the watermark at " + window);
            assertTrue(p.compressionWatermarkTokenBudget() < window,
                    "watermark must stay below the window at " + window);
            assertTrue(p.shortTermCompressTokenMax() > 0 && p.shortTermCompressTokenMax() < window,
                    "compression window must be usable and inside the window at " + window);
            assertEquals(p.recentRawKeepTokenMax() + p.shortTermCompressTokenMax(), p.maxRawTokenCount(),
                    "raw capacity is the two raw segments together at " + window);
            assertEquals(p.maxRawTokenCount() * 4, p.maxRawCharacters(),
                    "character capacity is the token capacity at the fallback density at " + window);
        }
    }

    @Test
    void smallWindowsAreLiftedByTheAbsoluteFloorsInsteadOfCollapsing() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(3_000, 3, 60000L);

        assertEquals(3_000, p.totalContextTokenBudget());
        // 0.10 x 3000 and 0.05 x 3000 are below the floors, so the floors win.
        assertEquals(2_048, p.summaryBlockTokenBudget());
        assertEquals(2_048, p.recentRawKeepTokenMax());
        assertEquals(1_638, p.recentRawKeepTokenTarget());
        assertEquals(2_100, p.compressionWatermarkTokenBudget());
        // 2100 - 2048 - 2048 - 900 is negative: the remainder collapses to the explicit floor of 1
        // rather than escaping as a negative budget. Such a window cannot run AX usefully; the
        // smallest supported tier is AXContextTier.COMPACT.
        assertEquals(1, p.shortTermCompressTokenMax());
        assertEquals(1, p.shortTermCompressTokenTarget());
        assertEquals(1, p.compressionUnitTokenBudget());
        assertEquals(2_049, p.maxRawTokenCount());
        // The input ceiling follows the watermark, so it is not floored at 1000 here.
        assertEquals(2_100, p.chatInputTokenBudget());
        assertEquals(900, p.chatOutputReserveTokenBudget());
    }

    @Test
    void largeWindowsAreCappedByTheAbsoluteCeilings() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(W_500K, 3, 60000L);

        // 0.05 x 512000 = 25600 and 0.10 x 512000 = 51200 both exceed their ceilings.
        assertEquals(24_576, p.summaryBlockTokenBudget());
        assertEquals(49_152, p.recentRawKeepTokenMax());
        assertEquals(358_400, p.compressionWatermarkTokenBudget());
        assertEquals(283_772, p.shortTermCompressTokenMax());
        assertEquals(8_192, p.compressionUnitTokenBudget());
        // The tail does not grow with the window at any tier.
        assertEquals(512, p.tailRetrievalTokenBudget());
        assertEquals(128, p.tailCurrentInputTokenBudget());
    }

    @Test
    void taskSlotsFitInsideTheTaskInputBudget() {
        for (int budget : new int[]{2000, 3000, 4000, 6500, 8000, 12000}) {
            AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(budget, 3, 60000L);
            int totalSlots = p.taskInstructionTokenBudget() + p.taskPayloadTokenBudget();
            assertTrue(totalSlots <= p.taskInputTokenBudget(), "task slots exceed budget at " + budget);
        }
    }

    @Test
    void memorySegmentsGrowWithTheWindow() {
        AXMemoryWindowPolicy compact = AXMemoryWindowPolicy.fromBudget(AXContextTier.COMPACT.maxTokens(), 3, 60000L);
        AXMemoryWindowPolicy standard = AXMemoryWindowPolicy.fromBudget(W_100K, 3, 60000L);

        assertTrue(compact.summaryBlockTokenBudget() < standard.summaryBlockTokenBudget());
        assertTrue(compact.recentRawKeepTokenMax() < standard.recentRawKeepTokenMax());
        assertTrue(compact.shortTermCompressTokenMax() < standard.shortTermCompressTokenMax());
        assertTrue(compact.compressionWatermarkTokenBudget() < standard.compressionWatermarkTokenBudget());
        assertTrue(compact.maxRawTokenCount() < standard.maxRawTokenCount());
        // The unit is a size, not a ratio; it only shrinks when the window is too narrow to hold it.
        assertEquals(8_192, compact.compressionUnitTokenBudget());
        assertEquals(8_192, standard.compressionUnitTokenBudget());
        // The tail is the same size at both tiers.
        assertEquals(compact.tailRetrievalTokenBudget(), standard.tailRetrievalTokenBudget());
    }

    @Test
    void budgetClampedToMinimum() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(100, 1, 1000L);
        assertEquals(1_000, p.totalContextTokenBudget());
        // 0.70 x 1000 is below the absolute floor of an input budget, so the floor wins.
        assertEquals(1_000, p.chatInputTokenBudget());
    }

    @Test
    void defaultIsTheContractFloorAtTheSmallestTier() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.DEFAULT;

        // The fallback is the conservative end of the ladder, built by the same formula as any turn.
        assertEquals(AXContextTier.COMPACT.maxTokens(), p.totalContextTokenBudget());
        assertEquals(AXContextTier.COMPACT, p.tier());
        assertEquals(22_938, p.chatInputTokenBudget());
        assertEquals(2_048, p.summaryBlockTokenBudget());
        assertEquals(3_277, p.recentRawKeepTokenMax());
        assertEquals(2_622, p.recentRawKeepTokenTarget());
        assertEquals(22_938, p.compressionWatermarkTokenBudget());
        assertEquals(16_713, p.shortTermCompressTokenMax());
        assertEquals(13_370, p.shortTermCompressTokenTarget());
        assertEquals(8_192, p.compressionUnitTokenBudget());
        assertEquals(19_990, p.maxRawTokenCount());
        assertEquals(79_960, p.maxRawCharacters());
        assertEquals(3, p.shortTermChatBlockLimit());
        assertEquals(60000L, p.conversationPauseMillis());
    }

    @Test
    void retrievalLimitsDoNotFollowTheWindow() {
        // The retired layout divided a 0.60 x W input share into five proportional slots, so the
        // 32K floor alone asked for 49 retrieved blocks — more than the 900-token tail can render.
        // The counts now come from the tail, which does not grow, so they must not move either.
        AXContextBudget compact = AXContextBudget.fromPolicy(
                AXMemoryWindowPolicy.fromBudget(AXContextTier.COMPACT.maxTokens(), 3, 60000L));
        AXContextBudget large = AXContextBudget.fromPolicy(
                AXMemoryWindowPolicy.fromBudget(W_500K, 3, 60000L));

        assertEquals(8, AXContextBudget.DEFAULT.maxRetrievedMemoryItems());
        assertEquals(2, AXContextBudget.DEFAULT.maxRecentMemoryItems());
        assertEquals(3, AXContextBudget.DEFAULT.maxKnowledgeRagItems());
        assertEquals(1_000, AXContextBudget.DEFAULT.maxCurrentInputChars());
        assertEquals(435, AXContextBudget.DEFAULT.memoryRetrievalTokenBudget());

        for (AXContextBudget budget : new AXContextBudget[]{compact, large}) {
            assertEquals(8, budget.maxRetrievedMemoryItems());
            assertEquals(2, budget.maxRecentMemoryItems());
            assertEquals(3, budget.maxKnowledgeRagItems());
            assertEquals(1_000, budget.maxCurrentInputChars());
            assertEquals(435, budget.memoryRetrievalTokenBudget());
            assertTrue(budget.maxRetrievedMemoryItems() + budget.maxRecentMemoryItems()
                            + budget.maxKnowledgeRagItems() <= AXContextBudget.MAX_RETRIEVAL_ITEMS,
                    "retrieved + recent + knowledge must fit the joint cap");
        }
    }

    @Test
    void keptWindowTurnsFollowTheWindowBecauseTheSegmentDoes() {
        // KEEP is 0.10 x W, so the turn count is the one count that scales; 130 tokens per turn is
        // the same density the cost model uses.
        assertEquals(25, AXContextBudget.fromPolicy(
                AXMemoryWindowPolicy.fromBudget(AXContextTier.COMPACT.maxTokens(), 3, 60000L))
                .maxRecentRawDialogueTurns());
        assertEquals(78, AXContextBudget.fromPolicy(
                AXMemoryWindowPolicy.fromBudget(W_100K, 3, 60000L)).maxRecentRawDialogueTurns());
        assertEquals(157, AXContextBudget.fromPolicy(
                AXMemoryWindowPolicy.fromBudget(W_200K, 3, 60000L)).maxRecentRawDialogueTurns());
        // 49152 / 130 = 378 at the 500K ceiling, which is the largest kept window the design allows.
        assertEquals(378, AXContextBudget.fromPolicy(
                AXMemoryWindowPolicy.fromBudget(W_500K, 3, 60000L)).maxRecentRawDialogueTurns());
        assertTrue(378 <= AXContextBudget.MAX_RECENT_RAW_DIALOGUE_TURNS,
                "the cap must not truncate the largest legitimate kept window");
    }
}
