package com.rheinmetal.tianshu.api.tool;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * The catalogue entry the conversation agent reads: what the tool is, what it accepts and whether it is on.
 *
 * <p>{@code argsSchema} is the JSON Schema of the argument object when the tool supplies one; the agent can still
 * use a tool without it (the description carries the shape), but a schema makes the call far more reliable.</p>
 */
public record ToolSpec(
        ToolId id,
        ToolCategory category,
        String description,
        JsonObject argsSchema,
        boolean enabled
) {

    public ToolSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(description, "description");
        // Copied, not shared: the schema object belongs to the tool, and a consumer that edits the catalogue entry it
        // was handed would otherwise be editing the registered tool's schema.
        argsSchema = argsSchema == null ? null : argsSchema.deepCopy();
    }
}