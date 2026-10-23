package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;
import com.rheinmetal.tianshu.protocol.voice.VoiceTriggerMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IrResultArbitrationMapperTest {
    @Test
    void preservesStructuredVoiceEvidenceWithoutGivingExtraWordsClaimMeaning() {
        IrResultPayload result = new IrResultPayload(
                "酒狐帮我种地",
                "酒狐帮我种地",
                List.of(new VoiceTriggerMatch("module.maid", List.of("酒狐"), List.of("种地"), 0.8D)),
                List.of("minecraft:diamond_hoe"),
                List.of("touhou_little_maid:maid"),
                6,
                91L,
                1_000L
        );
        var envelope = EnvelopeBuilder.eventTopic(
                "module.ir",
                ProtocolTopics.IR_RESULT,
                PayloadType.IR_RESULT,
                result
        ).build();

        DialogueArbitrationRequestPayload request = new IrResultArbitrationMapper().map(envelope, result);

        assertEquals(result.voiceMatches(), request.voiceMatches());
        assertEquals(List.of("酒狐"), request.matchedWakeWords());
        assertEquals(List.of("种地"), request.matchedExtraWords());
        assertEquals(List.of("minecraft:diamond_hoe"), request.matchedItemIds());
        assertEquals(List.of("touhou_little_maid:maid"), request.matchedEntityTypeIds());
        assertEquals("local", request.playerId());
    }

    /**
     * The names the repair step matched must survive the hop, otherwise the id travels alone and every reader has
     * to rebuild the name it was shown.
     */
    @Test
    void carriesTheDisplayNamesOfTheMatchedObjects() {
        IrResultPayload result = new IrResultPayload(
                "酒狐帮我种地",
                "酒狐帮我种地",
                List.of(),
                List.of("minecraft:diamond_hoe"),
                List.of("touhou_little_maid:maid"),
                7,
                92L,
                1_000L,
                Map.of("minecraft:diamond_hoe", "钻石锄", "touhou_little_maid:maid", "女仆")
        );
        var envelope = EnvelopeBuilder.eventTopic(
                "module.ir",
                ProtocolTopics.IR_RESULT,
                PayloadType.IR_RESULT,
                result
        ).build();

        DialogueArbitrationRequestPayload request = new IrResultArbitrationMapper().map(envelope, result);

        assertEquals("钻石锄", request.displayNames().get("minecraft:diamond_hoe"));
        assertEquals("女仆", request.displayNames().get("touhou_little_maid:maid"));
    }
}
