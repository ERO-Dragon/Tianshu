package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.dubhe.agent.skill.DubheSkill;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从资源目录加载内置技能包（classpath）。
 *
 * <p>jar 里不能列目录，所以用一份清单声明有哪些包、每个包有哪些 references：</p>
 * <pre>
 * skills/index.json
 * { "schemaVersion": 1, "skills": [ { "name": "work", "references": ["references/tiers.md"] } ] }
 * </pre>
 *
 * <p>加载后经 {@link DubheSkillPackageAdapter} 变成可注册的技能：目录只给元数据，调用才读正文/reference。</p>
 */
public final class DubheSkillPackageResourceLoader {

    private static final String ROOT = "/com/rheinmetal/tianshu/dubhe/skills/";
    private static final String INDEX = ROOT + "index.json";
    private static final int SCHEMA_VERSION = 1;

    private DubheSkillPackageResourceLoader() {
    }

    public static List<DubheSkill> loadBuiltin() {
        List<DubheSkill> skills = new ArrayList<>();
        for (DubheSkillDefinition definition : loadBuiltinDefinitions()) {
            skills.add(DubheSkillPackageAdapter.toSkill(definition));
        }
        return List.copyOf(skills);
    }

    public static List<DubheSkillDefinition> loadBuiltinDefinitions() {
        JsonObject index = readJson(INDEX);
        int version = index.has("schemaVersion") ? index.get("schemaVersion").getAsInt() : -1;
        if (version != SCHEMA_VERSION) {
            throw new IllegalStateException("skill index schema is " + version + ", expected " + SCHEMA_VERSION);
        }
        JsonArray entries = index.has("skills") && index.get("skills").isJsonArray()
                ? index.getAsJsonArray("skills") : new JsonArray();
        List<DubheSkillDefinition> definitions = new ArrayList<>();
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            String name = entry.get("name").getAsString();
            List<String> references = new ArrayList<>();
            if (entry.has("references") && entry.get("references").isJsonArray()) {
                for (JsonElement reference : entry.getAsJsonArray("references")) {
                    references.add(reference.getAsString());
                }
            }
            definitions.add(load(name, references));
        }
        return List.copyOf(definitions);
    }

    public static DubheSkillDefinition load(String name, List<String> references) {
        String document = readText(ROOT + name + "/SKILL.md");
        Map<String, String> contents = new LinkedHashMap<>();
        for (String reference : references) {
            String path = ROOT + name + "/" + reference;
            contents.put(reference, readText(path));
        }
        return DubheSkillDefinitionLoader.loadText(document, name, contents);
    }

    private static JsonObject readJson(String resource) {
        try (InputStream stream = DubheSkillPackageResourceLoader.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing skill index: " + resource);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IllegalStateException broken) {
            throw broken;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read " + resource, failure);
        }
    }

    private static String readText(String resource) {
        try (InputStream stream = DubheSkillPackageResourceLoader.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing skill resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IllegalStateException broken) {
            throw broken;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read " + resource, failure);
        }
    }
}