package com.rheinmetal.tianshu.dubhe.agent.skill;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheSkillSpecTest {

    private static final DubheSkillBudget BUDGET = DubheSkillBudget.of(1_000L, 1);

    private static DubheSkillSpec.Builder valid() {
        return DubheSkillSpec.builder("research", "查资料并整理成结论")
                .budget(DubheSkillBudget.of(30_000L, 8));
    }

    @Test
    void aMinimalInternalSkillDeclaresEverythingTheAgentNeeds() {
        DubheSkillSpec spec = valid().build();

        assertEquals("research", spec.id());
        assertEquals(List.of("outcome", "facts", "blocked"), spec.reportSchema());
        assertFalse(spec.external());
        assertFalse(spec.mayWrite());
        assertEquals(DubheSkillLane.PLAYER, spec.lane());
        assertEquals("object", spec.inputSchema().get("type").getAsString());
    }

    @Test
    void idsAreLowercaseStableTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> DubheSkillSpec.builder("Research", "x").budget(BUDGET).build());
        assertThrows(IllegalArgumentException.class,
                () -> DubheSkillSpec.builder("1research", "x").budget(BUDGET).build());
        assertThrows(IllegalArgumentException.class,
                () -> DubheSkillSpec.builder("", "x").budget(BUDGET).build());
        assertThrows(IllegalArgumentException.class,
                () -> DubheSkillSpec.builder(null, "x").budget(BUDGET).build());
    }

    @Test
    void theDirectoryNeedsAReadableDescription() {
        assertThrows(IllegalArgumentException.class,
                () -> DubheSkillSpec.builder("research", "  ").budget(BUDGET).build());
    }

    @Test
    void anExternalSkillMustDeclareItsRiskTier() {
        assertThrows(IllegalArgumentException.class,
                () -> valid().visibility(DubheSkillVisibility.EXTERNAL).build());

        DubheSkillSpec exposed = valid()
                .visibility(DubheSkillVisibility.EXTERNAL)
                .autonomy(ToolAutonomy.READ_ONLY)
                .build();
        assertTrue(exposed.external());
        assertFalse(exposed.mayWrite());
    }

    @Test
    void aReportSchemaWithoutOutcomeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> valid().reportSchema(List.of("facts", "blocked")).build());
    }

    @Test
    void theBudgetIsMandatory() {
        assertThrows(NullPointerException.class, () -> DubheSkillSpec.builder("research", "x").build());
    }

    @Test
    void theSpecDoesNotShareMutableStateWithItsCaller() {
        JsonObject schema = DubheSkillSpec.emptySchema();
        List<String> capabilities = new ArrayList<>(List.of("cap.a"));
        DubheSkillSpec spec = valid().inputSchema(schema).capabilities(capabilities).build();

        schema.addProperty("type", "mutated");
        capabilities.add("cap.b");

        assertEquals("object", spec.inputSchema().get("type").getAsString());
        assertEquals(List.of("cap.a"), spec.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> spec.capabilities().add("cap.c"));
    }
}