package com.rheinmetal.tianshu.function.auxilium.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemoryWindowPolicyTest {

    @Test
    void fromBudget_8000_reservesOutputAndBuildsChatLayout() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L);

        assertEquals(8000, p.totalContextTokenBudget());
        assertEquals(4800, p.chatInputTokenBudget());
        assertEquals(3200, p.chatOutputReserveTokenBudget());
        assertEquals(480, p.chatSystemTokenBudget());
        assertEquals(1440, p.knowledgeRagTokenBudget());
        assertEquals(1200, p.retrievedMemoryTokenBudget());
        assertEquals(480, p.recentMemoryTokenBudget());
        assertEquals(960, p.recentRawDialogueTokenBudget());
        assertEquals(240, p.currentInputTokenBudget());

        assertEquals(4800, p.taskInputTokenBudget());
        assertEquals(3200, p.taskOutputReserveTokenBudget());
        assertEquals(600, p.taskSystemTokenBudget());
        assertEquals(600, p.taskInstructionTokenBudget());
        assertEquals(3600, p.taskPayloadTokenBudget());

        assertEquals(5000, p.recentRawKeepTokenTarget());
        assertEquals(8000, p.recentRawKeepTokenMax());
        assertEquals(7000, p.shortTermCompressTokenTarget());
        assertEquals(10000, p.shortTermCompressTokenMax());
        assertEquals(28000, p.maxRawTokenCount());
        assertEquals(120000, p.maxRawCharacters());
    }

    @Test
    void fromBudget_3000_scalesProportionally() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(3000, 3, 60000L);

        assertEquals(3000, p.totalContextTokenBudget());
        assertEquals(1800, p.chatInputTokenBudget());
        assertEquals(1200, p.chatOutputReserveTokenBudget());
        assertEquals(180, p.chatSystemTokenBudget());
        assertEquals(540, p.knowledgeRagTokenBudget());
        assertEquals(450, p.retrievedMemoryTokenBudget());
        assertEquals(180, p.recentMemoryTokenBudget());
        assertEquals(360, p.recentRawDialogueTokenBudget());
        assertEquals(90, p.currentInputTokenBudget());

        double scale = 3000.0 / 8000.0;
        assertEquals((int) (5000 * scale), p.recentRawKeepTokenTarget());
        assertEquals((int) (8000 * scale), p.recentRawKeepTokenMax());
        assertEquals((int) (7000 * scale), p.shortTermCompressTokenTarget());
        assertEquals((int) (10000 * scale), p.shortTermCompressTokenMax());
        assertEquals((int) (28000 * scale), p.maxRawTokenCount());
        assertEquals((int) (120000 * scale), p.maxRawCharacters());
    }

    @Test
    void fromBudget_12000_scalesProportionally() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(12000, 3, 60000L);

        assertEquals(12000, p.totalContextTokenBudget());
        assertEquals(7200, p.chatInputTokenBudget());
        assertEquals(4800, p.chatOutputReserveTokenBudget());
        assertEquals(720, p.chatSystemTokenBudget());
        assertEquals(2160, p.knowledgeRagTokenBudget());
        assertEquals(1800, p.retrievedMemoryTokenBudget());
        assertEquals(720, p.recentMemoryTokenBudget());
        assertEquals(1440, p.recentRawDialogueTokenBudget());
        assertEquals(360, p.currentInputTokenBudget());
    }

    @Test
    void chatInputSlotRatiosCoverChatInputBudget() {
        for (int budget : new int[]{2000, 3000, 4000, 6500, 8000, 12000}) {
            AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(budget, 3, 60000L);
            int totalSlots = p.chatSystemTokenBudget()
                    + p.knowledgeRagTokenBudget()
                    + p.retrievedMemoryTokenBudget()
                    + p.recentMemoryTokenBudget()
                    + p.recentRawDialogueTokenBudget()
                    + p.currentInputTokenBudget();
            assertTrue(totalSlots <= p.chatInputTokenBudget(), "slots exceed chat input budget at " + budget);
            assertTrue(p.chatInputTokenBudget() - totalSlots <= 5, "unexpected unallocated chat input budget at " + budget);
        }
    }

    @Test
    void taskInputSlotRatiosCoverTaskInputBudget() {
        for (int budget : new int[]{2000, 3000, 4000, 6500, 8000, 12000}) {
            AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(budget, 3, 60000L);
            int totalSlots = p.taskSystemTokenBudget()
                    + p.taskInstructionTokenBudget()
                    + p.taskPayloadTokenBudget();
            assertTrue(totalSlots <= p.taskInputTokenBudget(), "task slots exceed budget at " + budget);
            assertTrue(p.taskInputTokenBudget() - totalSlots <= 5, "unexpected unallocated task input budget at " + budget);
        }
    }

    @Test
    void compressionParamsShrinkWithSmallBudget() {
        AXMemoryWindowPolicy small = AXMemoryWindowPolicy.fromBudget(3000, 3, 60000L);
        AXMemoryWindowPolicy large = AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L);

        assertTrue(small.recentRawKeepTokenMax() < large.recentRawKeepTokenMax());
        assertTrue(small.shortTermCompressTokenMax() < large.shortTermCompressTokenMax());
        assertTrue(small.maxRawTokenCount() < large.maxRawTokenCount());
    }

    @Test
    void budgetClampedToMinimum() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(100, 1, 1000L);
        assertEquals(1000, p.totalContextTokenBudget());
        assertEquals(1000, p.chatInputTokenBudget());
    }

    @Test
    void contextBudgetScalesWithPolicy() {
        AXContextBudget small = AXContextBudget.fromPolicy(AXMemoryWindowPolicy.fromBudget(3000, 3, 60000L));
        AXContextBudget large = AXContextBudget.fromPolicy(AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L));

        assertEquals(3, small.maxRecentRawDialogueTurns());
        assertEquals(9, large.maxRecentRawDialogueTurns());

        assertEquals(4, small.maxRetrievedMemoryItems());
        assertEquals(12, large.maxRetrievedMemoryItems());
        assertEquals(1, small.maxRecentMemoryItems());
        assertEquals(4, large.maxRecentMemoryItems());
        assertEquals(5, small.maxKnowledgeRagItems());
        assertEquals(14, large.maxKnowledgeRagItems());
        assertEquals(180, small.systemTokenBudget());
        assertEquals(480, large.systemTokenBudget());
        assertEquals(1000, small.maxCurrentInputChars());
        assertEquals(1000, large.maxCurrentInputChars());

        assertTrue(small.retrievedMemoryTokenBudget() < large.retrievedMemoryTokenBudget());
        assertTrue(small.recentMemoryTokenBudget() < large.recentMemoryTokenBudget());
    }
}
