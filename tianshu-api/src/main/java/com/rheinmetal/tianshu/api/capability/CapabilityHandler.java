package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * Executes or resolves one non-tool capability.
 *
 * <p>The result is the same envelope the tool channel uses: {@code {"ok":true,"text":...}} for resources and
 * prompts, {@code {"ok":false,"code":...}} on failure. A skill may return text and/or structured data under
 * {@code data}. Implementations must not block the caller.</p>
 */
@FunctionalInterface
public interface CapabilityHandler {

    CompletableFuture<JsonObject> invoke(CapabilityContext context, JsonObject args);
}
