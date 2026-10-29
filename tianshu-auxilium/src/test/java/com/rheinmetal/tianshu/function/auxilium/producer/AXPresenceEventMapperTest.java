package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which presence world events become an event of the layer, and how one payload becomes one line.
 *
 * <p>The mapper owns only the "fact to batch" step: the wording keys come from this module's own catalog, the parts are
 * rendered by the service, and whether the line is spoken is the tier table's business.</p>
 */
class AXPresenceEventMapperTest {

    @Test
    void anAdvancementBecomesTheEventLayersLine() {
        AXPresenceEventMapper.Batch batch = batch(advancement("成就：探险时间"));

        assertEquals("tianshu:advancement", batch.kind());
        assertEquals("tianshu.ax.event.line.tianshu.advancement", batch.lineKey());
        assertEquals(1, batch.totalCount());
        assertEquals(1, batch.parts().size());
        assertEquals("成就：探险时间", detailOf(batch.parts().get(0)),
                "the localized title is what the sentence substitutes");
    }

    @Test
    void aMissingDetailFallsBackToTheWholeSentence() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(null,
                PresenceWorldEventPayload.EVENT_ADVANCEMENT_UNLOCKED,
                "player", "minecraft:overworld", 1L, Map.of());

        AXPresenceEventMapper.Batch batch = batch(payload);

        assertTrue(batch.parts().isEmpty(), "no detail means the kind words itself as a whole sentence");
        assertEquals(1, batch.totalCount(), "the occurrence still counts");
        assertEquals("tianshu.ax.event.line.tianshu.advancement", batch.lineKey());
    }

    /** The facts the optional server component reports: each one lands on its own kind and detail. */
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

    /** A craft the server watched: the resolved name is preferred, the registry id is the fallback. */
    @Test
    void aCraftPrefersTheResolvedNameAndFallsBackToTheId() {
        AXPresenceEventMapper.Batch named = batch(craft(Map.of(
                PresenceWorldEventPayload.VALUE_ITEM_NAME, "橡木木板",
                PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:oak_planks")));
        assertEquals("tianshu:item_crafted", named.kind());
        assertEquals("tianshu.ax.event.line.tianshu.item_crafted", named.lineKey());
        assertEquals("橡木木板", detailOf(named.parts().get(0)));

        AXPresenceEventMapper.Batch idOnly = batch(craft(Map.of(
                PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:oak_planks")));
        assertEquals("minecraft:oak_planks", detailOf(idOnly.parts().get(0)),
                "without a name the id still words the line");
    }

    /** Chat is something said, and an unknown type is a fact of its own: both stay out of the event layer. */
    @Test
    void chatAndUnknownTypesStayOutOfTheEventLayer() {
        assertTrue(AXPresenceEventMapper.batch(chat("uuid-1", "甲", "在吗")).isEmpty());
        assertTrue(AXPresenceEventMapper.batch(
                new PresenceWorldEventPayload(null, "mymod:custom_fact", "player", "minecraft:overworld", 1L,
                        Map.of())).isEmpty());
        assertTrue(AXPresenceEventMapper.batch(null).isEmpty());
    }

    /**
     * Presence ships a second's worth of one kind as one payload with several groups; the batch must keep every group
     * and the summed count, otherwise the aggregation would silently drop everything except the first.
     */
    @Test
    void anAggregatedPayloadKeepsEveryGroupAndTheSummedCount() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(null,
                PresenceWorldEventPayload.EVENT_ITEM_PICKUP, "player", "minecraft:overworld", 1L,
                List.of(
                        new PresenceWorldEventPayload.Occurrence(20,
                                Map.of(PresenceWorldEventPayload.VALUE_ITEM_NAME, "泥土")),
                        new PresenceWorldEventPayload.Occurrence(60,
                                Map.of(PresenceWorldEventPayload.VALUE_ITEM_NAME, "圆石"))));

        AXPresenceEventMapper.Batch batch = batch(payload);

        assertEquals("tianshu:pickup", batch.kind());
        assertEquals("tianshu.ax.event.line.tianshu.pickup", batch.lineKey());
        assertEquals(2, batch.parts().size(), "two groups must not collapse into one");
        assertEquals(80, batch.totalCount(), "the frequency step needs the real number of occurrences");
        assertEquals("20", countOf(batch.parts().get(0)));
        assertEquals("泥土", detailOf(batch.parts().get(0)));
        assertEquals("60", countOf(batch.parts().get(1)));
    }

    private static void assertKind(String factId, Map<String, String> values, String expectedKind,
                                   String expectedDetail) {
        AXPresenceEventMapper.Batch batch = batch(
                new PresenceWorldEventPayload(null, factId, "player", "minecraft:overworld", 1L, values));

        assertEquals(expectedKind, batch.kind(), "no rule for " + factId);
        assertEquals(expectedDetail, detailOf(batch.parts().get(0)));
        assertFalse(batch.parts().isEmpty());
    }

    private static AXPresenceEventMapper.Batch batch(PresenceWorldEventPayload payload) {
        return AXPresenceEventMapper.batch(payload).orElseThrow();
    }

    private static PresenceWorldEventPayload chat(String senderId, String senderName, String text) {
        return new PresenceWorldEventPayload(null, PresenceWorldEventPayload.EVENT_CHAT_MESSAGE,
                senderId, "", 1L, Map.of(
                        PresenceWorldEventPayload.VALUE_SENDER_ID, senderId,
                        PresenceWorldEventPayload.VALUE_SENDER_NAME, senderName,
                        PresenceWorldEventPayload.VALUE_TEXT, text));
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

    /** A batch part is the "count + name" template: argument 0 is the count, argument 1 the detail. */
    private static String countOf(AXEventText part) {
        return ((AXEventText.Arg.Value) part.args().get(0)).text();
    }

    /** A counted part is (count, name); a once-only part is just (name). */
    private static String detailOf(AXEventText part) {
        return part.args().size() == 1
                ? ((AXEventText.Arg.Value) part.args().get(0)).text()
                : ((AXEventText.Arg.Value) part.args().get(1)).text();
    }
}
