package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.function.ir.enhance.IrNamedObjectEnhancementResult;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrDialogueArbitrationRequestMapperTest {
    @Test
    void mapsFinalRepairedInputAndTextFeaturesIntoDialogueRequest() {
        IrDialogueArbitrationRequestMapper mapper = new IrDialogueArbitrationRequestMapper();
        IrInputText sourceInput = new IrInputText("give me dimond sword", "give me dimond sword", 7, 9L, "asr:voice", 100L);
        IrPreparedInput prepared = new IrPreparedInput(sourceInput, "give me dimond sword", "dimond sword", List.of("give me"), List.of());
        IrNamedObjectEnhancementResult enhancement = new IrNamedObjectEnhancementResult("give me diamond sword", List.of("diamond sword"), List.of("minecraft:diamond_sword"), true);
        IrInputText repairedInput = new IrInputText(enhancement.repairedText(), sourceInput.rawText(), sourceInput.turnId(), sourceInput.sessionId(), sourceInput.source(), sourceInput.createdAt());
        IrMatchBatch batch = new IrMatchBatch(repairedInput, List.of(new IrVoiceMatch("module.someone", List.of("assistant"), List.of("diamond sword"), 0.8D)));

        var payload = mapper.map(repairedInput, prepared, enhancement, batch);

        assertFalse(payload.requestId().isBlank());
        assertEquals(IrProtocolAdapter.MODULE_ID, payload.sourceModuleId());
        assertEquals("asr:voice", payload.playerId());
        assertEquals("7", payload.turnId());
        assertEquals(9L, payload.sourceSessionId());
        assertEquals("give me diamond sword", payload.repairedText());
        assertEquals("dimond sword", payload.normalizedText());
        assertEquals(List.of("assistant"), payload.matchedWakeWords());
        assertEquals(List.of("minecraft:diamond_sword"), payload.matchedItemIds());
        assertTrue(payload.expireAtMillis() > payload.timestampMillis());
    }

    @Test
    void mapsMissingSourceToLocalPlayerId() {
        IrDialogueArbitrationRequestMapper mapper = new IrDialogueArbitrationRequestMapper();
        IrInputText input = new IrInputText("hello", "hello", 1, 0L, "", 100L);

        var payload = mapper.map(input, null, null, null);

        assertEquals("local", payload.playerId());
        assertEquals("hello", payload.repairedText());
    }
}
