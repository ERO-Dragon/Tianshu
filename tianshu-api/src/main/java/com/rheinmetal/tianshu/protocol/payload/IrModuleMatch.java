package com.rheinmetal.tianshu.protocol.payload;

/**
 * One voice-trigger hit: which module was called, with which words.
 *
 * <p>This replaces the old aggregated {@code moduleId -> [wake words] + [extra words]} shape. The aggregation lost the
 * pairing that actually matters — which spoken word hit which registered word — so a consumer could not tell whether a
 * module was called by its name or by a variant, and every consumer had to re-derive the pairing.</p>
 *
 * <ul>
 *   <li>{@code spokenWord} — the words the player said.</li>
 *   <li>{@code registeredWord} — the word that module registered (its name or one of its triggers).</li>
 * </ul>
 */
public record IrModuleMatch(String moduleId, String spokenWord, String registeredWord) {
    public IrModuleMatch {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId cannot be blank");
        }
        moduleId = moduleId.trim();
        spokenWord = clean(spokenWord);
        registeredWord = clean(registeredWord);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
