package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud provider catalog is shipped data, so these checks pin both halves: the bundled file is usable,
 * and a malformed profile fails at load instead of opening a form that cannot be requested.
 */
class TtsCloudProvidersTest {

    @Test
    void bundledCatalogOffersUsableDefaults() {
        TtsCloudProviders.Provider edge = TtsCloudProviders.defaultProvider();

        assertEquals(TtsCloudProviders.EDGE, edge.id());
        assertTrue(edge.defaultEndpoint().startsWith("wss://"), "the shipped endpoint has to be websocket");
        assertTrue(edge.voice(edge.defaultVoice()).isPresent(),
                "the default voice must be one of the listed voices");
        assertTrue(edge.voices().size() >= 2, "a provider with one voice is not worth a picker");
        assertTrue(edge.labelKey().startsWith("tianshu.gui.tts."),
                "labels stay language keys, never player-facing text");
        assertTrue(TtsCloudProviders.byId(TtsCloudProviders.EDGE).isPresent());
        assertTrue(TtsCloudProviders.byId("no-such-provider").isEmpty());
    }

    @Test
    void duplicateProviderIdsAreRejected() {
        String json = "[" + provider("edge", "zh-CN-XiaoxiaoNeural", "zh-CN-XiaoxiaoNeural")
                + "," + provider("edge", "zh-CN-XiaoxiaoNeural", "zh-CN-XiaoxiaoNeural") + "]";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TtsCloudProviders.parse(JsonParser.parseString(json)));

        assertTrue(failure.getMessage().contains("duplicate cloud provider id"), failure.getMessage());
    }

    @Test
    void aDefaultVoiceOutsideTheListIsRejected() {
        String json = "[" + provider("edge", "zh-CN-XiaoxiaoNeural", "zh-CN-NotListedNeural") + "]";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TtsCloudProviders.parse(JsonParser.parseString(json)));

        assertTrue(failure.getMessage().contains("default voice is not listed"), failure.getMessage());
    }

    @Test
    void anEmptyVoiceListIsRejected() {
        String json = "[{\"id\":\"edge\",\"labelKey\":\"tianshu.gui.tts.cloud.provider.edge\","
                + "\"defaultEndpoint\":\"wss://example.invalid/v1\","
                + "\"defaultVoice\":\"zh-CN-XiaoxiaoNeural\",\"voices\":[]}]";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TtsCloudProviders.parse(JsonParser.parseString(json)));

        assertTrue(failure.getMessage().contains("no voices"), failure.getMessage());
    }

    @Test
    void aNonWebsocketEndpointIsRejected() {
        String json = "[{\"id\":\"edge\",\"labelKey\":\"tianshu.gui.tts.cloud.provider.edge\","
                + "\"defaultEndpoint\":\"https://example.invalid/v1\","
                + "\"defaultVoice\":\"zh-CN-XiaoxiaoNeural\","
                + "\"voices\":[{\"id\":\"zh-CN-XiaoxiaoNeural\","
                + "\"labelKey\":\"tianshu.gui.tts.cloud.voice.zh_cn_xiaoxiao\"}]}]";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TtsCloudProviders.parse(JsonParser.parseString(json)));

        assertTrue(failure.getMessage().contains("websocket URL"), failure.getMessage());
    }

    private static String provider(String id, String defaultVoice, String listedVoice) {
        return "{\"id\":\"" + id + "\",\"labelKey\":\"tianshu.gui.tts.cloud.provider." + id + "\","
                + "\"defaultEndpoint\":\"wss://example.invalid/v1\","
                + "\"defaultVoice\":\"" + defaultVoice + "\","
                + "\"voices\":[{\"id\":\"" + listedVoice + "\","
                + "\"labelKey\":\"tianshu.gui.tts.cloud.voice.voice\"}]}";
    }

    @Test
    void voicesAreListedOncePerProvider() {
        TtsCloudProviders.Provider edge = TtsCloudProviders.defaultProvider();
        List<String> ids = edge.voices().stream().map(TtsCloudProviders.Voice::id).toList();

        assertEquals(ids.size(), ids.stream().distinct().count(), "voice ids must be unique: " + ids);
    }
}