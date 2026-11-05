package com.rheinmetal.tianshu.mc.compat;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;

/**
 * The shader sources of one HUD program, in the shape the 26.3 pipeline compiler asks for.
 *
 * <p>26.3 compiles a pipeline from a {@link ShaderSource} — the interface the game's own shader manager implements —
 * instead of resolving asset ids itself. A HUD program's two sources are the files the feature already ships, so this
 * class reads them by the same convention the game uses ({@link ShaderType#idConverter()}: {@code shaders/<path>.vsh}
 * and {@code .fsh}) and reuses the game's own {@code #moj_import} include cache for anything a source imports.
 * Reading happens when the compiler asks, so a program that was declared but never drawn costs no I/O.</p>
 *
 * <p>The instance owns the include entries it hands out — they wrap native memory — and lives as long as the compiled
 * pipeline it produced, which is how the engine treats its own sources too (a backend recompile reads them again).</p>
 */
final class HudShaderSource implements ShaderSource {

    private final ResourceManager resources;
    private final Map<Identifier, ShaderSource.CachedIncludeSource> includes;

    private HudShaderSource(ResourceManager resources, Map<Identifier, ShaderSource.CachedIncludeSource> includes) {
        this.resources = resources;
        this.includes = includes;
    }

    /** A source over the packs loaded right now; the caller keeps it for the lifetime of the compiled pipeline. */
    static HudShaderSource of(ResourceManager resources) {
        return new HudShaderSource(resources, ShaderManager.listAllIncludes(resources));
    }

    @Override
    public String getShader(Identifier id, ShaderType type) {
        // A missing source is the compiler's failure to report for that one pipeline, not a thrown exception here:
        // the compile future completes exceptionally and the HUD simply keeps drawing nothing for that program.
        Identifier file = type.idConverter().idToFile(id);
        try {
            return resources.getResourceOrThrow(file).readAllAsString();
        } catch (Exception unreadable) {
            return null;
        }
    }

    @Override
    public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
        return includes.get(id);
    }

    @Override
    public void close() {
        includes.values().forEach(ShaderSource.CachedIncludeSource::close);
    }
}
