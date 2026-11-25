package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A skill says which capabilities it teaches, and the catalogue says so where the model is already looking.
 *
 * <p>The failure this prevents is quiet: guidance that exists but is never connected to the thing it is about, so the
 * model re-derives how to use a capability from its name and gets it wrong.</p>
 */
class SkillCoverageTest {

    private final List<CapabilityId> registered = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        registered.forEach(CapabilityCatalog::unregister);
        registered.clear();
    }

    @Test
    void aSkillDeclaresWhatItCovers() {
        CapabilityDescriptor skill = skill("test:mining.howto", "tianshu:body.mine", "tianshu:body.pickup");

        assertEquals(Set.of(CapabilityId.parse("tianshu:body.mine"), CapabilityId.parse("tianshu:body.pickup")),
                SkillCoverage.coversOf(skill));
    }

    @Test
    void aSkillWithNoDeclarationCoversNothing() {
        CapabilityDescriptor skill = skill("test:plain", new String[0]);

        assertTrue(SkillCoverage.coversOf(skill).isEmpty());
        assertFalse(CapabilityKind.TOOL.equals(skill.kind()));
    }

    @Test
    void anUnreadableEntryIsSkippedWithoutLosingTheRest() {
        JsonObject attributes = new JsonObject();
        com.google.gson.JsonArray covers = new com.google.gson.JsonArray();
        covers.add("not-a-capability-id");
        covers.add("tianshu:body.mine");
        attributes.add(SkillCoverage.COVERS, covers);
        CapabilityDescriptor skill = register("test:mixed", CapabilityKind.SKILL, attributes);

        assertEquals(Set.of(CapabilityId.parse("tianshu:body.mine")), SkillCoverage.coversOf(skill));
    }

    @Test
    void theCatalogueNamesTheManualNextToTheCapability() {
        CapabilityDescriptor skill = skill("test:mining.howto", "tianshu:body.mine");
        CapabilityDescriptor capability = register("tianshu:body.mine", CapabilityKind.RESOURCE, new JsonObject());

        String block = CapabilityExposure.describe(List.of(capability, skill));

        assertTrue(block.contains("how to use: test:mining.howto"), block);
    }

    @Test
    void aCapabilityWithNoManualGetsNoSuffix() {
        CapabilityDescriptor capability = register("test:lonely", CapabilityKind.RESOURCE, new JsonObject());

        String block = CapabilityExposure.describe(List.of(capability));

        assertFalse(block.contains("how to use"), block);
    }

    @Test
    void theManualOfAnInvisibleCapabilityIsNotReferenced() {
        skill("test:mining.howto", "test:not-exposed");
        CapabilityDescriptor other = register("test:other", CapabilityKind.RESOURCE, new JsonObject());

        String block = CapabilityExposure.describe(List.of(other));

        assertFalse(block.contains("test:mining.howto"), block);
    }

    private CapabilityDescriptor skill(String id, String... covers) {
        JsonObject attributes = new JsonObject();
        if (covers.length > 0) {
            SkillCoverage.into(attributes, covers);
        }
        return register(id, CapabilityKind.SKILL, attributes);
    }

    private CapabilityDescriptor register(String id, CapabilityKind kind, JsonObject attributes) {
        CapabilityId capability = CapabilityId.parse(id);
        registered.add(capability);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(capability, kind, CapabilityBinding.LOCAL,
                "a test row", ToolSide.CLIENT, ToolPermission.EVERYONE, "tianshu", attributes);
        CapabilityCatalog.register(descriptor, (context, args) ->
                java.util.concurrent.CompletableFuture.completedFuture(new JsonObject()));
        return descriptor;
    }
}