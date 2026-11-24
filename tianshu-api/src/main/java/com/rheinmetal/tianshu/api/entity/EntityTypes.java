package com.rheinmetal.tianshu.api.entity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Declarations of the entity types modules want registered, collected until the adapter's registration pass.
 *
 * <p>Two phases, because the game has two: a module declares while it is assembling (any time before the loader's
 * registry event), and the adapter drains the declarations <em>during</em> that event, where it holds the loader's
 * registry. The platform keeps the ordering, so neither side has to reach into the other.</p>
 *
 * <p>A declaration made after {@link #closeDeclarations()} is refused rather than accepted and dropped: the entity type
 * would never exist, and the failure would surface much later as "the body did not spawn".</p>
 */
public final class EntityTypes {

    private static final Object LOCK = new Object();
    private static final Map<String, EntityTypeSpec> DECLARED = new LinkedHashMap<>();
    private static boolean closed;
    private static List<EntityTypeSpec> drained = List.of();

    private EntityTypes() {
    }

    /** Declares one type. Refused once the registration pass has taken the declarations. */
    public static EntityTypeSpec declare(EntityTypeSpec spec) {
        Objects.requireNonNull(spec, "spec");
        synchronized (LOCK) {
            if (closed) {
                throw new IllegalStateException("entity type " + spec.id()
                        + " was declared after the loader registration pass");
            }
            EntityTypeSpec existing = DECLARED.get(spec.id());
            if (existing != null) {
                if (existing.equals(spec)) {
                    return existing;
                }
                throw new IllegalArgumentException("entity type " + spec.id()
                        + " is already declared with a different shape");
            }
            DECLARED.put(spec.id(), spec);
            return spec;
        }
    }

    /** The declarations so far; a view, so a caller cannot register something behind the platform's back. */
    public static List<EntityTypeSpec> declared() {
        synchronized (LOCK) {
            return List.copyOf(DECLARED.values());
        }
    }

    /** Takes every declaration once, for the adapter's registration pass, and closes further declarations. */
    public static List<EntityTypeSpec> drainForRegistration() {
        synchronized (LOCK) {
            closed = true;
            drained = List.copyOf(DECLARED.values());
            DECLARED.clear();
            return drained;
        }
    }

    /**
     * Whether {@code moduleId} may spawn the declared type {@code typeId}.
     *
     * <p>The namespace <em>is</em> the ownership: {@link EntityTypeSpec} names a type after the module that declared
     * it, so the same string that identifies the registry entry also says who may spawn it. There is no second table
     * to keep in step, and no way to claim someone else's type by asking nicely.</p>
     *
     * <p>This rule covers declared types only. The adapter's built-in carriers are its own shapes, not declarations,
     * and every module may ask for one by name.</p>
     */
    public static boolean ownedBy(String moduleId, String typeId) {
        if (moduleId == null || typeId == null) {
            return false;
        }
        String owner = moduleId.trim().toLowerCase(Locale.ROOT);
        return !owner.isEmpty() && typeId.startsWith(owner + ":");
    }

    /** Whether the registration pass has already taken the declarations. */
    public static boolean closed() {
        synchronized (LOCK) {
            return closed;
        }
    }

    /** Tests only: returns the registry to its pre-registration state. */
    public static void resetForTests() {
        synchronized (LOCK) {
            DECLARED.clear();
            closed = false;
            drained = List.of();
        }
    }

    /** Tests only: what the registration pass actually took, so an end-to-end test can assert the scan read a jar. */
    public static List<EntityTypeSpec> drainedForTests() {
        synchronized (LOCK) {
            return drained;
        }
    }
}