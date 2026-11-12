package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stand-in for FML's {@code @Mod}.
 *
 * <p>The real annotation is supplied by the loader at runtime; this copy exists only so an entrypoint
 * can compile without pulling the whole NeoForge artifact into a platform-neutral module. It is never
 * packaged: the stub source set is compile-only, exactly like {@code tianshu_foundry} does for its
 * Forge compatibility stubs.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {

    String value();
}
