package com.rheinmetal.tianshu.function.ir.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IRCommandServiceNamedObjectRepairTest {
    private final IRCommandService service = new IRCommandService();

    @Test
    void repairsCommonAndUncommonMinecraftNamesFromPinyinLikeAsrErrors() {
        service.rebuild(testDictionary());

        assertRepaired(
                "\u9879\u76ee\u4eec\u600e\u4e48\u5408\u6210",
                "\u6a61\u6728\u95e8\u600e\u4e48\u5408\u6210",
                "minecraft:oak_door"
        );
        assertRepaired(
                "\u4e0b\u5c4a\u5408\u91d1\u952d\u80fd\u505a\u4ec0\u4e48",
                "\u4e0b\u754c\u5408\u91d1\u952d\u80fd\u505a\u4ec0\u4e48",
                "minecraft:netherite_ingot"
        );
        assertRepaired(
                "\u5e7d\u5c3c\u50ac\u53d1\u4f53\u9644\u8fd1\u6709\u4ec0\u4e48",
                "\u5e7d\u533f\u50ac\u53d1\u4f53\u9644\u8fd1\u6709\u4ec0\u4e48",
                "minecraft:sculk_catalyst"
        );
        assertRepaired(
                "\u78e8\u4e4b\u9ed1\u77f3\u7816\u600e\u4e48\u505a",
                "\u78e8\u5236\u9ed1\u77f3\u7816\u600e\u4e48\u505a",
                "minecraft:polished_blackstone_bricks"
        );
    }

    @Test
    void keepsAmbiguousShortTextUnclaimedWhenTopCandidatesAreTooClose() {
        service.rebuild(testDictionary());

        IRParseResult result = service.parse(
                "\u6728\u95e8",
                () -> Set.of(),
                true
        );

        assertTrue(result.getUnits().isEmpty());
    }

    @Test
    void englishRegistryAliasDoesNotPretendToRepairUnrelatedChineseText() {
        Map<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.put("mod:obscure_machine", List.of("obscure_machine"));
        service.rebuild(dictionary);

        IRParseResult result = service.parse(
                "\u5947\u602a\u673a\u5668\u600e\u4e48\u7528",
                () -> Set.of(),
                true
        );

        assertEquals("\u5947\u602a\u673a\u5668\u600e\u4e48\u7528", result.getHealedRawText());
        assertTrue(result.getUnits().isEmpty());
    }

    @Test
    void repairsMinecraftEntityNamesWithoutTreatingThemAsItemCommands() {
        Map<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.put(IRObjectId.item("minecraft:oak_door"), List.of("\u6a61\u6728\u95e8", "oak_door"));
        dictionary.put(IRObjectId.entity("minecraft:creeper"), List.of("\u82e6\u529b\u6015", "creeper"));
        dictionary.put(IRObjectId.entity("minecraft:villager"), List.of("\u6751\u6c11", "villager"));
        service.rebuild(dictionary);

        IRParseResult result = service.parse(
                "\u82e6\u529b\u5e15\u5728\u54ea\u91cc",
                () -> Set.of(),
                true
        );

        assertEquals("\u82e6\u529b\u6015\u5728\u54ea\u91cc", result.getHealedRawText());
        assertEquals(List.of("minecraft:creeper"), result.getMatchedEntityTypeIds());
        assertTrue(result.getMatchedItemRealIds().isEmpty());
        assertTrue(result.getUnits().isEmpty());
    }

    @Test
    void resolvesItemAndEntityDisplayNamesSeparatelyWhenRawIdsCollide() {
        Map<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.put(IRObjectId.item("minecraft:minecart"), List.of("\u77ff\u8f66\u7269\u54c1", "minecart_item"));
        dictionary.put(IRObjectId.entity("minecraft:minecart"), List.of("\u77ff\u8f66\u5b9e\u4f53", "minecart_entity"));
        service.rebuild(dictionary);

        assertEquals("\u77ff\u8f66\u7269\u54c1", service.resolveDisplayName("minecraft:minecart"));
        assertEquals("\u77ff\u8f66\u5b9e\u4f53", service.resolveEntityDisplayName("minecraft:minecart"));
    }

    private void assertRepaired(String asrText, String repairedText, String expectedItemId) {
        IRParseResult result = service.parse(asrText, () -> Set.of(), true);

        assertEquals(repairedText, result.getHealedRawText());
        assertTrue(
                result.getMatchedItemRealIds().contains(expectedItemId),
                "Expected matched item id " + expectedItemId + " from " + result.getMatchedItemRealIds()
        );
    }

    private Map<String, List<String>> testDictionary() {
        Map<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.put("minecraft:oak_door", List.of("\u6a61\u6728\u95e8", "oak_door"));
        dictionary.put("minecraft:dark_oak_door", List.of("\u6df1\u8272\u6a61\u6728\u95e8", "dark_oak_door"));
        dictionary.put("minecraft:netherite_ingot", List.of("\u4e0b\u754c\u5408\u91d1\u952d", "netherite_ingot"));
        dictionary.put("minecraft:netherite_scrap", List.of("\u4e0b\u754c\u5408\u91d1\u788e\u7247", "netherite_scrap"));
        dictionary.put("minecraft:sculk_catalyst", List.of("\u5e7d\u533f\u50ac\u53d1\u4f53", "sculk_catalyst"));
        dictionary.put("minecraft:sculk_sensor", List.of("\u5e7d\u533f\u611f\u6d4b\u4f53", "sculk_sensor"));
        dictionary.put("minecraft:polished_blackstone_bricks", List.of("\u78e8\u5236\u9ed1\u77f3\u7816", "polished_blackstone_bricks"));
        dictionary.put("minecraft:chiseled_polished_blackstone", List.of("\u96d5\u7eb9\u78e8\u5236\u9ed1\u77f3", "chiseled_polished_blackstone"));
        return dictionary;
    }
}
