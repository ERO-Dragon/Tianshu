package com.rheinmetal.tianshu.function.auxilium.input;

/**
 * One named object the intake side matched in what the player said, in this module's own vocabulary.
 *
 * <p>The four fields answer four different questions: {@code spokenWord} is what the player actually said
 * (the form a prompt should show), {@code registryId} is the exact identity, {@code registryKind} is the
 * category the word list came from, and {@code languageKey} is the translation key that id maps to.</p>
 *
 * <p>This module never learns which types carry these values on the producing side; the translation lives in
 * the bridge.</p>
 */
public record AXMatchedObject(String spokenWord, String registryId, String registryKind, String languageKey) {

    public AXMatchedObject {
        spokenWord = clean(spokenWord);
        registryId = clean(registryId);
        registryKind = clean(registryKind);
        languageKey = clean(languageKey);
    }

    /** An entry without the words actually said names nothing; the producing side drops those too. */
    public boolean isMeaningful() {
        return !spokenWord.isBlank() && !registryId.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
