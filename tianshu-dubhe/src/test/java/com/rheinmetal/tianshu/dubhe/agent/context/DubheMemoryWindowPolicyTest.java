package com.rheinmetal.tianshu.dubhe.agent.context;

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
class DubheMemoryWindowPolicyTest {

    private static final int W_100K = 102_400;
    private static final int W_50K = 51_200;
    private static final int W_200K = 204_800;
    private static final int W_500K = 512_000;

    @Test
    void inputBudgetIsTheWatermarkAndTaskHangsOffIt() {
        DubheMemoryWindowPolicy p = DubheMemoryWindowPolicy.fromBudget(W_100K, 3, 60000L);

        // Input is the watermark (0.70 x W), not a share of its own: the watermark already is "the
        // prompt size the compression trigger fires at", so a second share would be a second ladder.
        assertEquals(W_100K, p.totalContextTokenBudget());
        assertEquals(81_920, p.compressionWatermarkTokenBudget());
        assertEquals(81_920, p.chatInputTokenBudget());
        assertEquals(20_480, p.chatOutputReserveTokenBudget());

        // TASK keeps its own instruction/payload split, but off that same input ceiling.
        assertEquals(81_920, p.taskInputTokenBudget());
        assertEquals(20_480, p.taskOutputReserveTokenBudget());
        assertEquals(10_240, p.taskInstructionTokenBudget());
        assertEquals(61_440, p.taskPayloadTokenBudget());

        // The tail is a size, not a ratio: every tier pays exactly these three parts.
        assertEquals(900, DubheMemoryWindowPolicy.TAIL_TOKEN_BUDGET);
        assertEquals(256, DubheMemoryWindowPolicy.TAIL_STATE_SNAPSHOT_TOKENS);
        assertEquals(128, DubheMemoryWindowPolicy.TAIL_CURRENT_INPUT_TOKENS);
        assertEquals(512, p.tailRetrievalTokenBudget());
        assertEquals(128, p.tailCurrentInputTokenBudget());
        assertTrue(DubheMemoryWindowPolicy.TAIL_STATE_SNAPSHOT_TOKENS
                        + p.tailRetrievalTokenBudget()
                        + p.tailCurrentInputTokenBudget()
                        <= DubheMemoryWindowPolicy.TAIL_TOKEN_BUDGET,
                "the three tail parts must fit the tail cap");
        assertEquals(896, DubheMemoryWindowPolicy.TAIL_STATE_SNAPSHOT_TOKENS
                        + p.tailRetrievalTokenBudget()
                        + p.tailCurrentInputTokenBudget(),
                "T1 + T2 + Q = 896, which is the 896 the design prices into C_low");
    }

    @Test
    void memorySegmentsAtThe100kTierAreDerivedFromTheWindowAlone() {
        DubheMemoryWindowPolicy p = DubheMemoryWindowPolicy.fromBudget(W_100K, 3, 60000L);

        // SB = clamp(0.05 x W, 2048, 24576)
        assertEquals(5_120, p.summaryBlockTokenBudget());
        // KEEP_MAX = clamp(0.10 x W, 2048, 49152); KEEP_TARGET = round(0.8 x KEEP_MAX)
        assertEquals(12_288, p.recentRawKeepTokenMax());
        assertEquals(9_830, p.recentRawKeepTokenTarget());
        // WATERMARK = 0.70 x W
        assertEquals(81_920, p.compressionWatermarkTokenBudget());
        // COMPRESS_MAX = WATERMARK - SB - KEEP_MAX - TAIL  (the derived remainder, not a knob)
        assertEquals(63_612, p.shortTermCompressTokenMax());
        // COMPRESS_TARGET = round(0.8 x COMPRESS_MAX)
        assertEquals(50_890, p.shortTermCompressTokenTarget());
        // UNIT = min(8192, COMPRESS_MAX): the unit fits inside the compression window
        assertEquals(8_192, p.compressionUnitTokenBudget());
        // MAX_RAW_TOKENS = KEEP_MAX + COMPRESS_MAX; MAX_RAW_CHARS = MAX_RAW_TOKENS x CHARS_PER_TOKEN
        assertEquals(75_900, p.maxRawTokenCount());
        assertEquals(303_600, p.maxRawCharacters());
    }

