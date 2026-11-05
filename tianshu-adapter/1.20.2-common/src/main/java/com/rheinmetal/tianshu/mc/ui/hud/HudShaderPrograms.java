package com.rheinmetal.tianshu.mc.ui.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgramId;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The HUD shader handles, one per feature-declared program id, as created from {@code HudShaderCatalog}.
 *
 * <p>Registration happens once per client start; a program that failed to compile is simply absent, and the surface
 * draws nothing for it without asking why. The map is concurrent because a shutdown can clear it while a frame is
 * being drawn.</p>
 */
public final class HudShaderPrograms {
    private static final Map<HudProgramId, ShaderInstance> PROGRAMS = new ConcurrentHashMap<>();

    private HudShaderPrograms() {
    }

    public static void register(HudProgramId program, ShaderInstance shader) {
        Objects.requireNonNull(program, "program");
        if (shader == null) {
            PROGRAMS.remove(program);
        } else {
            PROGRAMS.put(program, shader);
        }
    }

    public static ShaderInstance get(HudProgramId program) {
        return program == null ? null : PROGRAMS.get(program);
    }

    public static void clear() {
        PROGRAMS.clear();
    }
}
