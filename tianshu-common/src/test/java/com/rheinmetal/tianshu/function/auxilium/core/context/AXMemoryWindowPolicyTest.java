package com.rheinmetal.tianshu.function.auxilium.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXMemoryWindowPolicyTest {

    @Test
    void fromBudget_8000_matchesReferenceDefaults() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L);

        assertEquals(8000, p.chatInputTokenBudget());
        assertEquals(2000, p.recentRawChatTokenBudget());
        assertEquals(2000, p.shortTermChatTokenBudget());
        assertEquals(1500, p.memoryRagTokenBudget());
        assertEquals(1000, p.staticContentTokenBudget());
        assertEquals(1000, p.dynamicContentTokenBudget());

        assertEquals(5000, p.recentRawKeepTokenTarget());
        assertEquals(8000, p.recentRawKeepTokenMax());
        assertEquals(7000, p.shortTermCompressTokenTarget());
        assertEquals(10000, p.shortTermCompressTokenMax());
        assertEquals(28000, p.maxRawEstimatedTokens());
        assertEquals(120000, p.maxRawCharacters());
    }

    @Test
    void fromBudget_3000_scalesProportionally() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(3000, 3, 60000L);

        assertEquals(3000, p.chatInputTokenBudget());
        assertEquals(750, p.recentRawChatTokenBudget());    // 25%
        assertEquals(750, p.shortTermChatTokenBudget());     // 3000*0.25
        assertEquals(562, p.memoryRagTokenBudget());         // 3000*0.1875
        assertEquals(375, p.staticContentTokenBudget());
        assertEquals(375, p.dynamicContentTokenBudget());

        double scale = 3000.0 / 8000.0;
        assertEquals((int)(5000 * scale),  p.recentRawKeepTokenTarget());
        assertEquals((int)(8000 * scale),  p.recentRawKeepTokenMax());
        assertEquals((int)(7000 * scale),  p.shortTermCompressTokenTarget());
        assertEquals((int)(10000 * scale), p.shortTermCompressTokenMax());
        assertEquals((int)(28000 * scale), p.maxRawEstimatedTokens());
        assertEquals((int)(120000 * scale), p.maxRawCharacters());
    }

    @Test
    void fromBudget_12000_scalesProportionally() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(12000, 3, 60000L);

        assertEquals(12000, p.chatInputTokenBudget());
        assertEquals(3000,  p.recentRawChatTokenBudget());
        assertEquals(3000,  p.shortTermChatTokenBudget());
        assertEquals(2250,  p.memoryRagTokenBudget());
        assertEquals(1500,  p.staticContentTokenBudget());
        assertEquals(1500,  p.dynamicContentTokenBudget());
    }

    @Test
    void slotRatiosAreConsistentAcrossBudgets() {
        for (int budget : new int[]{2000, 3000, 4000, 6500, 8000, 12000}) {
            AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(budget, 3, 60000L);
            int totalSlots = p.recentRawChatTokenBudget()
                    + p.shortTermChatTokenBudget()
                    + p.memoryRagTokenBudget()
                    + p.staticContentTokenBudget()
                    + p.dynamicContentTokenBudget();
            // slots should not exceed total budget
            assertTrue(totalSlots <= budget, "slots exceed budget at " + budget);
            // slots should leave headroom for system and chat-template overhead.
            assertTrue(budget - totalSlots >= budget * 0.05, "too little headroom at " + budget);
        }
    }

    @Test
    void compressionParamsShrinkWithSmallBudget() {
        AXMemoryWindowPolicy small = AXMemoryWindowPolicy.fromBudget(3000, 3, 60000L);
        AXMemoryWindowPolicy large = AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L);

        assertTrue(small.recentRawKeepTokenMax() < large.recentRawKeepTokenMax());
        assertTrue(small.shortTermCompressTokenMax() < large.shortTermCompressTokenMax());
        assertTrue(small.maxRawEstimatedTokens() < large.maxRawEstimatedTokens());
    }

    @Test
    void budgetClampedToMinimum() {
        AXMemoryWindowPolicy p = AXMemoryWindowPolicy.fromBudget(100, 1, 1000L);
        assertEquals(1000, p.chatInputTokenBudget());
    }

    @Test
    void contextBudgetScalesWithPolicy() {
        AXContextBudget small = AXContextBudget.fromPolicy(AXMemoryWindowPolicy.fromBudget(3000, 3, 60000L));
        AXContextBudget large = AXContextBudget.fromPolicy(AXMemoryWindowPolicy.fromBudget(8000, 3, 60000L));

        // 2B model (3000): 750/500 = 1 turn
        assertEquals(1, small.maxShortTermTurns());
        // Reference budget (8000): 2000/500 = 4 turns
        assertEquals(4, large.maxShortTermTurns());

        // memory items use the memory RAG budget, not a separate user-convention slot.
        assertEquals(5, small.maxMemoryItems());
        assertEquals(15, large.maxMemoryItems());
        assertEquals(3, small.maxStaticContentItems());
        assertEquals(10, large.maxStaticContentItems());

        assertTrue(small.memoryTokenBudget() < large.memoryTokenBudget());
    }
}
