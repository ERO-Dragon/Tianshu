package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stand-in for legacy FML's `@Mod`.
 *
 * <p>The 1.20.1 Forge line ships this annotation under `net.minecraftforge`; modern NeoForge ships it under
 * `net.neoforged`. The entry shell for the legacy line compiles against this copy and the stub source set is
 * never packaged, exactly like `src/neoforgeStubs` does for the modern one.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {

    String value();
}