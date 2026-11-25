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

    /** Attribute key on a skill row: an array of capability ids it covers, or {@code *} for "all of them". */
    public static final String COVERS = "covers";

    /**
     * The marker for a manual that applies to <em>every</em> capability: a calling convention, a return-envelope
     * shape, an explanation of the tiers.
     *
     * <p>A star is not a capability, so it is named once at the top of the exposed list instead of being repeated on
     * every row: a rule that applies to everything is noise when printed next to everything.</p>
     */
    public static final String GENERAL = "*";

    private SkillCoverage() {
    }

    /** Whether this skill is a general manual rather than the manual of particular capabilities. */
    public static boolean isGeneral(CapabilityDescriptor skill) {
        if (!CapabilityKind.SKILL.equals(skill.kind())) {
            return false;
        }
        JsonElement declared = skill.attributes().get(COVERS);
        if (declared == null || !declared.isJsonArray()) {
            return false;
        }
        for (JsonElement element : declared.getAsJsonArray()) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    && GENERAL.equals(element.getAsString().trim())) {
                return true;
            }
        }
        return false;
    }

    /** The capabilities one skill covers, without the general marker; empty when it declares none. */
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
                String value = element.getAsString().trim();
                if (GENERAL.equals(value)) {
                    // The general marker is not a capability: it is reported by isGeneral and printed once at the top.
                    continue;
                }
                try {
                    covered.add(CapabilityId.parse(value));
                } catch (RuntimeException malformed) {
                    // A row that cannot be parsed covers nothing; a typo must not take the whole index down.
                }
            }
        }
        return covered;
    }

    /** The skills in these rows that are general manuals, in the order given. */
    public static List<CapabilityId> generalManuals(List<CapabilityDescriptor> rows) {
        List<CapabilityId> manuals = new java.util.ArrayList<>();
        for (CapabilityDescriptor row : rows) {
            if (isGeneral(row)) {
                manuals.add(row.id());
            }
        }
        return manuals;
    }

    /**
     * Skill id → the capabilities it covers, from the rows given.
     *
     * <p>One specific manual per capability: when two skills claim the same capability the first one in the given
     * order keeps it and the later claim is dropped, because two manuals for one capability leave the reader choosing
     * which to believe. The dropped claims are reported by {@link #conflicts(List)} rather than silently lost.</p>
     */
    public static Map<CapabilityId, Set<CapabilityId>> index(List<CapabilityDescriptor> rows) {
        Map<CapabilityId, Set<CapabilityId>> index = new LinkedHashMap<>();
        java.util.Set<CapabilityId> claimed = new java.util.LinkedHashSet<>();
        for (CapabilityDescriptor row : rows) {
            java.util.Set<CapabilityId> covered = new java.util.LinkedHashSet<>();
            for (CapabilityId capability : coversOf(row)) {
                if (claimed.add(capability)) {
                    covered.add(capability);
                }
            }
            if (!covered.isEmpty()) {
                index.put(row.id(), covered);
            }
        }
        return index;
    }

    /**
     * Coverage lines that name nothing: a manual for a capability no module publishes.
     *
     * <p>Silent typos are the failure this catches. An id that does not resolve documents nothing, and nothing about
     * the result says so 鈥?the row simply never appears next to the capability the author meant.</p>
     */
    public static List<String> unknown(List<CapabilityDescriptor> rows) {
        java.util.Set<CapabilityId> published = new java.util.LinkedHashSet<>();
        for (CapabilityDescriptor row : rows) {
            published.add(row.id());
        }
        List<String> problems = new java.util.ArrayList<>();
        for (CapabilityDescriptor row : rows) {
            for (CapabilityId capability : coversOf(row)) {
                if (!published.contains(capability)) {
                    problems.add(row.id() + " documents " + capability + ", which no module publishes");
                }
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Declarations that cannot be honoured, as sentences: a capability claimed by more than one manual.
     *
     * <p>Reported rather than thrown: rejecting the registration would take a whole skill down for one wrong line,
     * and the platform can still say precisely which capability is contended.</p>
     */
    public static List<String> conflicts(List<CapabilityDescriptor> rows) {
        Map<CapabilityId, CapabilityId> owner = new LinkedHashMap<>();
        List<String> problems = new java.util.ArrayList<>();
        for (CapabilityDescriptor row : rows) {
            for (CapabilityId capability : coversOf(row)) {
                CapabilityId first = owner.putIfAbsent(capability, row.id());
                if (first != null && !first.equals(row.id())) {
                    problems.add(capability + " is documented by both " + first + " and " + row.id()
                            + "; " + first + " keeps it");
                }
            }
        }
        return List.copyOf(problems);
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