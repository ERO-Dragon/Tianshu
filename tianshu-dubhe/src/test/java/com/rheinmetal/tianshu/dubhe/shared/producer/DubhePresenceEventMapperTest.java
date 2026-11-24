package com.rheinmetal.tianshu.dubhe.shared.producer;

import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventText;
import com.rheinmetal.tianshu.presence.payload.PresenceWorldEventPayload;
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
class DubhePresenceEventMapperTest {

    @Test
    void anAdvancementBecomesTheEventLayersLine() {
        DubhePresenceEventMapper.Batch batch = batch(advancement("成就：探险时间"));

        assertEquals("tianshu:advancement", batch.kind());
        assertEquals("tianshu.dubhe.shared.event.line.tianshu.advancement", batch.lineKey());
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

        DubhePresenceEventMapper.Batch batch = batch(payload);

        assertTrue(batch.parts().isEmpty(), "no detail means the kind words itself as a whole sentence");
        assertEquals(1, batch.totalCount(), "the occurrence still counts");
        assertEquals("tianshu.dubhe.shared.event.line.tianshu.advancement", batch.lineKey());
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
        DubhePresenceEventMapper.Batch named = batch(craft(Map.of(
                PresenceWorldEventPayload.VALUE_ITEM_NAME, "橡木木板",
                PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:oak_planks")));
        assertEquals("tianshu:item_crafted", named.kind());
        assertEquals("tianshu.dubhe.shared.event.line.tianshu.item_crafted", named.lineKey());
        assertEquals("橡木木板", detailOf(named.parts().get(0)));

        DubhePresenceEventMapper.Batch idOnly = batch(craft(Map.of(
                PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:oak_planks")));
        assertEquals("minecraft:oak_planks", detailOf(idOnly.parts().get(0)),
                "without a name the id still words the line");
    }

    /** Chat is something said, and an unknown type is a fact of its own: both stay out of the event layer. */
    @Test
    void chatAndUnknownTypesStayOutOfTheEventLayer() {
        assertTrue(DubhePresenceEventMapper.batch(chat("uuid-1", "甲", "在吗")).isEmpty());
        assertTrue(DubhePresenceEventMapper.batch(
                new PresenceWorldEventPayload(null, "mymod:custom_fact", "player", "minecraft:overworld", 1L,
                        Map.of())).isEmpty());
        assertTrue(DubhePresenceEventMapper.batch(null).isEmpty());
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

        DubhePresenceEventMapper.Batch batch = batch(payload);

        assertEquals("tianshu:pickup", batch.kind());
        assertEquals("tianshu.dubhe.shared.event.line.tianshu.pickup", batch.lineKey());
        assertEquals(2, batch.parts().size(), "two groups must not collapse into one");
        assertEquals(80, batch.totalCount(), "the frequency step needs the real number of occurrences");
        assertEquals("20", countOf(batch.parts().get(0)));
        assertEquals("泥土", detailOf(batch.parts().get(0)));
        assertEquals("60", countOf(batch.parts().get(1)));
    }

    private static void assertKind(String factId, Map<String, String> values, String expectedKind,
                                   String expectedDetail) {
        DubhePresenceEventMapper.Batch batch = batch(
                new PresenceWorldEventPayload(null, factId, "player", "minecraft:overworld", 1L, values));

        assertEquals(expectedKind, batch.kind(), "no rule for " + factId);
        assertEquals(expectedDetail, detailOf(batch.parts().get(0)));
        assertFalse(batch.parts().isEmpty());
    }


    @Test
    void fallingIsItsOwnLineRatherThanAnotherHit() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(null,
                PresenceWorldEventPayload.EVENT_FALL_DAMAGE,
                "player", "minecraft:overworld", 1L,
                Map.of(PresenceWorldEventPayload.VALUE_AMOUNT, "7"));

        DubhePresenceEventMapper.Batch batch = batch(payload);

        assertEquals("tianshu:fall_damage", batch.kind(),
                "the tier table and the wording both exist for this kind; only the producer was missing");
        assertEquals("tianshu.dubhe.shared.event.line.tianshu.fall_damage", batch.lineKey());
        assertEquals("7", detailOf(batch.parts().get(0)));
    }

    @Test
    void aRareFindIsNotFoldedIntoOrdinaryPickups() {
        PresenceWorldEventPayload payload = new PresenceWorldEventPayload(null,
                PresenceWorldEventPayload.EVENT_RARE_ITEM,
                "player", "minecraft:overworld", 1L,
                Map.of(PresenceWorldEventPayload.VALUE_ITEM_NAME, "钻石",
                        PresenceWorldEventPayload.VALUE_ITEM_ID, "minecraft:diamond"));

        DubhePresenceEventMapper.Batch batch = batch(payload);

        assertEquals("tianshu:rare_item", batch.kind());
        assertEquals("tianshu.dubhe.shared.event.line.tianshu.rare_item", batch.lineKey());
        assertEquals("钻石", detailOf(batch.parts().get(0)));
    }

    private static DubhePresenceEventMapper.Batch batch(PresenceWorldEventPayload payload) {
        return DubhePresenceEventMapper.batch(payload).orElseThrow();
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
    private static String countOf(DubheEventText part) {
        return ((DubheEventText.Arg.Value) part.args().get(0)).text();
    }

    /** A counted part is (count, name); a once-only part is just (name). */
    private static String detailOf(DubheEventText part) {
        return detailArg(part.args().size() == 1 ? part.args().get(0) : part.args().get(1));
    }

    /** The value of a detail argument, whichever shape it carries: literal, or a key with its token as fallback. */
    private static String detailArg(DubheEventText.Arg arg) {
        if (arg instanceof DubheEventText.Arg.Value value) {
            return value.text();
        }
        if (arg instanceof DubheEventText.Arg.Key key) {
            return key.fallback();
        }
        return "";
    }

    private static PresenceWorldEventPayload weather(String state) {
        return new PresenceWorldEventPayload(null, PresenceWorldEventPayload.EVENT_WEATHER_CHANGE,
                "player", "minecraft:overworld", 1L,
                Map.of(PresenceWorldEventPayload.VALUE_TYPE, state));
    }

    private static PresenceWorldEventPayload respawn(String dimensionId) {
        return new PresenceWorldEventPayload(null, PresenceWorldEventPayload.EVENT_RESPAWN,
                "player", dimensionId, 1L,
                Map.of(PresenceWorldEventPayload.VALUE_DIMENSION, dimensionId));
    }

    /**
     * Weather is protocol vocabulary, not language: the mapper has to name the value's key so the sentence can
     * say "下雨" instead of "rain". The token still travels as the fallback, for a build with no entry yet.
     */
    @Test
    void aWeatherStateBecomesALanguageReference() {
        DubhePresenceEventMapper.Batch batch = batch(weather("rain"));

        assertEquals("tianshu:weather_change", batch.kind());
        DubheEventText.Arg detail = batch.parts().get(0).args().get(0);
        DubheEventText.Arg.Key key = (DubheEventText.Arg.Key) detail;
        assertEquals("tianshu.dubhe.shared.event.value.weather.rain", key.key());
        assertEquals("rain", key.fallback());
    }

    @Test
    void aRespawnDimensionBecomesALanguageReference() {
        DubhePresenceEventMapper.Batch batch = batch(respawn("minecraft:the_nether"));

        DubheEventText.Arg.Key key = (DubheEventText.Arg.Key) batch.parts().get(0).args().get(0);
        assertEquals("tianshu.dubhe.shared.event.value.dimension.minecraft.the_nether", key.key());
        assertEquals("minecraft:the_nether", key.fallback());
    }

    /** A dimension the client already resolved to a name has nothing to look up. */
    @Test
    void anAlreadyLocalizedNameStaysALiteral() {
        DubhePresenceEventMapper.Batch batch = batch(new PresenceWorldEventPayload(null,
                PresenceWorldEventPayload.EVENT_DIMENSION_CHANGED, "player", "minecraft:overworld", 1L,
                Map.of(PresenceWorldEventPayload.VALUE_DIMENSION_NAME, "下界")));

        assertTrue(batch.parts().get(0).args().get(0) instanceof DubheEventText.Arg.Value,
                "a display name is already language; wrapping it in a key would guarantee a miss");
        assertEquals("下界", detailOf(batch.parts().get(0)));
    }
}
