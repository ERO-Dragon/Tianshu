package com.rheinmetal.tianshu.dubhe.agent.skill;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What the agent reads before deciding whether to call a skill (G45).
 *
 * <p>A spec is data: the agent's directory is built from these fields, and adding a skill never changes the loop.
 * The description is written for the agent (what it can do), never for the player.</p>
 *
 * <p>Two strong rules are enforced here rather than at the call site: skills are always internal（G47 修订：
 * 技能不对外，对外的是功能）；and every skill's report schema must include {@code outcome}, so a failure can never be
 * reported as an empty success.</p>
 *
 * @param id            stable identifier, lowercase, used in diagnostics and {@code skillsUsed}
 * @param description   one sentence for the agent directory
 * @param inputSchema   JSON Schema for the arguments the agent fills in
 * @param promptProfile resource key of this skill's own prompt; blank when the skill needs none
 * @param capabilities  capability or tool ids this skill may use
 * @param visibility    always internal; kept as an explicit field so a future exposure rule has one place to live
 * @param autonomy      risk tier declared by the skill; it never widens what 舟楫 allows
 * @param budget        per-invocation ceiling
 * @param reportSchema  report fields this skill fills; must contain {@code outcome}
 * @param retry         whether a failed invocation may be retried
 * @param lane          player-facing or background
 */
public record DubheSkillSpec(
        String id,
        String description,
        JsonObject inputSchema,
        String promptProfile,
        List<String> capabilities,
        DubheSkillVisibility visibility,
        ToolAutonomy autonomy,
        DubheSkillBudget budget,
        List<String> reportSchema,
        boolean retry,
        DubheSkillLane lane
) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final int MAX_DESCRIPTION = 200;

    public DubheSkillSpec {
        id = id == null ? "" : id.trim();
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("skill id must match " + ID.pattern() + ": " + id);
        }
        description = description == null ? "" : description.trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("skill " + id + " needs a description for the agent directory");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException(
                    "skill " + id + " description exceeds " + MAX_DESCRIPTION + " characters");
        }
        inputSchema = inputSchema == null ? emptySchema() : inputSchema.deepCopy();
        promptProfile = promptProfile == null ? "" : promptProfile.trim();
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        visibility = visibility == null ? DubheSkillVisibility.INTERNAL : visibility;
        budget = Objects.requireNonNull(budget, "budget");
        reportSchema = reportSchema == null ? List.of() : List.copyOf(reportSchema);
        if (!reportSchema.contains("outcome")) {
            throw new IllegalArgumentException(
                    "skill " + id + " report schema must contain 'outcome'; a failure is never an empty success");
        }
        lane = lane == null ? DubheSkillLane.PLAYER : lane;
    }

    public static JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        return schema;
    }

    public boolean mayWrite() {
        return autonomy != null && autonomy.mayWrite();
    }

    public static Builder builder(String id, String description) {
        return new Builder(id, description);
    }

    /** Fluent construction; only {@code budget} has no default, because a skill without a ceiling is a bug. */
    public static final class Builder {

        private final String id;
        private final String description;
        private JsonObject inputSchema;
        private String promptProfile;
        private List<String> capabilities = List.of();
        private DubheSkillVisibility visibility = DubheSkillVisibility.INTERNAL;
        private ToolAutonomy autonomy;
        private DubheSkillBudget budget;
        private List<String> reportSchema = List.of("outcome", "facts", "blocked");
        private boolean retry;
        private DubheSkillLane lane = DubheSkillLane.PLAYER;

        private Builder(String id, String description) {
            this.id = id;
            this.description = description;
        }

        public Builder inputSchema(JsonObject value) {
            this.inputSchema = value;
            return this;
        }

        public Builder promptProfile(String value) {
            this.promptProfile = value;
            return this;
        }

        public Builder capabilities(List<String> value) {
            this.capabilities = value;
            return this;
        }

        public Builder visibility(DubheSkillVisibility value) {
            this.visibility = value;
            return this;
        }

        public Builder autonomy(ToolAutonomy value) {
            this.autonomy = value;
            return this;
        }

        public Builder budget(DubheSkillBudget value) {
            this.budget = value;
            return this;
        }

        public Builder reportSchema(List<String> value) {
            this.reportSchema = value;
            return this;
        }

        public Builder retry(boolean value) {
            this.retry = value;
            return this;
        }

        public Builder lane(DubheSkillLane value) {
            this.lane = value;
            return this;
        }

        public DubheSkillSpec build() {
            return new DubheSkillSpec(
                    id, description, inputSchema, promptProfile, capabilities, visibility, autonomy, budget,
                    reportSchema, retry, lane);
        }
    }
}