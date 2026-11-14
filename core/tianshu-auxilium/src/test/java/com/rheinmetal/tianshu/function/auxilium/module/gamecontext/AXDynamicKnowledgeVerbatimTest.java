package com.rheinmetal.tianshu.function.auxilium.module.gamecontext;

import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguageProvider;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceContextFactIds;
import com.rheinmetal.tianshu.protocol.payload.FactField;
import com.rheinmetal.tianshu.protocol.payload.FactValue;
import com.rheinmetal.tianshu.function.presence.payload.PresenceContextSnapshotPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Freezes the prompt sentences the formatter produces today, before it is switched from the flat value map to
 * {@code FactPayload.fields}.
 *
 * <p>This is the guard rail for that switch: the wording comes from the {@code dynamic_knowledge.*} templates
 * and must not change when only the source of the values changes. Every expectation here is the literal output
 * of the <em>current</em> implementation, captured by running it — not by reasoning about the templates.
 *
 * <p>One known, deliberate difference is recorded rather than fixed: a screen kind is worded with
 * {@code screenKind.toLowerCase()} (for example {@code pause}), not with the localized display name presence
 * uses ({@code pause menu}). That divergence predates this work and is kept on purpose.
 */
class AXDynamicKnowledgeVerbatimTest {
    private final AXDynamicKnowledgeFormatter formatter = new AXDynamicKnowledgeFormatter(
            null, AXPromptLanguageProvider.fixed(AXPromptLanguage.ZH_CN));

    @Test
    void playerStatusKeepsItsWording() {
        assertEquals(
                "当前玩家位于overworld，生命值约20.0，饥饿值约20，经验等级为5。",
                render(PresenceContextFactIds.PLAYER_STATUS, values(
                        "health", "20.0", "maxHealth", "20.0", "hunger", "20",
                        "experienceLevel", "5", "dimensionId", "minecraft:overworld")));
    }

    @Test
    void worldEnvironmentKeepsItsWording() {
        assertEquals(
                "当前生物群系是Plains，天气是晴朗，当前游戏时间是12:00。",
                render(PresenceContextFactIds.WORLD_ENVIRONMENT, values(
                        "dimensionId", "minecraft:overworld", "biomeId", "minecraft:plains",
                        "biomeDisplayName", "Plains", "raining", "false", "thundering", "false",
                        "dayTimeTicks", "6000")));
    }

    @Test
    void inventoryKeepsItsWordingAndItsItemOrder() {
        // Current output, quirky spacing included: behaviour must not change, only where the values come from.
        assertEquals(
                "当前玩家背包中有：Bread少量，Stick少量。",
                render(PresenceContextFactIds.PLAYER_INVENTORY, values(
                        "itemCount", "2", "items", "minecraft:bread:3|minecraft:stick:2"),
                        itemRecord("minecraft:bread", "Bread", 3, 64),
                        itemRecord("minecraft:stick", "Stick", 2, 64)));
    }

    @Test
    void activeEffectsKeepTheirWording() {
        assertEquals(
                "当前状态效果：SpeedI，剩余10秒。",
                render(PresenceContextFactIds.PLAYER_ACTIVE_EFFECTS, values(
                        "effects", "minecraft:speed:0:200"),
                        effectRecord("minecraft:speed", "Speed", 200, 0)));
    }

    @Test
    void interactionContextKeepsItsWording() {
        assertEquals(
                "当前交互：手持diamond sword，正在按住使用键，准星指向Cow，距离约3.5格。",
                render(PresenceContextFactIds.INTERACTION_CONTEXT, values(
                        "playerId", "Steve", "dimensionId", "minecraft:overworld",
                        "heldItemId", "minecraft:diamond_sword", "screenKind", "NONE",
                        "interactionKeyDown", "true", "sneaking", "false",
                        "crosshairTargetId", "entity-1", "crosshairTargetTypeId", "minecraft:cow",
                        "crosshairTargetDisplayName", "Cow", "crosshairTargetDistance", "3.5")));
    }

    /**
     * The recorded, deliberate divergence: the prompt words a screen by its enum name, not by the localized
     * display name presence uses. Asserted so that a future change here is a decision, not an accident.
     */
    @Test
    void aScreenKindIsWordedByItsEnumNameNotByALocalizedName() {
        String rendered = render(PresenceContextFactIds.INTERACTION_CONTEXT, values(
                "playerId", "Steve", "dimensionId", "minecraft:overworld",
                "screenKind", "PAUSE"));

        assertEquals("当前交互：打开pause界面。", rendered);
    }

    /**
     * Only slots the player fills are named; armour is reported even when nothing is worn.
     */
    @Test
    void equipmentNamesOnlyFilledSlotsAndTheArmourNumber() {
        assertEquals(
                "玩家装备：头盔是Diamond Helmet，主手是Diamond Sword。护甲值 15。",
                render(PresenceContextFactIds.PLAYER_EQUIPMENT, values("armorPoints", "15", "armorToughness", "2.0"),
                        slotRecord("helmet", "minecraft:diamond_helmet", "Diamond Helmet"),
                        slotRecord("mainHand", "minecraft:diamond_sword", "Diamond Sword")));
    }

