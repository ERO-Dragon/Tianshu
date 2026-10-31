package com.rheinmetal.tianshu.mc.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A feature's program descriptor, converted into the facts the platform needs to draw it.
 *
 * <p>A feature declares a program by the name its files have inside {@code shaders/core}: the descriptor
 * {@code shaders/core/<name>.json} names the two shader sources and the uniforms the program exposes. Up to 1.21.4
 * the game read that descriptor itself; 1.21.5 removed data-driven programs, so the platform reads the same asset and
 * draws from it. The asset therefore stays the feature's own declaration — the platform learns no shader name,
 * uniform name or uniform type from anything but this file.</p>
 *
 * <p>Only what a draw needs is read: the two shader sources and the uniform names with their shapes. A shader source
 * may be named with its folder ({@code core/gui}, as the game's own descriptors do) or without it, in which case it is
 * resolved inside the descriptor's own folder. The {@code count} field of a uniform entry is not read: a uniform's
 * width follows from its shape. A descriptor this class cannot read yields {@code null}, and the caller treats that
 * exactly like a program the loaded packs do not provide.</p>
 */
final class HudProgramDescriptor {

    /** Descriptors and the shader sources they name live in this folder below {@code shaders/}. */
    private static final String SHADER_FOLDER = "core";

    private final ResourceLocation vertexShader;
    private final ResourceLocation fragmentShader;
    private final List<Uniform> uniforms;

    private HudProgramDescriptor(
            ResourceLocation vertexShader,
            ResourceLocation fragmentShader,
            List<Uniform> uniforms
    ) {
        this.vertexShader = vertexShader;
        this.fragmentShader = fragmentShader;
        this.uniforms = uniforms;
    }

    /**
     * Reads the descriptor of one declared program, or returns {@code null} when the loaded packs do not provide a
     * usable one.
     */
    static HudProgramDescriptor read(ResourceManager resources, ResourceLocation declaredAsset) {
        try {
            Optional<Resource> resource = resources.getResource(descriptorId(declaredAsset));
            if (resource.isEmpty()) {
                return null;
            }
            try (BufferedReader reader = resource.get().openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    return null;
                }
                JsonObject root = parsed.getAsJsonObject();
                ResourceLocation vertex = shaderId(declaredAsset, root.get("vertex"));
                ResourceLocation fragment = shaderId(declaredAsset, root.get("fragment"));
                if (vertex == null || fragment == null) {
                    return null;
                }
                return new HudProgramDescriptor(vertex, fragment, uniforms(root.get("uniforms")));
            }
        } catch (IOException | RuntimeException unreadable) {
            // A pack that provides no readable descriptor is a missing program, not a failed frame.
            return null;
        }
    }

    /** {@code <namespace>:<name>} is the descriptor {@code <namespace>:shaders/core/<name>.json}. */
    private static ResourceLocation descriptorId(ResourceLocation declaredAsset) {
        return ResourceLocation.fromNamespaceAndPath(
                declaredAsset.getNamespace(),
                "shaders/" + SHADER_FOLDER + "/" + declaredAsset.getPath() + ".json"
        );
    }

    private static ResourceLocation shaderId(ResourceLocation declaredAsset, JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        ResourceLocation declared = ResourceLocation.tryParse(element.getAsString());
        if (declared == null) {
            return null;
        }
        return declared.getPath().indexOf('/') >= 0
                ? declared
                : ResourceLocation.fromNamespaceAndPath(
                        declared.getNamespace(),
                        SHADER_FOLDER + "/" + declared.getPath()
                );
    }

    private static List<Uniform> uniforms(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<Uniform> uniforms = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            Uniform uniform = Uniform.of(entry.getAsJsonObject());
            if (uniform != null) {
                uniforms.add(uniform);
            }
        }
        return List.copyOf(uniforms);
    }

    ResourceLocation vertexShader() {
        return vertexShader;
    }

    ResourceLocation fragmentShader() {
        return fragmentShader;
    }

    List<Uniform> uniforms() {
        return uniforms;
    }

    /**
     * One uniform the program exposes: the name a feature writes, the value shape its shader declares, and the
     * descriptor's default for it.
     *
     * <p>The shape is the descriptor's own vocabulary ({@code float}, {@code matrix4x4}, …), not a game type: since
     * 1.21.6 a pipeline describes std140 uniform blocks instead of individual uniforms, while the shader sources a
     * feature shares with the older adapters still declare plain uniform variables. The platform therefore reads the
     * shape from the feature's file and writes the values through its individual-uniform path.</p>
     */
    record Uniform(String name, ValueType type, float[] floatDefaults, int[] intDefaults) {

        /** The declared default, as the array the program is given before a feature writes anything. */
        Object defaults() {
            return intDefaults != null ? intDefaults : floatDefaults;
        }

        private static Uniform of(JsonObject entry) {
            JsonElement name = entry.get("name");
            if (name == null || !name.isJsonPrimitive()) {
                return null;
            }
            ValueType type = ValueType.named(entry.get("type"));
            if (type == null) {
                return null;
            }
            return type.intStorage()
                    ? new Uniform(name.getAsString(), type, null, intDefaults(entry.get("values"), type.count()))
                    : new Uniform(name.getAsString(), type, floatDefaults(entry.get("values"), type.count()), null);
        }

        private static float[] floatDefaults(JsonElement values, int width) {
            float[] defaults = new float[width];
            for (int index = 0; index < declaredWidth(values, width); index++) {
                defaults[index] = values.getAsJsonArray().get(index).getAsFloat();
            }
            return defaults;
        }

        private static int[] intDefaults(JsonElement values, int width) {
            int[] defaults = new int[width];
            for (int index = 0; index < declaredWidth(values, width); index++) {
                defaults[index] = values.getAsJsonArray().get(index).getAsInt();
            }
            return defaults;
        }

        /**
         * How many of a uniform's values the descriptor actually lists. A missing or shorter list leaves the rest of
         * the declared width at zero, which is what an unwritten uniform reads as.
         */
        private static int declaredWidth(JsonElement values, int width) {
            if (values == null || !values.isJsonArray()) {
                return 0;
            }
            JsonArray declared = values.getAsJsonArray();
            return Math.min(width, declared.size());
        }
    }

    /**
     * A uniform's value shape as the descriptor spells it: how many numbers it holds, whether they are integers, and
     * therefore which write call carries it.
     */
    enum ValueType {
        FLOAT("float", 1, false),
        INT("int", 1, true),
        IVEC3("ivec3", 3, true),
        VEC2("vec2", 2, false),
        VEC3("vec3", 3, false),
        VEC4("vec4", 4, false),
        MATRIX4X4("matrix4x4", 16, false);

        private final String descriptorName;
        private final int count;
        private final boolean intStorage;

        ValueType(String descriptorName, int count, boolean intStorage) {
            this.descriptorName = descriptorName;
            this.count = count;
            this.intStorage = intStorage;
        }

        /** How many numbers one value of this shape holds. */
        int count() {
            return count;
        }

        /** Whether the shader declares it as an integer uniform, which needs the integer write calls. */
        boolean intStorage() {
            return intStorage;
        }

        private static ValueType named(JsonElement element) {
            if (element == null || !element.isJsonPrimitive()) {
                return null;
            }
            String declared = element.getAsString();
            for (ValueType type : values()) {
                if (type.descriptorName.equals(declared)) {
                    return type;
                }
            }
            return null;
        }
    }
}
