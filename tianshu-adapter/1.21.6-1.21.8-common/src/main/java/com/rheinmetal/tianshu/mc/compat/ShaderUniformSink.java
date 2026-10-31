package com.rheinmetal.tianshu.mc.compat;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The platform's uniform writer for one program: it validates a feature's names against the uniforms the program
 * declares and holds the values until the quad is drawn.
 *
 * <p>Since 1.21.6 a pipeline describes std140 uniform blocks instead of individual uniforms, while a HUD program's
 * shaders — the assets a feature shares with the older adapters — declare plain uniform variables. The engine still
 * links those variables and binds their locations, so the sink writes them through {@link GlStateManager}, the same
 * state manager the engine's own uniform uploads use. The feature's names, defaults and value shapes are untouched;
 * only the transport differs from 1.21.5's per-pass uniform calls, apart from the two GUI matrices the engine stopped
 * supplying, which {@link #begin(float)} seeds for a program that declares them.</p>
 *
 * <p>The instance is reused across quads and frames, so a caller must not keep it. Locations are resolved once per
 * program id: a reload compiles new programs and the platform builds a new handle, and therefore a new sink, so a
 * cached location cannot outlive the program it belongs to.</p>
 */
final class ShaderUniformSink implements HudUniformSink {

    /**
     * The GUI projection uniform. Up to 1.21.5 the engine wrote the GUI orthographic projection into every GUI
     * program; 1.21.6 moved the GUI transform into std140 blocks and stopped filling the plain variable, so a HUD
     * program that still declares it — the shader contract a feature shares with the older adapters — would receive
     * the descriptor's identity matrix instead.
     */
    private static final String GUI_PROJECTION_UNIFORM = "ProjMat";

    /**
     * The GUI model-view uniform, the second variable the engine used to supply; see
     * {@link #GUI_PROJECTION_UNIFORM}. Its value pushes the quad to the far plane of the GUI volume (see
     * {@link #guiPose()}), because the surface already baked the GUI pose into the quad's vertices.
     */
    private static final String GUI_POSE_UNIFORM = "ModelViewMat";

    /**
     * The depth range of the engine's GUI volume. 1.21.6's own GUI pass projects GUI pixels with a 1000..11000
     * orthographic volume and translates GUI geometry by -11000, so the two numbers belong together: a quad left at
     * z = 0 would project to z_ndc = -1.2 and be clipped, which is why the model-view is not the identity.
     */
    private static final float GUI_Z_NEAR = 1000.0F;
    private static final float GUI_Z_FAR = 11000.0F;

    private final List<Slot> slots = new ArrayList<>();
    private final Map<String, Slot> byName = new HashMap<>();
    private float frameTimeSeconds;
    private int locatedProgramId = -1;

    /** Declares the program's uniforms, replacing whatever a previous program declared. */
    void describe(HudProgramDescriptor descriptor) {
        slots.clear();
        byName.clear();
        locatedProgramId = -1;
        for (HudProgramDescriptor.Uniform uniform : descriptor.uniforms()) {
            Slot slot = new Slot(uniform);
            slots.add(slot);
            byName.put(slot.name(), slot);
        }
    }

    /**
     * Starts one quad: every uniform reads the descriptor's default until the feature writes it.
     *
     * <p>The two GUI matrices the engine used to supply are seeded here rather than left at the descriptor's default,
     * because the platform owns the GUI transform: {@code ProjMat} becomes the GUI orthographic projection over the
     * scaled GUI — the engine's own GUI volume, depth 1000..11000 — and {@code ModelViewMat} pushes the quad to
     * eye-space z = -11000, the far plane that volume expects. Without that translation a quad at z = 0 would land at
     * z_ndc = -1.2 and be clipped away. Only a slot the descriptor declares as a 4x4 matrix of that name is touched, so
     * a program that declares neither is unaffected, and the feature's own bind still runs afterwards and may override
     * either value.</p>
     */
    void begin(float frameTimeSeconds) {
        this.frameTimeSeconds = frameTimeSeconds;
        for (Slot slot : slots) {
            slot.reset();
        }
        seedGuiMatrices();
    }

    /**
     * Writes the GUI transform into the two slots that declare it, each through its own matrix storage.
     *
     * <p>The values are read per quad instead of cached: the GUI scale is the window's, and the ordinary program
     * declares neither name and stops after the two lookups.</p>
     */
    private void seedGuiMatrices() {
        Slot projection = matrixSlot(GUI_PROJECTION_UNIFORM);
        Slot pose = matrixSlot(GUI_POSE_UNIFORM);
        if (projection == null && pose == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft == null ? null : minecraft.getWindow();
        if (window == null) {
            // No client window means no GUI volume to project into: the descriptor's defaults stay in place.
            return;
        }
        if (projection != null) {
            guiProjection(window.getGuiScaledWidth(), window.getGuiScaledHeight()).get(projection.floats());
        }
        if (pose != null) {
            guiPose().get(pose.floats());
        }
    }

    /** The slot that declares a 4x4 matrix of this name, or {@code null} when the program declares none. */
    private Slot matrixSlot(String name) {
        return slot(name, false, HudProgramDescriptor.ValueType.MATRIX4X4.count());
    }

    /**
     * The engine's GUI orthographic volume: scaled GUI pixels, origin top left, mapped onto clip space with a depth
     * range of 1000..11000.
     */
    static Matrix4f guiProjection(int guiScaledWidth, int guiScaledHeight) {
        return new Matrix4f().setOrtho(0.0F, guiScaledWidth, guiScaledHeight, 0.0F, GUI_Z_NEAR, GUI_Z_FAR);
    }

    /**
     * The model-view that puts a quad whose vertices carry GUI pixels at z = 0 onto the far plane of
     * {@link #guiProjection(int, int)}: the engine translates its GUI geometry by -11000 for the same reason.
     */
    static Matrix4f guiPose() {
        return new Matrix4f().setTranslation(0.0F, 0.0F, -GUI_Z_FAR);
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

    /**
     * Writes the program's whole uniform set, in the shapes the descriptor declared.
     *
     * <p>Called after the program is bound and immediately before the quad is submitted. A name the compiled shader
     * does not actually use resolves to no location and is skipped — a stale descriptor is not an error.</p>
     */
    void applyTo(int programId) {
        if (programId < 0) {
            return;
        }
        locate(programId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (Slot slot : slots) {
                if (slot.location() < 0) {
                    continue;
                }
                switch (slot.type()) {
                    case FLOAT -> GlStateManager._glUniform1(slot.location(), floatBuffer(stack, slot.floats()));
                    case INT -> GlStateManager._glUniform1i(slot.location(), slot.ints()[0]);
                    case IVEC3 -> GlStateManager._glUniform3(slot.location(), intBuffer(stack, slot.ints()));
                    case VEC2 -> GlStateManager._glUniform2(slot.location(), floatBuffer(stack, slot.floats()));
                    case VEC3 -> GlStateManager._glUniform3(slot.location(), floatBuffer(stack, slot.floats()));
                    case VEC4 -> GlStateManager._glUniform4(slot.location(), floatBuffer(stack, slot.floats()));
                    case MATRIX4X4 ->
                            GlStateManager._glUniformMatrix4(slot.location(), floatBuffer(stack, slot.floats()));
                }
            }
        }
    }

    private void locate(int programId) {
        if (programId == locatedProgramId) {
            return;
        }
        for (Slot slot : slots) {
            slot.locate(programId);
        }
        locatedProgramId = programId;
    }

    private static FloatBuffer floatBuffer(MemoryStack stack, float[] values) {
        FloatBuffer buffer = stack.mallocFloat(values.length);
        buffer.put(values);
        buffer.flip();
        return buffer;
    }

    private static IntBuffer intBuffer(MemoryStack stack, int[] values) {
        IntBuffer buffer = stack.mallocInt(values.length);
        buffer.put(values);
        buffer.flip();
        return buffer;
    }

    private Slot slot(String name, boolean intStorage, int width) {
        if (name == null) {
            return null;
        }
        Slot slot = byName.get(name);
        return slot != null && slot.matches(intStorage, width) ? slot : null;
    }

    /** One declared uniform and its working values: float storage for the float shapes, int storage for the rest. */
    private static final class Slot {
        private final String name;
        private final HudProgramDescriptor.ValueType type;
        private final float[] defaultFloats;
        private final int[] defaultInts;
        private final float[] floats;
        private final int[] ints;
        private int location = -1;

        private Slot(HudProgramDescriptor.Uniform uniform) {
            this.name = uniform.name();
            this.type = uniform.type();
            this.defaultFloats = uniform.floatDefaults();
            this.defaultInts = uniform.intDefaults();
            this.floats = defaultFloats == null ? null : new float[defaultFloats.length];
            this.ints = defaultInts == null ? null : new int[defaultInts.length];
        }

        private String name() {
            return name;
        }

        private HudProgramDescriptor.ValueType type() {
            return type;
        }

        private float[] floats() {
            return floats;
        }

        private int[] ints() {
            return ints;
        }

        private int location() {
            return location;
        }

        private void locate(int programId) {
            location = GlStateManager._glGetUniformLocation(programId, name);
        }

        private boolean matches(boolean intStorage, int width) {
            int declaredWidth = ints != null ? ints.length : floats.length;
            return (ints != null) == intStorage && declaredWidth == width;
        }

        private void reset() {
            if (ints != null) {
                System.arraycopy(defaultInts, 0, ints, 0, ints.length);
            } else {
                System.arraycopy(defaultFloats, 0, floats, 0, floats.length);
            }
        }
    }
}
