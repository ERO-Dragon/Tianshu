package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The cloud providers this build ships an endpoint profile for.
 *
 * <p>The profiles are data ({@code tts_cloud_providers.json}), not Java constants: an endpoint change is a
 * data edit, and the same file can later be replaced by a refresh instead of a code change. Labels stay
 * language keys rather than text, so no player-facing string is hardcoded in the data file either.</p>
 *
 * <p>Loading is strict on purpose. A malformed profile (duplicate ids, a default voice the provider does not
 * list, an empty voice list) fails when this class is first touched instead of opening the form on something
 * that cannot be requested. The checks live in {@link #parse(String)} so a test can exercise them against a
 * string, not only the bundled resource.</p>
 */
public final class TtsCloudProviders {

    /** The only provider this build ships today; the catalog is a starting point, not a limit. */
    public static final String EDGE = "edge";

    private static final String CATALOG_RESOURCE =
            "/com/rheinmetal/tianshu/function/echo/synthesis/cloud/tts_cloud_providers.json";

    private static final List<Provider> PROVIDERS = loadProviders();

    private TtsCloudProviders() {
    }

    /** One voice a provider can speak with. */
    public record Voice(String id, String labelKey) {
    }

    /** One provider's endpoint defaults plus the voices it lists. */
    public record Provider(String id, String labelKey, String defaultEndpoint, String defaultVoice,
                           List<Voice> voices) {

        public Optional<Voice> voice(String voiceId) {
            return voices.stream().filter(voice -> voice.id().equals(voiceId)).findFirst();
        }
    }

    public static List<Provider> all() {
        return PROVIDERS;
    }

    public static List<String> providerIds() {
        return PROVIDERS.stream().map(Provider::id).toList();
    }

    public static Provider defaultProvider() {
        return PROVIDERS.get(0);
    }

    public static Optional<Provider> byId(String providerId) {
        String wanted = providerId == null ? "" : providerId.trim();
        return PROVIDERS.stream().filter(provider -> provider.id().equals(wanted)).findFirst();
    }

    private static List<Provider> loadProviders() {
        try (InputStream stream = TtsCloudProviders.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing cloud provider catalog: " + CATALOG_RESOURCE);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<Provider> parse(JsonElement root) {
        if (root == null || !root.isJsonArray()) {
            throw new IllegalStateException("cloud provider catalog must be an array");
        }
        JsonArray array = root.getAsJsonArray();
        if (array.isEmpty()) {
            throw new IllegalStateException("cloud provider catalog is empty");
        }
        List<Provider> providers = new ArrayList<>(array.size());
        Set<String> ids = new HashSet<>();
        for (JsonElement element : array) {
            providers.add(parseProvider(element, ids));
        }
        return List.copyOf(providers);
    }

    private static Provider parseProvider(JsonElement element, Set<String> ids) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException("cloud provider entry must be an object");
        }
        JsonObject object = element.getAsJsonObject();
        String id = requiredString(object, "id");
        if (!ids.add(id)) {
            throw new IllegalStateException("duplicate cloud provider id: " + id);
        }
        String labelKey = requiredString(object, "labelKey");
        String defaultEndpoint = requiredString(object, "defaultEndpoint");
        String defaultVoice = requiredString(object, "defaultVoice");
        if (!defaultEndpoint.startsWith("ws://") && !defaultEndpoint.startsWith("wss://")) {
            throw new IllegalStateException("cloud provider endpoint must be a websocket URL: " + id);
        }
        JsonArray voiceArray = object.has("voices") && object.get("voices").isJsonArray()
                ? object.getAsJsonArray("voices")
                : new JsonArray();
        if (voiceArray.isEmpty()) {
            throw new IllegalStateException("cloud provider has no voices: " + id);
        }
        List<Voice> voices = new ArrayList<>(voiceArray.size());
        Set<String> voiceIds = new HashSet<>();
        for (JsonElement voiceElement : voiceArray) {
            if (voiceElement == null || !voiceElement.isJsonObject()) {
                throw new IllegalStateException("cloud voice entry must be an object: " + id);
            }
            JsonObject voiceObject = voiceElement.getAsJsonObject();
            String voiceId = requiredString(voiceObject, "id");
            if (!voiceIds.add(voiceId)) {
                throw new IllegalStateException("duplicate cloud voice id: " + id + "/" + voiceId);
            }
            voices.add(new Voice(voiceId, requiredString(voiceObject, "labelKey")));
        }
        if (!voiceIds.contains(defaultVoice)) {
            throw new IllegalStateException("default voice is not listed: " + id + "/" + defaultVoice);
        }
        return new Provider(id, labelKey, defaultEndpoint, defaultVoice, List.copyOf(voices));
    }

    private static String requiredString(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw new IllegalStateException("cloud provider entry is missing " + name);
        }
        String value = object.get(name).getAsString();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("cloud provider entry has a blank " + name);
        }
        return value.trim();
    }
}