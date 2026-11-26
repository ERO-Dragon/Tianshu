package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.CommandResult;
import com.rheinmetal.tianshu.api.tool.BodyAutonomy;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Asks to run one command as an agent body; nothing runs until a human approves that exact line.
 *
 * <p>Body side of the split pair; {@link CommandRunTool} is the player side. The world operation itself lives in
 * {@link CommandRunOps}: this tool only adapts its arguments and calls in. The approval flow is the same on both sides:
 * a request comes back pending, an operator approves the token, and only the retry runs.</p>
 *
 * <p>It is {@code CHEAT} on the body scale because running commands is what a body cannot do by itself, so a
 * server that only lets bodies do survival work does not offer this tool at all.</p>
 */
public final class BodyCommandRunTool implements TianshuTool<BodyCommandRunTool.Args, CommandResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.command.run");

    /** {@code approval} is empty on the first call and carries the token on the retry. */
    public record Args(String body, String line, String approval) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BODY_WRITE;
    }

    @Override
    public BodyAutonomy requiredBodyAutonomy() {
        return BodyAutonomy.CHEAT;
    }

    @Override
    public String description() {
        return "Makes an agent body ask to run one Minecraft command. Nothing runs on the first call: it returns an "
                + "approval token that a server operator confirms with /tianshu approve <token>, and only then does "
                + "the retry execute it.";
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
        properties.add("body", BodyToolSchemas.body());
        properties.add("line", BodyToolSchemas.text("The command line, without the leading slash"));
        properties.add("approval", BodyToolSchemas.text("The token an operator approved; omit on the first call"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<CommandResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return CommandRunOps.run(scope.actor(), args.line(), args.approval());
    }
}
