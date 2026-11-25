package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One manual, many capabilities; one capability, several manuals; and one manual that applies to everything.
 *
 * <p>The last case is why this exists: a calling convention repeated next to forty rows is noise, but a calling
 * convention that is never shown is worse. It goes at the top, once.</p>
 */
class SkillCoverageManyToManyTest {

    private final List<CapabilityId> registered = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        registered.forEach(CapabilityCatalog::unregister);
        registered.clear();
    }

    @Test
    void oneManualCanTeachSeveralCapabilities() {
        CapabilityDescriptor mine = capability("tianshu:body.mine");
        CapabilityDescriptor pickup = capability("tianshu:body.pickup");
        CapabilityDescriptor manual = skill("test:mining", "tianshu:body.mine", "tianshu:body.pickup");

        String block = CapabilityExposure.describe(List.of(mine, pickup, manual));

        assertTrue(block.contains("how to use: test:mining"), block);
        assertEquals(2, block.split("how to use: test:mining", -1).length - 1,
                "both rows that the manual teaches should name it");
    }

    @Test
    void oneCapabilityCanHaveSeveralManuals() {
        CapabilityDescriptor mine = capability("tianshu:body.mine");
        skill("test:mining", "tianshu:body.mine");
        skill("test:safety", "tianshu:body.mine");

        String block = CapabilityExposure.describe(List.of(mine, CapabilityCatalog.find(CapabilityId.parse("test:mining"))
                .orElseThrow(), CapabilityCatalog.find(CapabilityId.parse("test:safety")).orElseThrow()));

        assertTrue(block.contains("test:mining") && block.contains("test:safety"), block);
    }

    @Test
    void aGeneralManualIsStatedOnceAtTheTop() {
        CapabilityDescriptor mine = capability("tianshu:body.mine");
        CapabilityDescriptor pickup = capability("tianshu:body.pickup");
        skill("test:convention", "*");

        String block = CapabilityExposure.describe(List.of(mine, pickup,
                CapabilityCatalog.find(CapabilityId.parse("test:convention")).orElseThrow()));

        assertTrue(block.startsWith("calling convention: test:convention"), block);
        long rowsMentioning = block.lines()
                .filter(line -> line.startsWith("- ") && line.contains("test:convention"))
                .count();
        assertEquals(1, rowsMentioning,
                "only the skill's own row may mention it; the capability rows must not: " + block);
    }

    @Test
    void theStarIsNotTreatedAsACapability() {
        CapabilityDescriptor manual = skill("test:convention", "*");

        assertTrue(SkillCoverage.isGeneral(manual));
        assertTrue(SkillCoverage.coversOf(manual).isEmpty(), "the star is a marker, not a row id");
    }

    @Test
    void aCapabilityWithNoManualGetsNothing() {
        CapabilityDescriptor alone = capability("test:alone");

        String block = CapabilityExposure.describe(List.of(alone));

        assertFalse(block.contains("how to use"), block);
        assertFalse(block.startsWith("calling convention"), block);
    }

    private CapabilityDescriptor capability(String id) {
        return register(id, CapabilityKind.RESOURCE, new JsonObject());
    }

    private CapabilityDescriptor skill(String id, String... covers) {
        JsonObject attributes = new JsonObject();
        SkillCoverage.into(attributes, covers);
        return register(id, CapabilityKind.SKILL, attributes);
    }

    private CapabilityDescriptor register(String id, CapabilityKind kind, JsonObject attributes) {
        CapabilityId capability = CapabilityId.parse(id);
        registered.add(capability);
        CapabilityDescriptor descriptor = new CapabilityDescriptor(capability, kind, CapabilityBinding.LOCAL,
                "a test row", ToolSide.CLIENT, ToolPermission.EVERYONE, "tianshu", attributes);
        CapabilityCatalog.register(descriptor, (context, args) ->
                CompletableFuture.completedFuture(new JsonObject()));
        return descriptor;
    }
}