package com.rheinmetal.tianshu.function.auxilium.core.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rheinmetal.tianshu.api.tool.ToolSpec;

import java.util.List;

/**
 * Turns the registry catalogue into the instruction block the model sees.
 *
 * <p>Only enabled tools are listed. A disabled tool is not described, because a model that cannot use a capability
 * should not be taught that it exists — the caller is the one who can explain the switch to the player.</p>
 */
public final class AXToolCatalog {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    static final String PROTOCOL = """
            You can call at most one tool per answer. To call one, reply with a single JSON object and nothing else:
            {"tool": "<tool id>", "args": {<arguments>}}
            Otherwise reply normally. Never invent a tool id; use only the ids below.
            """;

    private AXToolCatalog() {
    }

    public static String describe(List<ToolSpec> catalogue) {
        StringBuilder text = new StringBuilder(PROTOCOL).append('\n');
        if (catalogue == null || catalogue.isEmpty()) {
            return text.append("(No tools are available.)").toString();
        }
        for (ToolSpec spec : catalogue) {
            if (!spec.enabled()) {
                continue;
            }
            text.append("- ").append(spec.id()).append(" [").append(spec.category().name().toLowerCase(java.util.Locale.ROOT))
                    .append("]: ").append(spec.description());
            if (spec.argsSchema() != null) {
                text.append(" args: ").append(GSON.toJson(spec.argsSchema()));
            }
            text.append('\n');
        }
        return text.toString();
    }
}
