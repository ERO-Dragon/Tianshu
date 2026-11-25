package com.rheinmetal.tianshu.api.capability;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolPermission;
import com.rheinmetal.tianshu.api.tool.ToolSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The consumer side of the capability table: which rows to show a model and how to render them.
 *
 * <p>This is a reference exposure, not the only one. An AI module may build native function-calling definitions,
 * an MCP list or a prompt block; this helper keeps the common case (a text catalogue plus pointer-style resources)
 * in one tested place.</p>
 */
public final class CapabilityExposure {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CapabilityExposure() {
    }

    /**
     * The rows a consumer on {@code side} may expose to {@code caller}: reachable, permitted, not a connector.
     *
     * <p>Switched-off tools are included, carrying {@code attributes.enabled = false}: the catalogue's other
     * discovery path already lists them so a model can say "this exists but is turned off", and two views of the
     * same table disagreeing about whether a tool exists is worse than a slightly longer list.</p>
     *
     * <p>Every attribute read is tolerant. {@code attributes} is an open bag by contract, so a provider can put
     * anything under {@code autonomy}; an unreadable value hides that one row instead of failing the call, or one
     * third-party descriptor breaks every consumer's capability view.</p>
     */
    public static List<CapabilityDescriptor> exposed(ToolCaller caller, ToolSide side) {
        return exposed(caller, side, CapabilityCatalog.snapshot().descriptors());
    }

    /**
     * The same rows, filtered from a table the caller already holds.
     *
     * <p>Takes the rows rather than reading the catalog again so a document can quote a revision and its content
     * together: reading the revision and then the table separately let another thread register something in between,
     * and the answer described a table that never existed at that revision.</p>
     */
    public static List<CapabilityDescriptor> exposed(ToolCaller caller, ToolSide side,
                                                     List<CapabilityDescriptor> table) {
        ToolAutonomy allowed = TianshuTools.autonomyFor(side, caller);
        List<CapabilityDescriptor> rows = new ArrayList<>();
        for (CapabilityDescriptor descriptor : table) {
            if (descriptor.kind().equals(CapabilityKind.CONNECTOR)) {
                continue;
            }
            if (!reachable(descriptor, side)
                    || !permitted(descriptor, caller)
                    || !autonomyAllows(descriptor, allowed)
                    || !sharedWithConsumer(descriptor, caller)) {
                continue;
            }
            rows.add(descriptor);
        }
        // A manual whose capabilities are all invisible here is not this caller's business either: it would describe
        // tools the caller cannot call. A general manual and one that declares no coverage are kept — the first is
        // about the format, the second does not claim any capability in particular.
        java.util.Set<CapabilityId> visibleCapabilities = new java.util.HashSet<>();
        for (CapabilityDescriptor row : rows) {
            if (!CapabilityKind.SKILL.equals(row.kind())) {
                visibleCapabilities.add(row.id());
            }
        }
        List<CapabilityDescriptor> kept = new ArrayList<>(rows.size());
        for (CapabilityDescriptor row : rows) {
            if (CapabilityKind.SKILL.equals(row.kind()) && !SkillCoverage.isGeneral(row)) {
                java.util.Set<CapabilityId> covered = SkillCoverage.coversOf(row);
                if (!covered.isEmpty() && covered.stream().noneMatch(visibleCapabilities::contains)) {
                    continue;
                }
            }
            kept.add(row);
        }
        return List.copyOf(kept);
    }

    private static boolean reachable(CapabilityDescriptor descriptor, ToolSide side) {
        return descriptor.side() != ToolSide.SERVER || side == ToolSide.SERVER || TianshuTools.serverAvailable();
    }

    private static boolean permitted(CapabilityDescriptor descriptor, ToolCaller caller) {
        return descriptor.permission() != ToolPermission.OPERATOR || caller.operator();
    }

    /**
     * Whether the provider shares this row with this consumer <em>and</em> the consumer accepts it.
     *
     * <p>Both halves are required. A provider opening a capability to a module that never asked for it would still be
     * invisible to that module (the subscription decides), and a module asking for something nobody shares gets nothing
     * (the declaration decides). That is what keeps "who opened what to whom" answerable.</p>
     *
     * <p>Player and body callers are not modules: sharing is a module-to-module arrangement, so it does not narrow a
     * human's own session — what bounds that is the tier and the permission, checked above. A module's own rows are
     * always visible to it, or a provider could not use what it published.</p>
     */
    private static boolean sharedWithConsumer(CapabilityDescriptor descriptor, ToolCaller caller) {
        String consumer = consumerModuleOf(caller);
        if (consumer == null) {
            return true;
        }
        if (consumer.equals(descriptor.provider())) {
            return true;
        }
        if (!CapabilitySharing.of(descriptor).permits(consumer)) {
            return false;
        }
        return CapabilitySubscriptions.ruleFor(consumer).accepts(descriptor);
    }

