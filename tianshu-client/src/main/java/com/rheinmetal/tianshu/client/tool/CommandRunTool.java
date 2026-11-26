package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.CommandResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Asks to run one command as you; nothing runs until a human approves that exact line.
 *
 * <p>Player side of the split pair; {@link BodyCommandRunTool} is the body side. The world operation itself lives in
 * {@link CommandRunOps}: this tool only adapts its arguments and calls in.</p>
 *
 * <p>The first call comes back pending with a token an operator approves with {@code /tianshu approve <token>}; the retry
 * carries the token and runs once. That is the whole design: a switch could be flipped by the caller, an approval
 * cannot.</p>
 *
 * <p>It is off by default and needs {@code FULL} player autonomy, because running commands is not something a survival
 * player does by hand. Requesting is allowed to anyone; executing needs an operator, which is the split this tool
 * exists to express.</p>
 */
public final class CommandRunTool implements TianshuTool<CommandRunTool.Args, CommandResult> {

    /** {@code approval} is empty on the first call and carries the token on the retry. */
    public record Args(String line, String approval) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "command.run");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_WRITE;
    }

    @Override
    public ToolPolicy policy() {
        // Requesting is not privileged; approving is. So the tool is open to callers and off by default, rather than
        // operator-only — an operator-only tool could never be asked for in the first place.
        return ToolPolicy.forCategory(ToolCategory.SERVER_WRITE)
                .withPermission(com.rheinmetal.tianshu.api.tool.ToolPermission.EVERYONE)
                .withEnabledByDefault(false);
    }

    @Override
    public com.rheinmetal.tianshu.api.tool.ToolAutonomy requiredAutonomy() {
        return com.rheinmetal.tianshu.api.tool.ToolAutonomy.FULL;
    }

    @Override
    public String description() {
        return "Asks to run one Minecraft command as you. Nothing runs on the first call: it returns "
                + "an approval token that a server operator confirms with /tianshu approve <token>, and only then does "
                + "the retry execute it. One token runs one command once.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return CommandResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("line", BodyToolSchemas.text("The command line, without the leading slash"));
        properties.add("approval", BodyToolSchemas.text("The token an operator approved; omit on the first call"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<CommandResult> invoke(ToolContext context, Args args) {
        if (args == null || args.line() == null || args.line().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "command.run needs a command line"));
        }
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return CommandRunOps.run(scope.actor(), args.line(), args.approval());
    }
}