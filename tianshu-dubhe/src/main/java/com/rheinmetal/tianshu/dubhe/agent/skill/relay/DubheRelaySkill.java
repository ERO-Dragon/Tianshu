package com.rheinmetal.tianshu.dubhe.agent.skill.relay;

import com.rheinmetal.tianshu.dubhe.DubheModule;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityKind;
import com.rheinmetal.tianshu.api.capability.CapabilityExposure;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillBudget;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillContext;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillLane;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillSpec;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkillVisibility;
import com.rheinmetal.tianshu.dubhe.shared.failure.DubheActionFailure;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 天枢的外交技能：把一件事交给另一个已知模组去办，或按需查询它的进度（G28）。
 *
 * <p>天枢没有实体，因此这个技能<b>不执行任何世界动作</b>：它只向对方已注册的 capability 投递任务，
 * 再在玩家问起时调用对方注册的读工具查询。对方的权限边界仍留在对方那边。</p>
 *
 * <p>两种动作：</p>
 * <ul>
 *   <li>{@code submit} — {@code target} 是 {@code namespace:name} 形式的 capability，参数带 {@code task}；
 *       要求对方返回 {@code taskId}，否则按失败处理（没有 taskId 就无法查询进度）。</li>
 *   <li>{@code progress} — {@code target} 是对方注册的读工具，参数带 {@code taskId}；只映射
 *       {@code RUNNING} / {@code DONE} / {@code UNKNOWN} 三态，不认识的返回值一律 {@code UNKNOWN}，
 *       不许把"没说"当成"完成"。</li>
 * </ul>
 *
 * <p>调用方的档位由注册时注入的 {@link ToolCaller} 决定；舟楫会用它自己的自治度判定是否放行，
 * 本技能不自行放大权限。</p>
 */
public final class DubheRelaySkill {

    public static final String ID = "relay";
    private static final long TIMEOUT_MILLIS = 30_000L;
    private static final String OUTCOME_OK = "OK";
    private static final String OUTCOME_FAILED = "FAILED";
    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_DONE = "DONE";
    private static final String STATE_UNKNOWN = "UNKNOWN";

    private final Supplier<ToolCaller> caller;
    private final com.rheinmetal.tianshu.dubhe.settings.DubheAutonomyConsent consent;
    private final com.rheinmetal.tianshu.dubhe.dialogue.output.DubheReplyTextRepository texts;

    public DubheRelaySkill(Supplier<ToolCaller> caller) {
        this(caller, null, null);
    }

    /**
     * @param consent first-use gate; {@code null} disables the gate (tests and callers without settings)
     */
    public DubheRelaySkill(Supplier<ToolCaller> caller,
                               com.rheinmetal.tianshu.dubhe.settings.DubheAutonomyConsent consent) {
        this(caller, consent, null);
    }

    /**
     * @param texts resolves the player-facing sentence of an action failure; {@code null} leaves {@code block} out
     */
    public DubheRelaySkill(Supplier<ToolCaller> caller,
                               com.rheinmetal.tianshu.dubhe.settings.DubheAutonomyConsent consent,
                               com.rheinmetal.tianshu.dubhe.dialogue.output.DubheReplyTextRepository texts) {
        this.caller = caller;
        this.consent = consent;
        this.texts = texts;
    }

    public DubheSkill skill() {
        return new DubheSkill(spec(), this::execute);
    }

