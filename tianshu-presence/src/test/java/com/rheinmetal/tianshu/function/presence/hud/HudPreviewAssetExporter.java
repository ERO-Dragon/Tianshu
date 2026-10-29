package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.function.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.function.presence.model.PresencePrimaryState;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the values the web HUD preview needs into {@code tools/hud-preview/index.html}.
 *
 * <p>The preview is a browser port of the HUD, so the values it draws with must not be typed in twice. This exporter
 * reads the real model — state parameters, layout defaults, transition length, colours and limits — plus the shader
 * sources and the status text keys, and rewrites the two generated regions of the preview page. The shaders are copied
 * verbatim; the page only prepends the GLSL ES header when it compiles them.</p>
 *
 * <p>It lives in the test source set on purpose: it is a development tool, not part of the shipped mod, and it needs
 * package access to the renderers' private constants. Reflection is used there instead of widening the production API,
 * so the exported colours and opacities are the ones the renderer actually uses.</p>
 *
 * <p>Regenerate with {@code gradlew :tianshu-presence:exportHudPreviewAssets}; a normal test run fails when the page
 * is stale, so a HUD change cannot leave the preview quietly lying.</p>
 */
public final class HudPreviewAssetExporter {

    private static final Path TOOL_DIR = Path.of("tools", "hud-preview");
    private static final Path PAGE = TOOL_DIR.resolve("index.html");
    private static final Path SHADER_DIR =
            Path.of("src", "main", "resources", "assets", "tianshu_presence", "shaders", "core");
    private static final Path LANG_DIR = Path.of("src", "main", "lang");

    private static final String MODEL_BLOCK = "presence-hud-model";

    private static final Map<String, String> SHADER_BLOCKS = shaderBlocks();

    private static final List<String> STATUS_SUFFIXES = List.of(
            "idle", "loading", "thinking", "responding", "error", "reconnecting", "listening");

    private static final List<Class<?>> CONSTANT_SOURCES = List.of(
            PresenceHudSettings.class,
            PresenceHudLayout.class,
            PresenceHudShapes.class,
            PresenceIconElementController.class,
            PresenceIconElementRenderer.class,
            PresenceStatusTextElementRenderer.class,
            PresencePartnerIconRenderer.class
    );

    private HudPreviewAssetExporter() {
    }

    public static void main(String[] args) throws IOException {
        update(PAGE);
        System.out.println("HUD preview assets written to " + PAGE.toAbsolutePath());
    }

    /** Rewrites the generated regions of {@code page} from the current HUD sources. */
    public static void update(Path page) throws IOException {
        String html = Files.readString(page, StandardCharsets.UTF_8);
        html = replaceBlock(html, MODEL_BLOCK, modelJson());
        for (Map.Entry<String, String> entry : SHADER_BLOCKS.entrySet()) {
            html = replaceBlock(html, entry.getValue(), shaderSource(entry.getKey()));
        }
        Files.writeString(page, html, StandardCharsets.UTF_8);
    }

