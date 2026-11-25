package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which capabilities a skill teaches, declared on the skill and read back from the list a consumer already holds.
 *
 * <p>A skill is prose; prose about "how to use this" is worthless if the reader cannot tell which capability it belongs
 * to. Declaring the coverage turns a pile of documentation into "this capability comes with a manual", and it works for
 * both kinds: a skill may cover one atomic capability, or a workflow ("when to run this, what it will cost").</p>
 *
 * <p>The index is built from the rows the caller is already allowed to see, not from the whole catalogue: a skill that
 * teaches a capability the caller cannot use is not the caller's business, and building it from exposed rows keeps this
 * consistent with what the model was shown.</p>
 */
public final class SkillCoverage {

    /** Attribute key on a skill row: an array of capability ids it covers. */
    public static final String COVERS = "covers";

    private SkillCoverage() {
    }

    /** The capabilities one skill covers; empty when it declares none. */
    public static Set<CapabilityId> coversOf(CapabilityDescriptor skill) {
        if (!CapabilityKind.SKILL.equals(skill.kind())) {
            return Set.of();
        }
        JsonElement declared = skill.attributes().get(COVERS);
        if (declared == null || !declared.isJsonArray()) {
            return Set.of();
        }
        Set<CapabilityId> covered = new LinkedHashSet<>();
        for (JsonElement element : declared.getAsJsonArray()) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                try {
                    covered.add(CapabilityId.parse(element.getAsString()));
                } catch (RuntimeException malformed) {
                    // A row that cannot be parsed covers nothing; a typo must not take the whole index down.
                }
            }
        }
        return covered;
    }

    /** Skill id → the capabilities it covers, from the rows given. */
    public static Map<CapabilityId, Set<CapabilityId>> index(List<CapabilityDescriptor> rows) {
        Map<CapabilityId, Set<CapabilityId>> index = new LinkedHashMap<>();
        for (CapabilityDescriptor row : rows) {
            Set<CapabilityId> covered = coversOf(row);
            if (!covered.isEmpty()) {
                index.put(row.id(), covered);
            }
        }
        return index;
    }


    /** The declaration as an attribute value, for a provider building its skill row. */
    public static JsonElement attribute(String... capabilities) {
        JsonArray array = new JsonArray();
        for (String capability : capabilities) {
            array.add(capability);
        }
        return array;
    }

    /** Convenience for a provider building its skill row. */
    public static JsonObject into(JsonObject attributes, String... capabilities) {
        attributes.add(COVERS, attribute(capabilities));
        return attributes;
    }
}