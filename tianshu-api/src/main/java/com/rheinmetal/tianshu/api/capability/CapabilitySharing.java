package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Who a capability is shared with — declared <em>when it is registered</em>, not in a separate manifest.
 *
 * <p>Sharing is a property of the capability, not of the module: a module may publish its mining workflow and keep its
 * internal state reading to itself. That is why this rides on the row (in {@code attributes.share}) instead of being a
 * module-wide switch.</p>
 *
 * <p>Three scopes, declared as {@code share} in the row's attributes: {@code "all"} (everyone), {@code "none"}
 * (private) and a list of module ids ({@code "modA"}, {@code "modA, modB"}) for exactly those modules.</p>
 *
 * <p>The default follows the provider. The base's own rows and the player's own folder carry no declaration and are open
 * to everyone — they are the content this session is built out of. A module's rows are private unless it says
 * otherwise: an undeclared share must fail closed, or installing two mods would silently open both to each other.</p>
 *
 * <p>This is only half the gate. A consumer also has to declare that it accepts the provider — see
 * {@link CapabilitySubscriptions}. Both sides must agree; one side's declaration alone is a grant nobody asked for.</p>
 */
public record CapabilitySharing(Scope scope, Set<String> modules) {

    /** Who may see the row. */
    public enum Scope {
        /** Everyone. */
        ALL,
        /** Nobody outside the provider itself. */
        NONE,
        /** Only the modules named in {@link #modules()}. */
        ONLY
    }

    public static final CapabilitySharing ALL = new CapabilitySharing(Scope.ALL, Set.of());
    public static final CapabilitySharing NONE = new CapabilitySharing(Scope.NONE, Set.of());

    /** The provider of every base capability; its rows are open by default. */
    public static final String BASE_PROVIDER = "tianshu";

    /** The provider of the player's own skill, resource and prompt folders; also open by default. */
    public static final String USER_PROVIDER = "user";

    public CapabilitySharing {
        modules = modules == null ? Set.of() : Set.copyOf(modules);
        if (scope == null) {
            throw new IllegalArgumentException("a sharing scope is required");
        }
    }

    /**
     * Whether a provider's rows are open when it declares nothing: the base and the human's own folder are, a module is
     * not.
     *
     * <p>The human's folder counts because it is the human's own: the assistant acts for that same person, so making the
     * default private would hide the player's files from the session they were written for. A file that should stay
     * away from other modules says so — {@code metadata.share: none} — and that is a declaration, not a default.</p>
     */
    public static boolean opens(String provider) {
        return BASE_PROVIDER.equals(provider) || USER_PROVIDER.equals(provider);
    }

    public static CapabilitySharing only(String... modules) {
        return new CapabilitySharing(Scope.ONLY, new LinkedHashSet<>(java.util.List.of(modules)));
    }

    /** The same default for a row that is not constructed yet: open for the base and the human, private for a module. */
    public static CapabilitySharing ofProvider(String provider, JsonElement declared) {
        if (declared == null || declared.isJsonNull()) {
            return opens(provider) ? ALL : NONE;
        }
        return ofAttributes(declared);
    }

    /** The declaration on this row; undeclared, it follows its provider. */
    public static CapabilitySharing of(CapabilityDescriptor descriptor) {
        return ofProvider(descriptor.provider(), descriptor.attributes().get("share"));
    }

    private static CapabilitySharing ofAttributes(JsonElement declared) {
        if (declared.isJsonPrimitive() && declared.getAsJsonPrimitive().isString()) {
            String value = declared.getAsString().strip();
            // Only the keywords are case-insensitive: a module id is the module's own spelling, and lowercasing it
            // here would make "share: MyMod" silently match nothing.
            switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "all", "everyone", "*":
                    return ALL;
                case "none", "private":
                    return NONE;
                default:
                    return fromNames(java.util.List.of(value.split(",")));
            }
        }
        if (declared.isJsonArray()) {
            Set<String> named = new LinkedHashSet<>();
            for (JsonElement element : declared.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    named.add(element.getAsString().strip());
                }
            }
            named.remove("");
            return named.isEmpty() ? NONE : new CapabilitySharing(Scope.ONLY, named);
        }
        // Unreadable declaration: private. A typo must not widen who can see it.
        return NONE;
    }

    /**
     * A comma-separated declaration is a list: {@code share: modA, modB} names two modules, not one module called
     * "moda, modb". The single-name case needs no special branch — one entry is a list of one.
     */
    private static CapabilitySharing fromNames(java.util.List<String> names) {
        Set<String> named = new LinkedHashSet<>();
        for (String name : names) {
            String trimmed = name.strip();
            if (!trimmed.isEmpty()) {
                named.add(trimmed);
            }
        }
        return named.isEmpty() ? NONE : new CapabilitySharing(Scope.ONLY, named);
    }

    /** Whether {@code consumerModule} may see a row carrying this declaration. */
    public boolean permits(String consumerModule) {
        return switch (scope) {
            case ALL -> true;
            case NONE -> false;
            case ONLY -> consumerModule != null && modules.contains(consumerModule);
        };
    }

    /** The same declaration as an attribute value, for a provider that wants to publish one. */
    public JsonElement toAttribute() {
        return switch (scope) {
            case ALL -> new com.google.gson.JsonPrimitive("all");
            case NONE -> new com.google.gson.JsonPrimitive("none");
            case ONLY -> {
                JsonArray array = new JsonArray();
                modules.forEach(array::add);
                yield array;
            }
        };
    }

    /** Convenience for providers building their row. */
    public JsonObject into(JsonObject attributes) {
        attributes.add("share", toAttribute());
        return attributes;
    }
}