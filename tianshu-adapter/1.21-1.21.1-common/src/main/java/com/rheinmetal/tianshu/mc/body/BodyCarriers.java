package com.rheinmetal.tianshu.mc.body;

import com.rheinmetal.tianshu.api.entity.BodyFailure;
import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.entity.EntityTypeSpec;
import net.minecraft.resources.ResourceLocation;
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

    /** Resolves only carrier types declared by this module. */
    public static EntityType<WalkerBody> declared(String moduleId, String id) {
        var key = ResourceLocation.tryParse(id);
        if (key != null && key.getNamespace().equals(moduleId)) {
            var type = types.get(key.toString());
            if (type != null) {
                return type;
            }
        }
        throw BodyFailure.KIND_INVALID.exception();
    }

    static EntityType<WalkerBody> walker() {
        return declared(WALKER.modId(), WALKER.id());
    }
}
