package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.Objects;

/**
 * One row of the capability table: what exists, who provides it and what a consumer needs to know.
 *
 * <p>It carries no content and no implementation. Large text, schemas fetched at runtime and credentials stay with
 * the provider; a consumer resolves them through {@link CapabilityCatalog#invoke}.</p>
 *
 * @param side        the side that executes it
 * @param permission  who may invoke it
 * @param provider    the module that registered it (diagnostics and routing)
 * @param attributes  kind-specific facts (media type, size, timeout, ...); unknown keys are preserved
 */
public record CapabilityDescriptor(
        CapabilityId id,
        CapabilityKind kind,
        CapabilityBinding binding,
        String description,
        ToolSide side,
        ToolPermission permission,
        String provider,
        JsonObject attributes
) {

    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(provider, "provider");
        attributes = attributes == null ? new JsonObject() : attributes.deepCopy();
    }
}
