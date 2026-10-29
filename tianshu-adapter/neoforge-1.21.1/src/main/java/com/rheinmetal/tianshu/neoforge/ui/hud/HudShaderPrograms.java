package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HUD shader handles, one per program family, as created from the features' declarations.
 *
 * <p>Registration happens once per client start from {@code HudShaderCatalog}; a program that failed to compile is
 * simply absent, and the surface draws nothing for that family without asking why. The map is concurrent because a shutdown
 * can clear it while a frame is being drawn.</p>
 */
public final class HudShaderPrograms {
    private static final Map<HudProgram, ShaderInstance> PROGRAMS = new ConcurrentHashMap<>();

    private HudShaderPrograms() {
    }

    public static void register(HudProgram program, ShaderInstance shader) {
        Objects.requireNonNull(program, "program");
        if (shader == null) {
            PROGRAMS.remove(program);
        } else {
            PROGRAMS.put(program, shader);
        }
    }

    public static ShaderInstance get(HudProgram program) {
        return program == null ? null : PROGRAMS.get(program);
    }

    public static void clear() {
        PROGRAMS.clear();
    }
}
