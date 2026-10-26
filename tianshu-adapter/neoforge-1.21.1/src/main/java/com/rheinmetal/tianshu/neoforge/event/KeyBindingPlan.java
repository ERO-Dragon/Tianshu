package com.rheinmetal.tianshu.neoforge.event;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which declared key owns its default key code.
 *
 * <p>Deliberately free of Minecraft types: the rule is about ownership, not about how a mapping is built, so
 * it can be decided and tested without a client. The platform layer turns the decision into a
 * {@code KeyMapping}.</p>
 */
final class KeyBindingPlan {

    /** A mapping whose default was taken by someone else is registered unbound, never stolen silently. */
    static final int UNBOUND = -1;

    record Declaration(String keyId, int defaultKeyCode, String categoryLangKey) {
    }

    record Entry(String keyId, int keyCode, String categoryLangKey, boolean unbound) {
    }

    private KeyBindingPlan() {
    }

    static List<Entry> resolve(List<Declaration> declarations) {
        List<Entry> entries = new ArrayList<>();
        if (declarations == null || declarations.isEmpty()) {
            return entries;
        }
        Set<Integer> claimed = new LinkedHashSet<>();
        for (Declaration declaration : declarations) {
            if (declaration == null || declaration.keyId() == null || declaration.keyId().isBlank()) {
                continue;
            }
            boolean taken = !claimed.add(declaration.defaultKeyCode());
            entries.add(new Entry(
                    declaration.keyId(),
                    taken ? UNBOUND : declaration.defaultKeyCode(),
                    declaration.categoryLangKey(),
                    taken));
        }
        return entries;
    }
}
