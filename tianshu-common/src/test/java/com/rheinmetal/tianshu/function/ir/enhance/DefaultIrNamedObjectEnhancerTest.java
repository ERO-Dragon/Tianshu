package com.rheinmetal.tianshu.function.ir.enhance;

import com.rheinmetal.tianshu.function.ir.core.IRCommandService;
import com.rheinmetal.tianshu.function.ir.core.IRObjectId;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import com.rheinmetal.tianshu.function.ir.input.IrPreparedInput;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultIrNamedObjectEnhancerTest {
    private final IRCommandService commandService = new IRCommandService();
    private final DefaultIrNamedObjectEnhancer enhancer = new DefaultIrNamedObjectEnhancer(commandService);

    @Test
    void repairsItemAndEntityMentionsThroughOneNamedObjectIndex() {
        commandService.rebuild(namedObjectDictionary());

        IrNamedObjectEnhancementResult result = enhancer.enhance(input("\u9879\u76ee\u4eec\u548c\u82e6\u529b\u5e15\u5728\u54ea\u91cc"));

        assertTrue(result.matched());
        assertEquals("\u6a61\u6728\u95e8\u548c\u82e6\u529b\u6015\u5728\u54ea\u91cc", result.repairedText());
        assertEquals(List.of("\u6a61\u6728\u95e8"), result.matchedItemNames());
        assertEquals(List.of("minecraft:oak_door"), result.matchedItemIds());
        assertEquals(List.of("\u82e6\u529b\u6015"), result.matchedEntityNames());
        assertEquals(List.of("minecraft:creeper"), result.matchedEntityTypeIds());
    }

    @Test
    void resolvesCollidingRawIdsByNamedObjectKind() {
        commandService.rebuild(namedObjectDictionary());

        IrNamedObjectEnhancementResult itemResult = enhancer.enhance(input("\u77ff\u8f66\u7269\u54c1\u600e\u4e48\u505a"));
        IrNamedObjectEnhancementResult entityResult = enhancer.enhance(input("\u77ff\u8f66\u5b9e\u4f53\u5728\u54ea\u91cc"));

        assertEquals(List.of("\u77ff\u8f66\u7269\u54c1"), itemResult.matchedItemNames());
        assertEquals(List.of("minecraft:minecart"), itemResult.matchedItemIds());
        assertTrue(itemResult.matchedEntityTypeIds().isEmpty());

        assertEquals(List.of("\u77ff\u8f66\u5b9e\u4f53"), entityResult.matchedEntityNames());
        assertEquals(List.of("minecraft:minecart"), entityResult.matchedEntityTypeIds());
        assertTrue(entityResult.matchedItemIds().isEmpty());
    }

    @Test
    void returnsEmptyResultWhenIndexIsNotReady() {
        IrNamedObjectEnhancementResult result = enhancer.enhance(input("\u9879\u76ee\u4eec"));

        assertFalse(result.matched());
        assertEquals("\u9879\u76ee\u4eec", result.repairedText());
        assertTrue(result.matchedItemIds().isEmpty());
        assertTrue(result.matchedEntityTypeIds().isEmpty());
    }

    private IrPreparedInput input(String text) {
        IrInputText source = new IrInputText(text, text, 1, 1L, "test", 100L);
        return new IrPreparedInput(source, text, text, List.of(), List.of());
    }

    private Map<String, List<String>> namedObjectDictionary() {
        Map<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.put(IRObjectId.item("minecraft:oak_door"), List.of("\u6a61\u6728\u95e8", "oak_door"));
        dictionary.put(IRObjectId.entity("minecraft:creeper"), List.of("\u82e6\u529b\u6015", "creeper"));
        dictionary.put(IRObjectId.item("minecraft:minecart"), List.of("\u77ff\u8f66\u7269\u54c1", "minecart_item"));
        dictionary.put(IRObjectId.entity("minecraft:minecart"), List.of("\u77ff\u8f66\u5b9e\u4f53", "minecart_entity"));
        return dictionary;
    }
}