    /** The module id behind a caller, or null when the caller is a player or a body rather than a module. */
    private static String consumerModuleOf(ToolCaller caller) {
        return switch (caller.kind()) {
            case MOD, AGENT -> caller.id() == null || caller.id().isBlank() ? null : caller.id().trim();
            case PLAYER, BODY -> null;
        };
    }

    /**
     * Whether the tier governing this side permits this row.
     *
     * <p>An unreadable tier hides the row: failing closed keeps a typo in someone else's metadata from widening
     * what the model is offered.</p>
     */
    private static boolean autonomyAllows(CapabilityDescriptor descriptor, ToolAutonomy allowed) {
        if (!descriptor.kind().isTool()) {
            return true;
        }
        JsonElement declared = descriptor.attributes().get("autonomy");
        if (declared == null || !declared.isJsonPrimitive()) {
            return true;
        }
        ToolAutonomy required;
        try {
            required = ToolAutonomy.valueOf(declared.getAsString().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException unreadable) {
            return false;
        }
        return allowed.permits(required);
    }

    /** Renders the exposed rows as a prompt block; connectors never appear. */
    public static String describe(List<CapabilityDescriptor> descriptors) {
        final int maxRows = 200;
        final int maxChars = 8_000;
        StringBuilder text = new StringBuilder();
        int rows = 0;
        // Built from the rows this caller may see: a manual for a capability it cannot use is not its business.
        Map<CapabilityId, Set<CapabilityId>> coverage = SkillCoverage.index(descriptors);
        // A manual about calling conventions applies to every row, so it is stated once here instead of being repeated
        // next to each of them.
        List<CapabilityId> general = SkillCoverage.generalManuals(descriptors);
        if (!general.isEmpty()) {
            text.append("calling convention: ").append(String.join(", ", general.stream().map(Object::toString).toList()))
                    .append('\n');
        }
        // Capabilities first, manuals last: a skill is something to read, not something to call, and interleaving the two makes
        // the list read as if a manual were a capability.
        List<CapabilityDescriptor> ordered = new ArrayList<>(descriptors.stream()
                .filter(row -> !CapabilityKind.SKILL.equals(row.kind()))
                .toList());
        ordered.addAll(descriptors.stream().filter(row -> CapabilityKind.SKILL.equals(row.kind())).toList());
        for (CapabilityDescriptor descriptor : ordered) {
            if (descriptor.kind().equals(CapabilityKind.CONNECTOR)) {
                continue;
            }
            if (rows >= maxRows || text.length() >= maxChars) {
                text.append("… (").append(descriptors.size() - rows).append(" more; narrow the list)\n");
                break;
            }
            String name = descriptor.attributes().has("name")
                    ? descriptor.attributes().get("name").getAsString() : "";
            if (descriptor.kind().equals(CapabilityKind.SKILL) && !name.isBlank()) {
                text.append("- ").append(descriptor.id()).append(" — ").append(name).append(" — ")
                        .append(descriptor.description());
                text.append('\n');
                rows++;
                continue;
            }
            text.append("- ").append(descriptor.id())
                    .append(" [").append(descriptor.kind()).append('/').append(descriptor.binding()).append("]: ")
                    .append(descriptor.description());
            rows++;
            JsonElement schema = descriptor.attributes().get("argsSchema");
            if (schema != null && schema.isJsonObject()) {
                text.append(" args: ").append(GSON.toJson(schema));
            }
            // The manual, named where the model is already looking: a capability whose usage guidance exists must say so,
            // or the guidance is only found by accident.
            List<CapabilityId> manuals = new ArrayList<>();
            for (Map.Entry<CapabilityId, Set<CapabilityId>> skill : coverage.entrySet()) {
                if (skill.getValue().contains(descriptor.id())) {
                    manuals.add(skill.getKey());
                }
            }
            if (!manuals.isEmpty()) {
                text.append(" — how to use: ");
                for (int index = 0; index < manuals.size(); index++) {
                    text.append(index == 0 ? "" : ", ").append(manuals.get(index));
                }
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** The text of a resolved resource/prompt/skill result, or an empty string when it is not text. */
    public static String textOf(JsonObject envelope) {
        if (envelope == null || !envelope.has("ok") || !envelope.get("ok").getAsBoolean()) {
            return "";
        }
        JsonElement text = envelope.get("text");
        return text == null || !text.isJsonPrimitive() ? "" : text.getAsString();
    }
}
