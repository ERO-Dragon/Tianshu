package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.client.host.settings.ClientAudioSettingsPort;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/** Moves one of the client's volume sliders. */
public final class AudioSetTool implements TianshuTool<AudioSetTool.Args, ClientAudioSettingsPort.Channel> {

    public static final ToolId ID = ToolId.of("tianshu", "client.audio.set");

    /** {@code volume} is percent, 0..100, the unit the options screen shows. */
    public record Args(String channel, int volume) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.LOCAL;
    }

    @Override
    public ToolAutonomy requiredAutonomy() {
        return ToolAutonomy.CONSERVATIVE;
    }

    @Override
    public String description() {
        return "Sets one of the client's volume channels to a percentage, 0..100. Use client.audio.list for the ids.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return ClientAudioSettingsPort.Channel.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("channel", BodyToolSchemas.text("Channel id from client.audio.list, for example master"));
        properties.add("volume", BodyToolSchemas.integer("Percentage, 0..100"));
        return BodyToolSchemas.object(properties, "channel", "volume");
    }

    @Override
    public CompletableFuture<ClientAudioSettingsPort.Channel> invoke(ToolContext context, Args args) {
        ClientAudioSettingsPort port = TianshuRuntimePorts.find(ClientAudioSettingsPort.class).orElse(null);
        if (port == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.UNSUPPORTED_SIDE,
                    "this platform does not expose its audio settings"));
        }
        if (args == null || args.channel() == null || args.channel().isBlank()) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "client.audio.set needs a channel id"));
        }
        int volume = args.volume();
        if (volume < 0 || volume > 100) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "volume is a percentage between 0 and 100"));
        }
        if (!port.setVolume(args.channel().trim(), volume)) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "unknown audio channel: " + args.channel() + "; client.audio.list names the ones this client has"));
        }
        // The port returning true means the slider moved, so the answer is what was asked for — re-reading a channel
        // list here would report whatever snapshot the platform happens to hold.
        String label = port.channels().stream()
                .filter(channel -> channel.id().equals(args.channel().trim()))
                .map(ClientAudioSettingsPort.Channel::label)
                .findFirst()
                .orElse("");
        return CompletableFuture.completedFuture(new ClientAudioSettingsPort.Channel(args.channel().trim(), label, volume));
    }
}