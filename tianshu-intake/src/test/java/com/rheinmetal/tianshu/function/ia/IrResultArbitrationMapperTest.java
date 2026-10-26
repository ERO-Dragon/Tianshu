package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.function.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrResultPayload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IrResultArbitrationMapperTest {
    @Test
    void carriesMatchedObjectsAndTriggerWordsAsEvidence() {
        List<IrObjectMatch> objectMatches = List.of(
                new IrObjectMatch("钻石锄", "minecraft:diamond_hoe", "item", "item.minecraft.diamond_hoe"),
                new IrObjectMatch("女仆", "touhou_little_maid:maid", "entity", "entity.touhou_little_maid.maid")
        );
        List<IrModuleMatch> moduleMatches = List.of(
                new IrModuleMatch("module.maid", "酒狐", "酒狐"),
                new IrModuleMatch("module.maid", "种地", "种地")
        );
        IrResultPayload result = new IrResultPayload(
                "酒狐帮我种地",
                "酒狐帮我种地",
                "九狐帮我种地",
                objectMatches,
                moduleMatches,
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

        assertEquals(objectMatches, request.objectMatches());
        assertEquals(moduleMatches, request.moduleMatches());
        assertEquals(2, request.moduleMatches().size(), "every trigger word is one entry, not one entry per module");
        assertEquals("酒狐帮我种地", request.repairedText());
        assertEquals("local", request.playerId());
    }

    /**
     * The words the player used travel with the id they resolved to, so nothing has to rebuild a name afterwards.
     *
     * <p>That pairing is why the request carries no id-to-name map of its own: the request asks who owns the turn,
     * and the names it may want are already attached to the match that produced them.</p>
     */
    @Test
    void theSpokenWordTravelsWithTheIdInsteadOfASeparateNameMap() {
        IrResultPayload result = new IrResultPayload(
                "酒狐帮我种地",
                "酒狐帮我种地",
                "酒狐帮我种地",
                List.of(new IrObjectMatch("钻石锄", "minecraft:diamond_hoe", "item", "item.minecraft.diamond_hoe")),
                List.of(),
                7,
                92L,
                1_000L
        );
        var envelope = EnvelopeBuilder.eventTopic(
                "module.ir",
                ProtocolTopics.IR_RESULT,
                PayloadType.IR_RESULT,
                result
        ).build();

        DialogueArbitrationRequestPayload request = new IrResultArbitrationMapper().map(envelope, result);

        assertEquals("钻石锄", request.objectMatches().get(0).spokenWord());
        assertEquals("minecraft:diamond_hoe", request.objectMatches().get(0).registryId());
        assertFalse(Stream.of(DialogueArbitrationRequestPayload.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .anyMatch("displayNames"::equals),
                "the request carries evidence, not a name map");
    }
}
