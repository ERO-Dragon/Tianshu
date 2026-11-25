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
 * <p>The default follows the provider. The base's own rows carry no declaration and are open to everyone — the base is
 * what everything else is built on. A module's rows are private unless it says otherwise: an undeclared share must fail
 * closed, or installing two mods would silently open both to each other.</p>
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





    public CapabilitySharing {
        modules = modules == null ? Set.of() : Set.copyOf(modules);
        if (scope == null) {
            throw new IllegalArgumentException("a sharing scope is required");
        }
    }

    /** Whether rows from this provider are open without a declaration: the base and the human's own files are. */
    /** Whether a provider's rows are open when it declares nothing: only the kernel's are. */
    public static boolean opens(String provider) {
        return BASE_PROVIDER.equals(provider);
    }

    public static CapabilitySharing only(String... modules) {
        return new CapabilitySharing(Scope.ONLY, new LinkedHashSet<>(java.util.List.of(modules)));
    }

    /** The same default, for a row that is not constructed yet: the base opens, a module stays private. */
    public static CapabilitySharing ofProvider(String provider, JsonElement declared) {
        if (declared == null || declared.isJsonNull()) {
            return opens(provider) ? ALL : NONE;
        }
        return ofAttributes(declared);
    }

    /** The declaration on this row; the base is open, a module is private until it says otherwise. */
    public static CapabilitySharing of(CapabilityDescriptor descriptor) {
        return ofProvider(descriptor.provider(), descriptor.attributes().get("share"));
    }

    private static CapabilitySharing ofAttributes(JsonElement declared) {
        if (declared.isJsonPrimitive() && declared.getAsJsonPrimitive().isString()) {
            String value = declared.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            return switch (value) {
                case "all", "everyone", "*" -> ALL;
                case "none", "private" -> NONE;
                // A single module name is a one-entry list, not a typo: writing {"share":"modA"} should mean what it says.
                default -> only(value);
            };
        }
        if (declared.isJsonArray()) {
            Set<String> named = new LinkedHashSet<>();
            for (JsonElement element : declared.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    named.add(element.getAsString().trim());
                }
            }
            return named.isEmpty() ? NONE : new CapabilitySharing(Scope.ONLY, named);
        }
        // Unreadable declaration: private. A typo must not widen who can see it.
        return NONE;
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