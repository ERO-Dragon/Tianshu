package com.rheinmetal.tianshu.protocol.payload;

/**
 * The vocabulary of world events: what happened, and the field names the values use.
 *
 * <p>It lives in the api because the platform produces these events and modules consume them — the strings are the
 * contract between the two sides, independent of which payload carries them. The presence module's aggregated
 * world-event payload re-exports them as aliases so its existing readers keep compiling; new code should reference
 * this class directly.</p>
 */
public final class WorldEventTypes {

    public static final String EVENT_ADVANCEMENT_UNLOCKED = "advancement_unlocked";
    public static final String EVENT_PLAYER_DEATH = "player_death";
    /** A craft the server watched happen; only the optional server component can report it. */
    public static final String EVENT_ITEM_CRAFTED = "item_crafted";
    /** Facts only a server can observe; the client never sees these on its own. */
    public static final String EVENT_MOB_KILLED = "mob_killed";
    public static final String EVENT_FISHING_CATCH = "fishing_catch";
    public static final String EVENT_TAMED = "tamed";
    public static final String EVENT_BLOCK_BROKEN = "block_broken";
    public static final String EVENT_BLOCK_PLACED = "block_placed";
    public static final String EVENT_CONTAINER_OPEN = "container_open";
    public static final String EVENT_MOUNT = "mount";
    public static final String EVENT_DISMOUNT = "dismount";
    public static final String EVENT_DIMENSION_CHANGED = "dimension_changed";
    public static final String EVENT_EFFECT_GAINED = "effect_gained";
    public static final String EVENT_EFFECT_EXPIRED = "effect_expired";
    public static final String EVENT_RESPAWN = "respawn";
    public static final String EVENT_DAMAGE_TAKEN = "damage_taken";
    public static final String EVENT_EXP_LEVEL = "exp_level";
    public static final String EVENT_FRIEND_JOIN = "friend_join";
    public static final String EVENT_FRIEND_LEAVE = "friend_leave";
    public static final String EVENT_ITEM_PICKUP = "item_pickup";
    public static final String EVENT_TRADE = "trade";
    /** A boss bar appearing on the client's screen; the client is the only place that sees it. */
    public static final String EVENT_BOSS_ENGAGE = "boss_engage";
    /** A chat line is a world event with a speaker, not a second payload kind. */
    public static final String EVENT_CHAT_MESSAGE = "chat_message";

    /** The effect name a fact refers to, resolved by the server. */
    public static final String VALUE_EFFECT_NAME = "effectName";
    public static final String VALUE_AMOUNT = "amount";
    public static final String VALUE_LEVEL = "level";
    public static final String VALUE_PLAYER_NAME = "playerName";
    public static final String VALUE_SENDER_ID = "senderId";
    public static final String VALUE_SENDER_NAME = "senderName";
    public static final String VALUE_TEXT = "text";
    /** Progress facts carry their own id and their localized title; a consumer words the line from these. */
    public static final String VALUE_ADVANCEMENT_ID = "advancementId";
    public static final String VALUE_TITLE = "title";
    public static final String VALUE_DESCRIPTION = "description";
    /** An advancement's kind (task / goal / challenge), which consumers use to tag and group it. */
    public static final String VALUE_TYPE = "type";
    /** The item an advancement shows as its icon. */
    public static final String VALUE_ICON_ITEM_ID = "iconItemId";
    /** Craft facts: the registry id, the name the server resolved, and how many were made. */
    public static final String VALUE_ITEM_ID = "itemId";
    public static final String VALUE_ITEM_NAME = "itemName";
    public static final String VALUE_COUNT = "count";
    /** Combat and entity facts: the registry id and the name the server resolved. */
    public static final String VALUE_ENTITY_ID = "entityId";
    public static final String VALUE_ENTITY_NAME = "entityName";
    /** The vanilla death message, already localized by the server, and the dimension a fact happened in. */
    public static final String VALUE_DEATH_MESSAGE = "deathMessage";
    public static final String VALUE_DIMENSION = "dimension";
    /** The dimension a player moved into, resolved to its display name. */
    public static final String VALUE_DIMENSION_NAME = "dimensionName";
    /** Block facts: the registry id and the name the server resolved. */
    public static final String VALUE_BLOCK_ID = "blockId";
    public static final String VALUE_BLOCK_NAME = "blockName";

    private WorldEventTypes() {
    }
}