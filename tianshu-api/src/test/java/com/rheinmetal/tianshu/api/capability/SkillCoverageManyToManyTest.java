package com.rheinmetal.tianshu.api.capability;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
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
    void twoManualsForOneCapabilityConflictAndTheFirstKeepsIt() {
        CapabilityDescriptor mine = capability("tianshu:body.mine");
        CapabilityDescriptor first = skill("test:mining", "tianshu:body.mine");
        CapabilityDescriptor second = skill("test:safety", "tianshu:body.mine");

        // One capability gets one specific manual: two manuals for the same tool leave the reader choosing which to
        // believe, and the second author's work silently disappears from the list either way.
        List<String> conflicts = SkillCoverage.conflicts(List.of(first, second, mine));
        assertEquals(1, conflicts.size(), conflicts.toString());
        assertTrue(conflicts.get(0).contains("test:mining") && conflicts.get(0).contains("test:safety"),
                conflicts.get(0));

        String block = CapabilityExposure.describe(List.of(mine, first, second));
        assertTrue(block.contains("how to use: test:mining"), block);
        assertFalse(block.contains("how to use: test:mining, test:safety"),
                "the losing claim must not be listed on the row: " + block);
    }

    @Test
    void aManualWhoseCapabilitiesAreAllInvisibleIsHidden() {
        // The skill row is open by its provider, but the only capability it documents belongs to a module that shares
        // nothing with this consumer.
        skill("test:orphan", "modx:body.mine");
        register("tianshu:body.visible", CapabilityKind.RESOURCE, new JsonObject());

        var exposed = CapabilityExposure.exposed(ToolCaller.agent(true), ToolSide.CLIENT).stream()
                .map(row -> row.id().toString()).toList();

        assertTrue(exposed.contains("tianshu:body.visible"), exposed.toString());
        assertFalse(exposed.contains("test:orphan"),
                "a manual for nothing this caller can use is not its business: " + exposed);
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

    @Test
    void manualsComeAfterTheCapabilitiesTheyDocument() {
        CapabilityDescriptor manual = skill("test:manual", "tianshu:body.mine");
        CapabilityDescriptor mine = capability("tianshu:body.mine");

        // The skill is given first on purpose: a manual is something to read, not something to call, so the list
        // must not interleave them.
        String block = CapabilityExposure.describe(List.of(manual, mine));

        int capabilityAt = block.indexOf("- tianshu:body.mine");
        int manualAt = block.indexOf("- test:manual");
        assertTrue(capabilityAt >= 0 && manualAt > capabilityAt, block);
    }

    @Test
    void aCoverageLineThatNamesNothingIsReported() {
        CapabilityDescriptor manual = skill("test:typo", "tianshu:body.mien");
        register("tianshu:body.mine", CapabilityKind.RESOURCE, new JsonObject());

        List<String> unknown = SkillCoverage.unknown(CapabilityCatalog.snapshot().descriptors());

        assertEquals(1, unknown.size(), unknown.toString());
        assertTrue(unknown.get(0).contains("tianshu:body.mien"), "the typo itself must be named: " + unknown);
        assertTrue(manual.id().toString().contains("typo"));
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