    @Test
    void memorySegmentsAlwaysFitUnderTheWatermark() {
        for (int window : new int[]{DubheContextTier.COMPACT.maxTokens(), W_50K, W_100K, W_200K, W_500K}) {
            DubheMemoryWindowPolicy p = DubheMemoryWindowPolicy.fromBudget(window, 3, 60000L);

            // The watermark is what the memory segments have to share: spending exactly it is the
            // whole point of deriving COMPRESS_MAX from it instead of scaling a fixed baseline.
            assertEquals(p.compressionWatermarkTokenBudget(),
                    p.summaryBlockTokenBudget() + p.recentRawKeepTokenMax()
                            + p.shortTermCompressTokenMax() + DubheMemoryWindowPolicy.TAIL_TOKEN_BUDGET,
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
        DubheMemoryWindowPolicy p = DubheMemoryWindowPolicy.fromBudget(3_000, 3, 60000L);

        assertEquals(3_000, p.totalContextTokenBudget());
        // 0.10 x 3000 and 0.05 x 3000 are below the floors, so the floors win.
        assertEquals(2_048, p.summaryBlockTokenBudget());
        assertEquals(2_048, p.recentRawKeepTokenMax());
        assertEquals(1_638, p.recentRawKeepTokenTarget());
        assertEquals(2_400, p.compressionWatermarkTokenBudget());
        // 2100 - 2048 - 2048 - 900 is negative: the remainder collapses to the explicit floor of 1
        // rather than escaping as a negative budget. Such a window cannot run AX usefully; the
        // smallest supported tier is DubheContextTier.COMPACT.
        assertEquals(1, p.shortTermCompressTokenMax());
        assertEquals(1, p.shortTermCompressTokenTarget());
        assertEquals(1, p.compressionUnitTokenBudget());
        assertEquals(2_049, p.maxRawTokenCount());
        // The input ceiling follows the watermark, so it is not floored at 1000 here.
        assertEquals(2_400, p.chatInputTokenBudget());
        assertEquals(600, p.chatOutputReserveTokenBudget());
    }

    @Test
    void largeWindowsAreCappedByTheAbsoluteCeilings() {
        DubheMemoryWindowPolicy p = DubheMemoryWindowPolicy.fromBudget(W_500K, 3, 60000L);

        // 0.05 x 512000 = 25600 and 0.10 x 512000 = 51200 both exceed their ceilings.
        assertEquals(8_192, p.summaryBlockTokenBudget());
        assertEquals(49_152, p.recentRawKeepTokenMax());
        assertEquals(409_600, p.compressionWatermarkTokenBudget());
        assertEquals(351_356, p.shortTermCompressTokenMax());
        assertEquals(8_192, p.compressionUnitTokenBudget());
        // The tail does not grow with the window at any tier.
        assertEquals(512, p.tailRetrievalTokenBudget());
        assertEquals(128, p.tailCurrentInputTokenBudget());
    }

    @Test
    void taskSlotsFitInsideTheTaskInputBudget() {
        for (int budget : new int[]{2000, 3000, 4000, 6500, 8000, 12000}) {
            DubheMemoryWindowPolicy p = DubheMemoryWindowPolicy.fromBudget(budget, 3, 60000L);
            int totalSlots = p.taskInstructionTokenBudget() + p.taskPayloadTokenBudget();
            assertTrue(totalSlots <= p.taskInputTokenBudget(), "task slots exceed budget at " + budget);
        }
    }

    @Test
    void memorySegmentsGrowWithTheWindow() {
        DubheMemoryWindowPolicy compact = DubheMemoryWindowPolicy.fromBudget(DubheContextTier.COMPACT.maxTokens(), 3, 60000L);
        DubheMemoryWindowPolicy standard = DubheMemoryWindowPolicy.fromBudget(W_100K, 3, 60000L);

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
        DubheMemoryWindowPolicy p = DubheMemoryWindowPolicy.fromBudget(100, 1, 1000L);
        assertEquals(1_000, p.totalContextTokenBudget());
        // 0.70 x 1000 is below the absolute floor of an input budget, so the floor wins.
        assertEquals(1_000, p.chatInputTokenBudget());
    }

    @Test
    void defaultIsTheContractFloorAtTheSmallestTier() {
        DubheMemoryWindowPolicy p = DubheMemoryWindowPolicy.DEFAULT;

        // The fallback is the conservative end of the ladder, built by the same formula as any turn.
        assertEquals(DubheContextTier.COMPACT.maxTokens(), p.totalContextTokenBudget());
        assertEquals(DubheContextTier.COMPACT, p.tier());
        assertEquals(26_214, p.chatInputTokenBudget());
        assertEquals(2_048, p.summaryBlockTokenBudget());
        assertEquals(3_932, p.recentRawKeepTokenMax());
        assertEquals(3_146, p.recentRawKeepTokenTarget());
        assertEquals(26_214, p.compressionWatermarkTokenBudget());
        assertEquals(19_334, p.shortTermCompressTokenMax());
        assertEquals(15_467, p.shortTermCompressTokenTarget());
        assertEquals(8_192, p.compressionUnitTokenBudget());
        assertEquals(23_266, p.maxRawTokenCount());
        assertEquals(93_064, p.maxRawCharacters());
        assertEquals(3, p.shortTermChatBlockLimit());
        assertEquals(60000L, p.conversationPauseMillis());
    }

    @Test
    void retrievalLimitsDoNotFollowTheWindow() {
        // The retired layout divided a 0.60 x W input share into five proportional slots, so the
        // 32K floor alone asked for 49 retrieved blocks — more than the 900-token tail can render.
        // The counts now come from the tail, which does not grow, so they must not move either.
        DubheContextBudget compact = DubheContextBudget.fromPolicy(
                DubheMemoryWindowPolicy.fromBudget(DubheContextTier.COMPACT.maxTokens(), 3, 60000L));
        DubheContextBudget large = DubheContextBudget.fromPolicy(
                DubheMemoryWindowPolicy.fromBudget(W_500K, 3, 60000L));

        assertEquals(10, DubheContextBudget.DEFAULT.maxRetrievedMemoryItems());
        assertEquals(3, DubheContextBudget.DEFAULT.maxKnowledgeRagItems());
        assertEquals(1_000, DubheContextBudget.DEFAULT.maxCurrentInputChars());
        assertEquals(435, DubheContextBudget.DEFAULT.memoryRetrievalTokenBudget());

        for (DubheContextBudget budget : new DubheContextBudget[]{compact, large}) {
            assertEquals(10, budget.maxRetrievedMemoryItems());
            assertEquals(3, budget.maxKnowledgeRagItems());
            assertEquals(1_000, budget.maxCurrentInputChars());
            assertEquals(435, budget.memoryRetrievalTokenBudget());
            assertTrue(budget.maxRetrievedMemoryItems() + budget.maxKnowledgeRagItems()
                            <= DubheContextBudget.MAX_RETRIEVAL_ITEMS,
                    "retrieved + knowledge must fit the joint cap");
        }
    }

    @Test
    void keptWindowTurnsFollowTheWindowBecauseTheSegmentDoes() {
        // KEEP is 0.10 x W, so the turn count is the one count that scales; 130 tokens per turn is
        // the same density the cost model uses.
        assertEquals(30, DubheContextBudget.fromPolicy(
                DubheMemoryWindowPolicy.fromBudget(DubheContextTier.COMPACT.maxTokens(), 3, 60000L))
                .maxRecentRawDialogueTurns());
        assertEquals(94, DubheContextBudget.fromPolicy(
                DubheMemoryWindowPolicy.fromBudget(W_100K, 3, 60000L)).maxRecentRawDialogueTurns());
        assertEquals(189, DubheContextBudget.fromPolicy(
                DubheMemoryWindowPolicy.fromBudget(W_200K, 3, 60000L)).maxRecentRawDialogueTurns());
        // 49152 / 130 = 378 at the 500K ceiling, which is the largest kept window the design allows.
        assertEquals(378, DubheContextBudget.fromPolicy(
                DubheMemoryWindowPolicy.fromBudget(W_500K, 3, 60000L)).maxRecentRawDialogueTurns());
        assertTrue(378 <= DubheContextBudget.MAX_RECENT_RAW_DIALOGUE_TURNS,
                "the cap must not truncate the largest legitimate kept window");
    }
}
