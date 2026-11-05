package com.rheinmetal.tianshu.mc.compat;

import com.mojang.blaze3d.systems.RenderPass;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The platform's uniform writer for one program: it validates a feature's names against the uniforms the program
 * declares and holds the values until the quad is drawn.
 *
 * <p>1.21.5 uploads uniforms through the pass that performs the draw, and every uniform a pipeline declares has to
 * carry a value by then. The sink therefore starts each quad from the descriptor's declared defaults and records the
 * feature's writes over them, and the pass uploads the whole set. A name the program does not declare, or a value
 * whose width does not match the declared type, is reported as {@code false} and changes nothing — a normal outcome,
 * not a frame failure.</p>
 *
 * <p>The instance is reused across quads and frames, so a caller must not keep it.</p>
 */
final class ShaderUniformSink implements HudUniformSink {

    private final List<Slot> slots = new ArrayList<>();
    private final Map<String, Slot> byName = new HashMap<>();
    private float frameTimeSeconds;

    /** Declares the program's uniforms, replacing whatever a previous program declared. */
    void describe(HudProgramDescriptor descriptor) {
        slots.clear();
        byName.clear();
        for (HudProgramDescriptor.Uniform uniform : descriptor.uniforms()) {
            Slot slot = new Slot(uniform);
            slots.add(slot);
            byName.put(slot.name(), slot);
        }
    }

    /** Starts one quad: every uniform reads the descriptor's default until the feature writes it. */
    void begin(float frameTimeSeconds) {
        this.frameTimeSeconds = frameTimeSeconds;
        for (Slot slot : slots) {
            slot.reset();
        }
    }

    @Override
    public float frameTimeSeconds() {
        return frameTimeSeconds;
    }

    @Override
    public boolean setFloat(String name, float value) {
        Slot slot = slot(name, false, 1);
        if (slot == null) {
            return false;
        }
        slot.floats()[0] = value;
        return true;
    }

    @Override
    public boolean setInt(String name, int value) {
        Slot slot = slot(name, true, 1);
        if (slot == null) {
            return false;
        }
        slot.ints()[0] = value;
        return true;
    }

    @Override
    public boolean setVec2(String name, float x, float y) {
        Slot slot = slot(name, false, 2);
        if (slot == null) {
            return false;
        }
        float[] values = slot.floats();
        values[0] = x;
        values[1] = y;
        return true;
    }

    @Override
    public boolean setVec3(String name, float x, float y, float z) {
        Slot slot = slot(name, false, 3);
        if (slot == null) {
            return false;
        }
        float[] values = slot.floats();
        values[0] = x;
        values[1] = y;
        values[2] = z;
        return true;
    }

    @Override
    public boolean setVec4(String name, float x, float y, float z, float w) {
        Slot slot = slot(name, false, 4);
        if (slot == null) {
            return false;
        }
        float[] values = slot.floats();
        values[0] = x;
        values[1] = y;
        values[2] = z;
        values[3] = w;
        return true;
    }

    /** Hands the program's whole uniform set to the pass that is about to draw the quad. */
    void applyTo(RenderPass pass) {
        for (Slot slot : slots) {
            if (slot.ints() != null) {
                pass.setUniform(slot.name(), slot.ints());
            } else {
                pass.setUniform(slot.name(), slot.floats());
            }
        }
    }

    private Slot slot(String name, boolean intStorage, int width) {
        if (name == null) {
            return null;
        }
        Slot slot = byName.get(name);
        return slot != null && slot.matches(intStorage, width) ? slot : null;
    }

    /** One declared uniform and its working values: float storage for the float types, int storage for the rest. */
    private static final class Slot {
        private final String name;
        private final Object defaults;
        private final float[] floats;
        private final int[] ints;

        private Slot(HudProgramDescriptor.Uniform uniform) {
            this.name = uniform.name();
            this.defaults = uniform.defaults();
            boolean intStorage = uniform.type().isIntStorage();
            int width = uniform.type().count();
            this.floats = intStorage ? null : new float[width];
            this.ints = intStorage ? new int[width] : null;
        }

        private String name() {
            return name;
        }

        private float[] floats() {
            return floats;
        }

        private int[] ints() {
            return ints;
        }

        private boolean matches(boolean intStorage, int width) {
            return (ints != null) == intStorage && (ints != null ? ints.length : floats.length) == width;
        }

        private void reset() {
            if (ints != null) {
                System.arraycopy(defaults, 0, ints, 0, ints.length);
            } else {
                System.arraycopy(defaults, 0, floats, 0, floats.length);
            }
        }
    }
}
