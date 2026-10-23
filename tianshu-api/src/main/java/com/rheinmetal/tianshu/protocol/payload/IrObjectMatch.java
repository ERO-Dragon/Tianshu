package com.rheinmetal.tianshu.protocol.payload;

/**
 * One named object the repair step matched in what the player said.
 *
 * <p>The three fields answer three different questions and none of them can replace another:</p>
 *
 * <ul>
 *   <li>{@code spokenWord} — the words the player actually used, as they appear in the utterance. This is what a
 *       prompt should show: the player said "钻石锄", so the model reads 钻石锄.</li>
 *   <li>{@code registryId} — the registry id the word resolved to ({@code minecraft:diamond_hoe}), for modules that
 *       need an exact identity rather than a name.</li>
 *   <li>{@code languageKey} — the translation key that id maps to ({@code item.minecraft.diamond_hoe}), derived from the
 *       id and the category the matching word list came from. Empty when the category was not known, because a guessed
 *       key is worse than none.</li>
 * </ul>
 *
 * <p>An empty {@code spokenWord} or {@code registryId} makes the entry meaningless and is dropped at construction.</p>
 */
public record IrObjectMatch(String spokenWord, String registryId, String registryKind, String languageKey) {
    /**
     * Registry categories this record can name.
     *
     * <p>The set is <b>open</b>: anything the game registers under a translation category is a candidate —
     * items, creature types, blocks, dimensions, biomes, effects, enchantments and whatever a mod registers.
     * The constants below only cover the categories that exist today, so a consumer must fall back to reading
     * {@link #registryKind()} itself instead of assuming one of these three.</p>
     */
    public static final String KIND_ITEM = "item";

    public static final String KIND_ENTITY = "entity";

    public static final String KIND_BLOCK = "block";

    public static final String KIND_DIMENSION = "dimension";

    public static final String KIND_BIOME = "biome";

    public static final String KIND_EFFECT = "effect";

    /** Prefix of an entity translation key. */
    public static final String ENTITY_KEY_PREFIX = "entity.";

    /** Prefix of an item translation key. */
    public static final String ITEM_KEY_PREFIX = "item.";

    /** Prefix of a block translation key; a block item keeps its own key even though the match came from an item list. */
    public static final String BLOCK_KEY_PREFIX = "block.";

    public IrObjectMatch {
        spokenWord = clean(spokenWord);
        registryId = clean(registryId);
        registryKind = clean(registryKind);
        languageKey = clean(languageKey);
        if (spokenWord.isEmpty() || registryId.isEmpty()) {
            throw new IllegalArgumentException("an object match needs both the spoken words and the registry id");
        }
        if (registryKind.isEmpty() && languageKey.contains(".")) {
            registryKind = languageKey.substring(0, languageKey.indexOf('.'));
        }
    }

    /**
     * The match names an entity type rather than an item.
     *
     * <p>Decided by the registry kind, not by parsing the language key: a match whose category is unknown still
     * has to answer this question correctly, and a missing key must not turn every creature into an item.</p>
     */
    public boolean entity() {
        return KIND_ENTITY.equals(registryKind);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