    public static DubheSkillSpec spec() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("action", property("string", "list / describe / submit / progress"));
        properties.add("target", property("string", "capability id for submit, read-tool id for progress"));
        properties.add("task", property("string", "what to ask the target module to do"));
        properties.add("taskId", property("string", "the target module's task id, for progress"));
        schema.add("properties", properties);
        schema.add("required", stringArray("action"));
        return DubheSkillSpec.builder(ID,
                        "hand a task to another known module, or ask it for progress")
                .inputSchema(schema)
                .visibility(DubheSkillVisibility.INTERNAL)
                .autonomy(ToolAutonomy.CONSERVATIVE)
                .budget(DubheSkillBudget.of(TIMEOUT_MILLIS, 1))
                .reportSchema(List.of("outcome", "taskId", "progress"))
                .retry(true)
                .lane(DubheSkillLane.PLAYER)
                .build();
    }

    private CompletableFuture<JsonObject> execute(DubheSkillContext context, JsonObject args) {
        ToolCaller effective = effectiveCaller();
        if (consent != null && consent.needsConsent(effective.autonomy())) {
            // First use of this tier: ask the player once. Nothing leaves the module until the player agrees.
            consent.markPending(effective.autonomy());
            return CompletableFuture.completedFuture(needsConsent(effective.autonomy()));
        }
        String action = text(args, "action").toLowerCase(Locale.ROOT);
        if ("describe".equals(action)) {
            return CompletableFuture.completedFuture(describeTarget(text(args, "target")));
        }
        if ("list".equals(action)) {
            return CompletableFuture.completedFuture(listTargets());
        }
        String target = text(args, "target");
        IdRef id = IdRef.parse(target);
        if (id == null) {
            return CompletableFuture.completedFuture(failed("INVALID_TARGET"));
        }
        if ("submit".equals(action)) {
            String task = text(args, "task");
            if (task.isBlank()) {
                return CompletableFuture.completedFuture(failed("INVALID_ARGS"));
            }
            return submit(id, task);
        }
        if ("progress".equals(action)) {
            String taskId = text(args, "taskId");
            if (taskId.isBlank()) {
                return CompletableFuture.completedFuture(failed("INVALID_ARGS"));
            }
            return progress(id, taskId);
        }
        return CompletableFuture.completedFuture(failed("INVALID_ACTION"));
    }

    /** 从舟楫列出其他模块对外的功能（技能/工具），供代理决定把任务交给谁。 */
    /** 渐进式披露的第二跳：选中一个目标后，才取它的完整描述与属性（参考）。 */
    private JsonObject describeTarget(String target) {
        IdRef id = IdRef.parse(target);
        if (id == null) {
            return failed("INVALID_TARGET", DubheActionFailure.TARGET_UNKNOWN, "");
        }
        for (var descriptor : CapabilityExposure.exposed(effectiveCaller(), ToolSide.CLIENT)) {
            if (!descriptor.id().equals(id.capabilityId())) {
                continue;
            }
            return described(descriptor);
        }
        var connector = CapabilityCatalog.find(id.capabilityId()).orElse(null);
        if (connector != null && CapabilityKind.CONNECTOR.equals(connector.kind())
                && connector.attributes().has("taskEntry")
                && connector.attributes().get("taskEntry").getAsBoolean()) {
            return described(connector);
        }
        return failed("TARGET_UNKNOWN", DubheActionFailure.TARGET_UNKNOWN, "");
    }

    private static JsonObject described(CapabilityDescriptor descriptor) {
        JsonObject answer = new JsonObject();
        answer.addProperty("outcome", OUTCOME_OK);
        answer.addProperty("id", descriptor.id().toString());
        answer.addProperty("kind", descriptor.kind().value());
        answer.addProperty("description", descriptor.description());
        answer.add("attributes", descriptor.attributes());
        return answer;
    }

    private JsonObject listTargets() {
        com.google.gson.JsonArray targets = new com.google.gson.JsonArray();
        int count = 0;
        for (var descriptor : CapabilityExposure.exposed(effectiveCaller(), ToolSide.CLIENT)) {
            if ("dubhe".equals(descriptor.provider())) {
                continue;
            }
            if (count >= 50) {
                break;
            }
            targets.add(row(descriptor.id().toString(), descriptor.kind().value(), descriptor.description()));
            count++;
        }
        com.google.gson.JsonArray taskEntries = new com.google.gson.JsonArray();
        for (CapabilityDescriptor descriptor : CapabilityCatalog.list()) {
            if (!CapabilityKind.CONNECTOR.equals(descriptor.kind())) {
                continue;
            }
            if (!"dubhe".equals(descriptor.provider())) {
                if (descriptor.attributes().has("taskEntry")
                        && descriptor.attributes().get("taskEntry").getAsBoolean()) {
                    taskEntries.add(row(descriptor.id().toString(), "connector", descriptor.description()));
                }
            }
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("outcome", OUTCOME_OK);
        answer.add("targets", targets);
        answer.add("taskEntries", taskEntries);
        return answer;
    }

    private static JsonObject row(String id, String kind, String description) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("kind", kind);
        row.addProperty("description", description);
        return row;
    }

    private CompletableFuture<JsonObject> submit(IdRef id, String task) {
        JsonObject payload = new JsonObject();
        payload.addProperty("task", task);
        return CapabilityCatalog.invoke(id.capabilityId(), payload, effectiveCaller(), side())
                .handle((result, error) -> {
                    if (error != null || result == null) {
                        return failed("CALL_FAILED", DubheActionFailure.FAILED, "");
                    }
                    String taskId = text(result, "taskId");
                    if (taskId.isBlank()) {
                        return failed("NO_TASK_ID", DubheActionFailure.TARGET_UNKNOWN, "");
                    }
                    return ok(taskId, STATE_RUNNING);
                });
    }

    private CompletableFuture<JsonObject> progress(IdRef id, String taskId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("taskId", taskId);
        return TianshuTools.callJson(id.toolId(), payload, effectiveCaller())
                .handle((envelope, error) -> {
                    if (error != null || envelope == null) {
                        return failed("CALL_FAILED", DubheActionFailure.FAILED, "");
                    }
                    if (envelope.has("ok") && !envelope.get("ok").getAsBoolean()) {
                        JsonObject errorPayload = envelope.has("error") && envelope.get("error").isJsonObject()
                                ? envelope.getAsJsonObject("error") : envelope;
                        String code = text(errorPayload, "code");
                        String message = text(errorPayload, "message");
                        return failed("CALL_REFUSED", DubheActionFailure.fromToolError(code, message), message);
                    }
                    JsonElement result = envelope.has("result") ? envelope.get("result") : envelope;
                    String state = (result != null && result.isJsonObject()
                            ? text(result.getAsJsonObject(), "state") : "").toUpperCase(Locale.ROOT);
                    String progress = switch (state) {
                        case STATE_RUNNING, STATE_DONE, STATE_UNKNOWN -> state;
                        default -> STATE_UNKNOWN;
                    };
                    return ok(taskId, progress);
                });
    }

    private ToolCaller effectiveCaller() {
        ToolCaller effective = caller == null ? null : caller.get();
        return effective != null
                ? effective
                : ToolCaller.agent(DubheModule.MOD_ID, false, ToolAutonomy.READ_ONLY);
    }

    private static com.rheinmetal.tianshu.api.tool.ToolSide side() {
        return com.rheinmetal.tianshu.api.tool.ToolSide.CLIENT;
    }

    private static JsonObject ok(String taskId, String progress) {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", OUTCOME_OK);
        result.addProperty("taskId", taskId);
        result.addProperty("progress", progress);
        return result;
    }

    private static JsonObject needsConsent(ToolAutonomy tier) {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", "NEEDS_CONSENT");
        result.addProperty("tier", tier == null ? ToolAutonomy.READ_ONLY.name() : tier.name());
        return result;
    }

    private JsonObject failed(String reason) {
        return failed(reason, DubheActionFailure.FAILED, "");
    }

    private JsonObject failed(String reason, DubheActionFailure cause, String gameReason) {
        JsonObject result = new JsonObject();
        result.addProperty("outcome", OUTCOME_FAILED);
        result.addProperty("reason", reason);
        if (texts != null) {
            String block = texts.actionFailure(cause, gameReason);
            if (!block.isBlank()) {
                // The agent copies this into its report's block; wording stays in the resource catalogue.
                result.addProperty("block", block);
            }
        }
        return result;
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private static JsonObject property(String type, String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", type);
        property.addProperty("description", description);
        return property;
    }

    private static com.google.gson.JsonArray stringArray(String... values) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** {@code namespace:name}, the shape both capability ids and tool ids use. */
    private record IdRef(String namespace, String name) {

        static IdRef parse(String value) {
            int separator = value == null ? -1 : value.indexOf(':');
            if (separator <= 0 || separator >= value.length() - 1) {
                return null;
            }
            return new IdRef(value.substring(0, separator), value.substring(separator + 1));
        }

        CapabilityId capabilityId() {
            return CapabilityId.of(namespace, name);
        }

        ToolId toolId() {
            return ToolId.of(namespace, name);
        }
    }
}
