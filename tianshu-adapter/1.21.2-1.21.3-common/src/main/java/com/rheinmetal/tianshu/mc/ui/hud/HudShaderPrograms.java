package com.rheinmetal.tianshu.mc.ui.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgramId;
import com.rheinmetal.tianshu.mc.compat.HudShader;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The platform handle for each feature-declared HUD program, created from {@code HudShaderCatalog}.
 *
 * <p>Registration happens once per client start and again after every resource reload. A handle resolves the
 * version's compiled program when it is applied, so a reload replaces the handles while the shader manager keeps
 * owning the compiled programs. A declaration that cannot be registered is simply absent, and the surface draws
 * nothing for it without asking why. The map is concurrent because a shutdown can clear it while a frame is being
 * drawn.</p>
 */
public final class HudShaderPrograms {
    private static final Map<HudProgramId, HudShader> PROGRAMS = new ConcurrentHashMap<>();

    private HudShaderPrograms() {
    }

    public static void register(HudProgramId program, HudShader shader) {
        Objects.requireNonNull(program, "program");
        if (shader == null) {
            PROGRAMS.remove(program);
        } else {
            PROGRAMS.put(program, shader);
        }
    }

    public static HudShader get(HudProgramId program) {
        return program == null ? null : PROGRAMS.get(program);
    }

    public static void clear() {
        PROGRAMS.clear();
    }
}
