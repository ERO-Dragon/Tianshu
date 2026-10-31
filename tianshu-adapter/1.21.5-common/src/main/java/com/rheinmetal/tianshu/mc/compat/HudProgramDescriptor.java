package com.rheinmetal.tianshu.mc.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A feature's program descriptor, converted into the facts a render pipeline needs.
 *
 * <p>A feature declares a program by the name its files have inside {@code shaders/core}: the descriptor
 * {@code shaders/core/<name>.json} names the two shader sources and the uniforms the program exposes. Up to 1.21.4
 * the game read that descriptor itself; 1.21.5 has no data-driven programs any more, so the platform reads the same
 * asset and builds the pipeline from it. The asset therefore stays the feature's own declaration — the platform
 * learns no shader name, uniform name or uniform type from anything but this file.</p>
 *
 * <p>Only what a pipeline needs is read: the two shader sources and the uniform names with their types. A shader
 * source may be named with its folder ({@code core/gui}, as the game's own descriptors do) or without it, in which
 * case it is resolved inside the descriptor's own folder. The {@code count} field of a uniform entry is not read:
 * on 1.21.5 a uniform's width is its type. A descriptor this class cannot read yields {@code null}, and the caller
 * treats that exactly like a program the loaded packs do not provide.</p>
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
     * One uniform the program exposes: the name a feature writes, the type the pipeline declares, and the descriptor's
     * default value for it.
     */
    record Uniform(String name, UniformType type, float[] floatDefaults, int[] intDefaults) {

        /** The declared default, as the array the pass is given before a feature writes anything. */
        Object defaults() {
            return intDefaults != null ? intDefaults : floatDefaults;
        }

        private static Uniform of(JsonObject entry) {
            JsonElement name = entry.get("name");
            if (name == null || !name.isJsonPrimitive()) {
                return null;
            }
            UniformType type = type(entry.get("type"));
            if (type == null) {
                return null;
            }
            return type.isIntStorage()
                    ? new Uniform(name.getAsString(), type, null, intDefaults(entry.get("values"), type.count()))
                    : new Uniform(name.getAsString(), type, floatDefaults(entry.get("values"), type.count()), null);
        }

        private static UniformType type(JsonElement element) {
            if (element == null || !element.isJsonPrimitive()) {
                return null;
            }
            String declared = element.getAsString();
            for (UniformType type : UniformType.values()) {
                if (type.getSerializedName().equals(declared)) {
                    return type;
                }
            }
            return null;
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
}
