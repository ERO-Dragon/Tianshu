package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import com.rheinmetal.tianshu.client.host.settings.ClientAudioSettingsPort;
import com.rheinmetal.tianshu.client.host.settings.ClientPackSettingsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client preferences are local: listed, settable, and refused clearly when the id is wrong.
 *
 * <p>Both domains report a wrong id as {@code INVALID_ARGS} rather than pretending the change landed — a model that
 * asked for a channel this client does not have must be able to tell.</p>
 */
class ClientSettingsToolsTest {

    private final List<String> changes = new ArrayList<>();

    @BeforeEach
    void register() {
        TianshuTools.registerBuiltin(new AudioListTool());
        TianshuTools.registerBuiltin(new AudioSetTool());
        TianshuTools.registerBuiltin(new PackListTool());
        TianshuTools.registerBuiltin(new PackSetTool());
    }

    @AfterEach
    void clean() {
        TianshuTools.unregister(AudioListTool.ID);
        TianshuTools.unregister(AudioSetTool.ID);
        TianshuTools.unregister(PackListTool.ID);
        TianshuTools.unregister(PackSetTool.ID);
        TianshuRuntimePorts.clear();
    }

    @Test
    void everyVolumeChannelIsListedWithItsPercentage() throws Exception {
        installAudio();

        JsonObject envelope = call(AudioListTool.ID, new JsonObject());

        assertTrue(envelope.get("ok").getAsBoolean(), envelope.toString());
        var result = envelope.getAsJsonArray("result");
        assertEquals(2, result.size());
        assertEquals("master", result.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(80, result.get(0).getAsJsonObject().get("percent").getAsInt());
    }

    @Test
    void aKnownChannelIsChangedAndReporatedBack() throws Exception {
        installAudio();

        JsonObject args = new JsonObject();
        args.addProperty("channel", "weather");
        args.addProperty("volume", 35);
        JsonObject envelope = call(AudioSetTool.ID, args);

        assertTrue(envelope.get("ok").getAsBoolean(), envelope.toString());
        assertEquals(List.of("weather=35"), changes);
        assertEquals(35, envelope.getAsJsonObject("result").get("percent").getAsInt());
    }

    @Test
    void anUnknownChannelIsRefusedWithACodeTheModelCanActOn() {
        installAudio();

        JsonObject args = new JsonObject();
        args.addProperty("channel", "subwoofer");
        args.addProperty("volume", 10);

        assertTrue(refusal(AudioSetTool.ID, args).contains("INVALID_ARGS"));
        assertTrue(changes.isEmpty(), "nothing should have been changed");
    }

    @Test
    void aVolumeOutsideTheSliderIsRefusedBeforeThePort() {
        installAudio();

        JsonObject args = new JsonObject();
        args.addProperty("channel", "master");
        args.addProperty("volume", 140);

        assertTrue(refusal(AudioSetTool.ID, args).contains("INVALID_ARGS"));
        assertTrue(changes.isEmpty());
    }

    @Test
    void packsAreListedAndARequiredOneCannotBeSwitchedOff() throws Exception {
        installPacks();

        JsonObject listed = call(PackListTool.ID, new JsonObject());
        assertEquals(2, listed.getAsJsonArray("result").size());

        JsonObject args = new JsonObject();
        args.addProperty("pack", "vanilla");
        args.addProperty("enabled", false);

        assertTrue(refusal(PackSetTool.ID, args).contains("INVALID_ARGS"),
                "a required pack must be refused, not quietly ignored");
        assertTrue(changes.isEmpty());
    }

    private void installAudio() {
        TianshuRuntimePorts.register(ClientAudioSettingsPort.class, new ClientAudioSettingsPort() {
            @Override
            public List<Channel> channels() {
                return List.of(new Channel("master", "Master", 80), new Channel("weather", "Weather", 100));
            }

            @Override
            public boolean setVolume(String channel, int percent) {
                if (!channel.equals("master") && !channel.equals("weather")) {
                    return false;
                }
                changes.add(channel + "=" + percent);
                return true;
            }
        });
    }

    private void installPacks() {
        TianshuRuntimePorts.register(ClientPackSettingsPort.class, new ClientPackSettingsPort() {
            @Override
            public List<Pack> packs() {
                return List.of(new Pack("vanilla", "Default", true, true),
                        new Pack("file/pack.zip", "Pack", false, false));
            }

            @Override
            public boolean setEnabled(String id, boolean enabled) {
                if (id.equals("vanilla")) {
                    return false;
                }
                changes.add(id + "=" + enabled);
                return true;
            }
        });
    }

    private JsonObject call(com.rheinmetal.tianshu.api.tool.ToolId tool, JsonObject args) throws Exception {
        return TianshuTools.callJson(tool, args, ToolCaller.agent(true), ToolSide.CLIENT)
                .get(2, TimeUnit.SECONDS);
    }

    private String refusal(com.rheinmetal.tianshu.api.tool.ToolId tool, JsonObject args) {
        try {
            return call(tool, args).toString();
        } catch (ExecutionException failure) {
            return String.valueOf(failure.getCause());
        } catch (Exception other) {
            return other.getClass().getSimpleName();
        }
    }
}