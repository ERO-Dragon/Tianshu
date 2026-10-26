package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which presence world events become an event of the layer, and which stay a memory fact.
 *
 * <p>The mapper owns only the "fact to kind" step: the wording key comes from this module's own catalog, and whether
 * the line is spoken is not its business — that is the tier table's.
 */
class AXPresenceEventMapperTest {

    @Test
    void anAdvancementBecomesTheEventLayersLine() {
        Optional<AXEventDescriptor> descriptor = AXPresenceEventMapper.describe(advancement("成就：探险时间"),
                "module.presence");

        assertTrue(descriptor.isPresent());
        assertEquals("tianshu:advancement", descriptor.get().kind());
        assertEquals("tianshu.ax.event.line.tianshu.advancement", descriptor.get().text().key());
        assertEquals("成就：探险时间", argument(descriptor.get().text()),
                "the localized title is what the sentence substitutes");
    }

    @Test
    void aMissingTitleStillProducesTheLine() {
        Optional<AXEventDescriptor> descriptor = AXPresenceEventMapper.describe(
                new PresenceWorldEventPayload(null, PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED,
                        "player", "minecraft:overworld", 1L, Map.of()),
                "module.presence");

        assertTrue(descriptor.isPresent());
        assertTrue(descriptor.get().text().args().isEmpty(),
                "an event without a detail keeps the template and drops the hole");
    }

    /** The facts the optional server component reports: each one lands on its own kind and values. */
    @Test
    void theServerFactsBecomeTheirOwnKinds() {
        assertKind(PresenceWorldEventPayload.EVENT_PLAYER_DEATH, Map.of(
                PresenceWorldEventPayload.VALUE_DEATH_MESSAGE, "被僵尸杀死"), "tianshu:death", "被僵尸杀死");
        assertKind(PresenceWorldEventPayload.EVENT_MOB_KILLED, Map.of(
                PresenceWorldEventPayload.VALUE_ENTITY_NAME, "僵尸"), "tianshu:mob_killed", "僵尸");
        assertKind(PresenceWorldEventPayload.EVENT_FISHING_CATCH, Map.of(
                PresenceWorldEventPayload.VALUE_ITEM_NAME, "鳕鱼"), "tianshu:fishing_catch", "鳕鱼");
        assertKind(PresenceWorldEventPayload.EVENT_TAMED, Map.of(
                PresenceWorldEventPayload.VALUE_ENTITY_NAME, "狼"), "tianshu:tamed", "狼");
        assertKind(PresenceWorldEventPayload.EVENT_DIMENSION_CHANGED, Map.of(
                PresenceWorldEventPayload.VALUE_DIMENSION_NAME, "下界"), "tianshu:dimension_enter", "下界");
    }

    private static void assertKind(String factId, Map<String, String> values, String expectedKind,
                                   String expectedArgument) {
        Optional<AXEventDescriptor> descriptor = AXPresenceEventMapper.describe(
                new PresenceWorldEventPayload(null, factId, "player", "minecraft:overworld", 1L, values),
                "module.tianshu");

        assertTrue(descriptor.isPresent(), "no rule for " + factId);
        assertEquals(expectedKind, descriptor.get().kind());
        assertEquals(expectedArgument, argument(descriptor.get().text()));
    }

    /** A craft the server watched: the resolved name is preferred, the registry id is the fallback. */
    @Test
    void aCraftBecomesTheEventLayersLine() {
        Optional<AXEventDescriptor> named = AXPresenceEventMapper.describe(craft(Map.of(
                PresenceWorldEventPayload.VALUE_ITEM_NAME, "橡木木板",
                PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:oak_planks")), "module.tianshu");

        assertTrue(named.isPresent());
        assertEquals("tianshu:item_crafted", named.get().kind());
        assertEquals("tianshu.ax.event.line.tianshu.item_crafted", named.get().text().key());
        assertEquals("橡木木板", argument(named.get().text()));

        Optional<AXEventDescriptor> idOnly = AXPresenceEventMapper.describe(craft(Map.of(
                PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:oak_planks")), "module.tianshu");
        assertEquals("minecraft:oak_planks", argument(idOnly.orElseThrow().text()),
                "without a name the id still words the line");
    }

    /** Chat is something said, and an unknown type is a fact of its own: both stay out of the event layer. */
    @Test
    void chatAndUnknownTypesStayOutOfTheEventLayer() {
        assertTrue(AXPresenceEventMapper.describe(
                PresenceWorldEventPayload.chatMessage("uuid-1", "甲", "在吗"), "module.presence").isEmpty());
        assertTrue(AXPresenceEventMapper.describe(
                new PresenceWorldEventPayload(null, "mymod:custom_fact", "player", "minecraft:overworld", 1L,
                        Map.of()), "module.presence").isEmpty());
        assertTrue(AXPresenceEventMapper.describe(null, "module.presence").isEmpty());
    }

    private static PresenceWorldEventPayload advancement(String title) {
        return new PresenceWorldEventPayload(null,
                PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED,
                "player", "minecraft:overworld", 1L,
                Map.of(PresenceWorldEventPayload.VALUE_TITLE, title));
    }

    private static PresenceWorldEventPayload craft(Map<String, String> values) {
        return new PresenceWorldEventPayload(null, PresenceWorldEventPayload.EVENT_ITEM_CRAFTED,
                "player", "minecraft:overworld", 1L, values);
    }

    private static String argument(AXEventText text) {
        if (text.args().isEmpty()) {
            return "";
        }
        return ((AXEventText.Arg.Value) text.args().get(0)).text();
    }
}
