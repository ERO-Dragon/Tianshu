package com.rheinmetal.tianshu.api;

/**
 * The names every module, manifest and payload agrees on.
 *
 * <p>They used to be repeated where they were needed, which is how a payload namespace and the mod id it belongs
 * to drift apart. The loader still declares the id in its own manifest; this is the compile-time copy the code
 * refers to, so a loader adapter and the neutral layer cannot spell it two ways.</p>
 */
public final class ModIdentity {

    /** The mod id, as declared in {@code neoforge.mods.toml} and {@code fabric.mod.json}. */
    public static final String MOD_ID = "tianshu";

    private ModIdentity() {
    }
}