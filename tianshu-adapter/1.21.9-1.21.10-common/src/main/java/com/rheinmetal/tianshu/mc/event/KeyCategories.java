package com.rheinmetal.tianshu.mc.event;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The controls-screen categories this platform's keys belong to.
 *
 * <p>Up to 1.21.8 a mapping named its category with a translation key, so a module could declare any string and the
 * game translated it. 1.21.9 names a category with a resource id and derives the label from that id
 * ({@code key.category.<namespace>.<path>}), so the id has to be chosen here — once — and the label has to exist under
 * the derived key. The choice this class makes is the narrowest one that keeps a module's declaration meaningful: the
 * last segment of the declared translation key becomes the id's namespace, under a {@code keys} path, so
 * {@code key.categories.tianshu} becomes {@code tianshu:keys} and is labelled by {@code key.category.tianshu.keys}.</p>
 *
 * <p>A category has to be registered exactly once per client: the game rejects a second registration of the same id,
 * and every key that names one category has to share a single instance or the controls screen lists it twice. The
 * cache below provides both, which is also why this lives beside {@link DeclaredKeyBindings} instead of inside it —
 * the host's own keys and a module's declared keys both register through here.</p>
 */
final class KeyCategories {

    /** Where a declaration that names no usable category still groups: the platform's own category. */
    private static final ResourceLocation FALLBACK = ResourceLocation.fromNamespaceAndPath("tianshu", "keys");

    private static final Map<ResourceLocation, KeyMapping.Category> REGISTERED = new HashMap<>();

    private KeyCategories() {
    }

    /** The category for one declared translation key, registered the first time anything asks for it. */
    static synchronized KeyMapping.Category of(String categoryLangKey) {
        ResourceLocation id = idOf(categoryLangKey);
        KeyMapping.Category cached = REGISTERED.get(id);
        if (cached != null) {
            return cached;
        }
        KeyMapping.Category category;
        try {
            category = KeyMapping.Category.register(id);
        } catch (IllegalArgumentException alreadyRegistered) {
            // Another owner of the same id (a second platform instance in one client, or another mod): the
            // constructor still yields a usable category, only the controls-screen ordering hint is lost.
            category = new KeyMapping.Category(id);
        }
        REGISTERED.put(id, category);
        return category;
    }

    /**
     * The id one declared key names. A declaration without a usable final segment falls back to the platform's own
     * category rather than failing: a malformed declaration must not cost the player the controls screen.
     */
    private static ResourceLocation idOf(String categoryLangKey) {
        String key = categoryLangKey == null ? "" : categoryLangKey.trim();
        int separator = key.lastIndexOf('.');
        if (separator < 0 || separator == key.length() - 1) {
            return FALLBACK;
        }
        String namespace = key.substring(separator + 1).toLowerCase(Locale.ROOT);
        try {
            return ResourceLocation.fromNamespaceAndPath(namespace, "keys");
        } catch (RuntimeException invalidName) {
            return FALLBACK;
        }
    }
}