    /** Every generated region that differs from what the HUD sources would produce; empty means fresh. */
    public static List<String> verify(Path page) throws IOException {
        String html = Files.readString(page, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        compareBlock(html, MODEL_BLOCK, modelJson(), problems);
        for (Map.Entry<String, String> entry : SHADER_BLOCKS.entrySet()) {
            compareBlock(html, entry.getValue(), shaderSource(entry.getKey()), problems);
        }
        return problems;
    }

    private static void compareBlock(String html, String blockId, String expected, List<String> problems) {
        String actual = block(html, blockId);
        if (actual == null) {
            problems.add("missing generated block: " + blockId);
            return;
        }
        if (!normalize(actual).equals(normalize(expected))) {
            problems.add("stale generated block: " + blockId);
        }
    }

    private static String block(String html, String blockId) {
        Matcher matcher = blockPattern(blockId).matcher(html);
        return matcher.find() ? matcher.group(2) : null;
    }

    private static String replaceBlock(String html, String blockId, String content) {
        Matcher matcher = blockPattern(blockId).matcher(html);
        if (!matcher.find()) {
            throw new IllegalStateException("generated block not found: " + blockId);
        }
        String eol = html.contains("\r\n") ? "\r\n" : "\n";
        String body = eol + normalize(content).replace("\n", eol) + eol;
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + body + matcher.group(3)));
    }

    private static Pattern blockPattern(String blockId) {
        return Pattern.compile(
                "(<script[^>]*\\bid=\"" + Pattern.quote(blockId) + "\"[^>]*>)(.*?)(</script>)",
                Pattern.DOTALL);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    private static Map<String, String> shaderBlocks() {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("presence_icon.vsh", "shader-presence-icon-vsh");
        blocks.put("presence_loading.fsh", "shader-presence-loading-fsh");
        blocks.put("presence_preset_one.fsh", "shader-presence-preset-one-fsh");
        blocks.put("presence_preset_two.fsh", "shader-presence-preset-two-fsh");
        return blocks;
    }

    private static String shaderSource(String name) {
        try {
            return Files.readString(SHADER_DIR.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read shader " + name, failure);
        }
    }

    // ------------------------------------------------------------------ model

    static String modelJson() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("generatedBy", "HudPreviewAssetExporter");
        model.put("moduleId", PresenceProtocolAdapter.MODULE_ID);
        model.put("textKeyPrefix", "tianshu.presence.status.");
        model.put("primaryStates", names(PresencePrimaryState.values()));
        model.put("visualStates", names(PresenceHudVisualState.values()));
        model.put("visualStateOf", visualStateOf());
        model.put("visualParameters", visualParameters());
        model.put("texts", texts());
        model.put("constants", constants());
        return json(model);
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static Map<String, Object> visualStateOf() {
        Map<String, Object> mapping = new TreeMap<>();
        for (PresencePrimaryState state : PresencePrimaryState.values()) {
            mapping.put(state.name(), PresenceHudVisualState.from(state).name());
        }
        return new LinkedHashMap<>(mapping);
    }

    private static Map<String, Object> visualParameters() {
        PresenceHudLayout layout = PresenceHudLayout.resolve(new PresenceHudSettings() {
        }, 960, 540);
        Map<String, Object> states = new LinkedHashMap<>();
        for (PresenceHudVisualState state : PresenceHudVisualState.values()) {
            List<Object> byListening = new ArrayList<>(2);
            for (boolean listening : new boolean[] {false, true}) {
                PresenceHudVisualParameters parameters = PresenceHudVisualParameters.forState(state, listening, layout);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("intensity", parameters.intensity());
                values.put("speed", parameters.speed());
                values.put("convergence", parameters.convergence());
                values.put("pulse", parameters.pulse());
                values.put("loadingBlend", parameters.loadingBlend());
                byListening.add(values);
            }
            states.put(state.name(), byListening);
        }
        return states;
    }

    private static Map<String, Object> texts() {
        Map<String, Object> languages = new LinkedHashMap<>();
        languages.put("zh_cn", language(LANG_DIR.resolve("zh_cn.json")));
        languages.put("en_us", language(LANG_DIR.resolve("en_us.json")));
        return languages;
    }

    private static Map<String, Object> language(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read language shard " + file, failure);
        }
        Pattern pattern = Pattern.compile(
                "\"tianshu\\.presence\\.status\\.([a-z_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(text);
        Map<String, String> values = new TreeMap<>();
        while (matcher.find()) {
            values.put(matcher.group(1), unescape(matcher.group(2)));
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String suffix : STATUS_SUFFIXES) {
            if (!values.containsKey(suffix)) {
                throw new IllegalStateException("missing status key " + suffix + " in " + file);
            }
            ordered.put(suffix, values.get(suffix));
        }
        return ordered;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Map<String, Object> constants() {
        Map<String, Object> byClass = new LinkedHashMap<>();
        for (Class<?> type : CONSTANT_SOURCES) {
            Map<String, Object> fields = new TreeMap<>();
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || field.isSynthetic()) {
                    continue;
                }
                Object value = readConstant(field);
                if (value != null) {
                    fields.put(field.getName(), value);
                }
            }
            byClass.put(type.getSimpleName(), new LinkedHashMap<>(fields));
        }
        return byClass;
    }

    private static Object readConstant(Field field) {
        Class<?> type = field.getType();
        if (type != int.class && type != long.class && type != float.class && type != double.class
                && type != String.class) {
            return null;
        }
        try {
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Float number) {
                return number;
            }
            if (value instanceof Double number) {
                return number;
            }
            if (value instanceof Integer number) {
                return number;
            }
            if (value instanceof Long number) {
                return number;
            }
            return value;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot read constant " + field, failure);
        }
    }

    // ------------------------------------------------------------------ json

    private static String json(Object value) {
        StringBuilder out = new StringBuilder();
        writeJson(out, value, 0);
        out.append('\n');
        return out.toString();
    }

    private static void writeJson(StringBuilder out, Object value, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            out.append(quote(text));
        } else if (value instanceof Float number) {
            out.append(number.toString());
        } else if (value instanceof Double number) {
            out.append(number.toString());
        } else if (value instanceof Number number) {
            out.append(number.toString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(out, list, indent);
        } else {
            throw new IllegalArgumentException("unsupported JSON value: " + value.getClass());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(out, indent + 1);
            out.append(quote(String.valueOf(entry.getKey()))).append(": ");
            writeJson(out, entry.getValue(), indent + 1);
            out.append(++index < map.size() ? ",\n" : "\n");
        }
        indent(out, indent);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int indent) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int index = 0; index < list.size(); index++) {
            indent(out, indent + 1);
            writeJson(out, list.get(index), indent + 1);
            out.append(index + 1 < list.size() ? ",\n" : "\n");
        }
        indent(out, indent);
        out.append(']');
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(level));
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