    /**
     * Nothing worn, nothing held and no armour means there is nothing to say, so no fact reaches the prompt.
     */
    @Test
    void equipmentWithNothingOnItProducesNoFact() {
        AXDynamicFact fact = formatter.format(new PresenceContextSnapshotPayload.FactPayload(
                PresenceContextFactIds.PLAYER_EQUIPMENT,
                List.of(new FactField("armorPoints", new FactValue.Count(0))),
                84, "presence", "player", List.of(), 1_000L, 60_000L));

        assertNull(fact, "an empty equipment fact must not reach the prompt");
    }

    private static FactField slotRecord(String slot, String itemId, String displayName) {
        return new FactField(slot, new FactValue.Record(List.of(
                new FactField("itemId", new FactValue.Identifier(itemId)),
                new FactField("displayName", new FactValue.Text(displayName)),
                new FactField("count", new FactValue.Count(1)),
                new FactField("maxStackSize", new FactValue.Count(1)))));
    }
    /**
     * Ticks become wall-clock time: 0 is sunrise, 6000 noon, 12000 dusk and 18000 midnight. Negative and
     * out-of-range counts wrap instead of throwing, which matters for modded dimensions.
     */
    @Test
    void dayTicksBecomeWallClockTime() {
        assertEquals("当前生物群系是Plains，天气是晴朗，当前游戏时间是06:00。", environmentAt(0));
        assertEquals("当前生物群系是Plains，天气是晴朗，当前游戏时间是12:00。", environmentAt(6000));
        assertEquals("当前生物群系是Plains，天气是晴朗，当前游戏时间是18:00。", environmentAt(12000));
        assertEquals("当前生物群系是Plains，天气是晴朗，当前游戏时间是00:00。", environmentAt(18000));
        assertEquals("当前生物群系是Plains，天气是晴朗，当前游戏时间是12:00。", environmentAt(6000 + 24000));
        assertEquals("当前生物群系是Plains，天气是晴朗，当前游戏时间是05:54。", environmentAt(-100));
    }

    private String environmentAt(long ticks) {
        return render(PresenceContextFactIds.WORLD_ENVIRONMENT, values(
                "dimensionId", "minecraft:overworld", "biomeId", "minecraft:plains",
                "biomeDisplayName", "Plains", "raining", "false", "thundering", "false",
                "dayTimeTicks", Long.toString(ticks)));
    }
    private String render(String factId, Map<String, String> values, FactField... records) {
        List<FactField> fields = new ArrayList<>(fieldsFor(values));
        fields.addAll(List.of(records));
        AXDynamicFact fact = formatter.format(new PresenceContextSnapshotPayload.FactPayload(
                factId, fields, 80, "presence", "subject", List.of(), 1_000L, 60_000L));
        assertNotNull(fact, "fact must render: " + factId);
        return fact.text();
    }

    /**
     * Turns the fixture's flat expectations into typed fields.
     *
     * <p>The formatter reads {@code fields} now, so the fixture has to speak that language. The inference here
     * only serves the fixture — production builds its fields through an explicit per-fact list — and it is
     * deliberately the same convention presence uses ({@code true}/{@code false} is a flag, a dotted number is
     * a decimal, a plain integer is a count, a namespaced value is an identifier), so a mis-typed fixture
     * cannot make the byte comparison pass by accident.</p>
     */
    private static List<FactField> fieldsFor(Map<String, String> values) {
        List<FactField> fields = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String raw = entry.getValue();
            fields.add(new FactField(entry.getKey(), typed(raw)));
        }
        return fields;
    }

    private static FactValue typed(String raw) {
        if ("true".equals(raw) || "false".equals(raw)) {
            return new FactValue.Flag(Boolean.parseBoolean(raw));
        }
        if (raw.matches("-?\\d+")) {
            return new FactValue.Count(Integer.parseInt(raw));
        }
        if (raw.matches("-?\\d+\\.\\d+")) {
            return new FactValue.Number(Double.parseDouble(raw));
        }
        if (raw.indexOf(':') >= 0) {
            return new FactValue.Identifier(raw);
        }
        return new FactValue.Text(raw);
    }

    private static FactField itemRecord(String itemId, String displayName, int count, int maxStackSize) {
        return new FactField("item", new FactValue.Record(List.of(
                new FactField("itemId", new FactValue.Identifier(itemId)),
                new FactField("displayName", new FactValue.Text(displayName)),
                new FactField("count", new FactValue.Count(count)),
                new FactField("maxStackSize", new FactValue.Count(maxStackSize)))));
    }

    private static FactField effectRecord(String effectId, String displayName, int durationTicks, int amplifier) {
        return new FactField("effect", new FactValue.Record(List.of(
                new FactField("effectId", new FactValue.Identifier(effectId)),
                new FactField("displayName", new FactValue.Text(displayName)),
                new FactField("durationTicks", new FactValue.Count(durationTicks)),
                new FactField("amplifier", new FactValue.Count(amplifier)))));
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }
}
