package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.enhance.IrItemEnhancementResult;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrDialogueArbitrationRequestMapperTest {
    @Test
    void mapsPreparedInputAndEnhancementIntoDialogueRequest() {
        IrDialogueArbitrationRequestMapper mapper = new IrDialogueArbitrationRequestMapper();
        IrInputText input = new IrInputText("给我看看钻石剑", "给我看看钻石剑", 7, 9L, "asr:voice", 100L);
        IrPreparedInput prepared = new IrPreparedInput(input, "给我看看钻石剑", "看看钻石剑", List.of("给我"), List.of());
        IrItemEnhancementResult enhancement = new IrItemEnhancementResult("看看钻石剑", List.of("钻石剑"), List.of("minecraft:diamond_sword"), true);
        IrMatchBatch batch = new IrMatchBatch(input, List.of(new IrVoiceMatch("module.someone", List.of("看看"), List.of("钻石剑"), 0.8D)));

        var payload = mapper.map(input, prepared, enhancement, batch);

        assertFalse(payload.requestId().isBlank());
        assertEquals(IrProtocolAdapter.MODULE_ID, payload.sourceModuleId());
        assertEquals("asr:voice", payload.playerId());
        assertEquals("7", payload.turnId());
        assertEquals(9L, payload.sourceSessionId());
        assertEquals("看看钻石剑", payload.repairedText());
        assertEquals("看看钻石剑", payload.normalizedText());
        assertEquals(List.of("看看"), payload.matchedWakeWords());
        assertEquals(List.of("minecraft:diamond_sword"), payload.matchedItemIds());
        assertTrue(payload.expireAtMillis() > payload.timestampMillis());
    }

    @Test
    void mapsMissingSourceToLocalPlayerId() {
        IrDialogueArbitrationRequestMapper mapper = new IrDialogueArbitrationRequestMapper();
        IrInputText input = new IrInputText("你好", "你好", 1, 0L, "", 100L);

        var payload = mapper.map(input, null, null, null);

        assertEquals("local", payload.playerId());
        assertEquals("你好", payload.repairedText());
    }
}
