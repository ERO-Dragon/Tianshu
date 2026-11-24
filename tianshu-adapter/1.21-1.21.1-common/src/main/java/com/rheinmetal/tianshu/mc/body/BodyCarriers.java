package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.rheinmetal.tianshu.api.entity.EntityTypeSpec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BodyCarriers {
    private static final EntityTypeSpec WALKER = new EntityTypeSpec(ModIdentity.MOD_ID, "walker", 0.6F, 1.8F, false);
    private static final Map<String, EntityType<WalkerBody>> types = new LinkedHashMap<>();

    private BodyCarriers() {
    }

    /** A standalone Ferry must leave the networked entity registry unchanged. */
    public static List<EntityTypeSpec> registrationTypes(List<EntityTypeSpec> declarations) {
        if (declarations.isEmpty()) {
            return List.of();
        }
        var specs = new ArrayList<EntityTypeSpec>();
        specs.add(WALKER);
        for (var spec : declarations) {
            if (!spec.id().equals(WALKER.id())) {
                specs.add(spec);
            }
        }
        return List.copyOf(specs);
    }

    public static EntityType<WalkerBody> createType(EntityTypeSpec spec) {
        var builder = EntityType.Builder.of(WalkerBody::new, MobCategory.MISC)
                .sized(spec.width(), spec.height()).clientTrackingRange(10);
        if (spec.fireImmune()) {
            builder.fireImmune();
        }
        return builder.build(spec.id());
    }

    public static void track(EntityTypeSpec spec, EntityType<WalkerBody> type) {
        types.put(spec.id(), type);
    }

    public static List<EntityType<WalkerBody>> types() {
        return List.copyOf(types.values());
    }

    public static boolean available(BodyKind kind) {
        return kind != BodyKind.WALKER || types.containsKey(WALKER.id());
    }

    /**
     * What a spawn request resolved to: the shape the registry records, plus the type to instantiate.
     *
     * <p>{@code type} is non-null exactly when {@code kind} is {@link BodyKind#WALKER} — the other two shapes are
     * vanilla entities the game already has. Whoever resolves a request once does not have to ask again downstream
     * whether a type was named.</p>
     */
    public record Carrier(BodyKind kind, EntityType<WalkerBody> type) {
    }

    /**
     * Resolves a spawn request against the entity types registered <em>in this process</em>.
     *
     * <p>Two kinds of name are accepted, and the difference is the guard:</p>
     * <ul>
     *   <li>a built-in shape token ({@code marker}, {@code stand}, {@code walker}) — the adapter's own carriers, made
     *       of vanilla types or of the type the adapter registers for itself, so any module may ask for one;</li>
     *   <li>a namespaced type id ({@code <module>:<name>}) — and only the module that declared it may spawn it.</li>
     * </ul>
     *
     * <p>The registry is the judge, not the caller's word. A type that exists only on the client has no entry here,
     * so such a request never reaches the world however sure the client is that its type exists.</p>
     *
     * @throws com.rheinmetal.tianshu.api.tool.ToolException {@link BodyFailure#KIND_INVALID} when nothing on this
     *         server can produce the name, {@link BodyFailure#KIND_FOREIGN} when the type exists but belongs to
     *         another module
     */
    public static Carrier resolve(String moduleId, String kind) {
        BodyKind builtin = CarrierName.builtin(kind).orElse(null);
        if (builtin != null) {
            return new Carrier(builtin, builtin == BodyKind.WALKER ? walker() : null);
        }
        CarrierName.requireWellFormed(kind);
        EntityType<WalkerBody> type = types.get(kind);
        if (type == null) {
            throw BodyFailure.KIND_INVALID.exception();
        }
        CarrierName.requireOwned(moduleId, kind);
        return new Carrier(BodyKind.WALKER, type);
    }

    /** The adapter's own walker, absent on a standalone Ferry that registered no entity types at all. */
    static EntityType<WalkerBody> walker() {
        EntityType<WalkerBody> type = types.get(WALKER.id());
        if (type == null) {
            throw BodyFailure.KIND_INVALID.exception();
        }
        return type;
    }
}