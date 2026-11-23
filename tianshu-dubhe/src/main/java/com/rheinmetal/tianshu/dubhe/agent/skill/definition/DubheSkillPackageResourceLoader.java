package com.rheinmetal.tianshu.dubhe.agent.skill.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.api.capability.skill.FrontMatter;
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
 * <p>解析复用舟楫的 {@link FrontMatter}（SKILL.md 的唯一实现）；本类只负责天枢自己的两件事：
 * 读 `skills/index.json` 清单、把包经 {@link DubheSkillPackageAdapter} 接成代理技能。</p>
 */
public final class DubheSkillPackageResourceLoader {

    private static final String ROOT = "/com/rheinmetal/tianshu/dubhe/skills/";
    private static final String INDEX = ROOT + "index.json";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_DESCRIPTION = 200;

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
        FrontMatter front = FrontMatter.parse(document);
        String declaredName = front.get("name");
        if (declaredName == null || !declaredName.strip().equals(name)) {
            throw new IllegalArgumentException("skill name '" + declaredName + "' must match its directory '" + name + "'");
        }
        String description = front.get("description");
        if (description == null || description.isBlank() || description.strip().length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("skill " + name + " needs a description of 1-" + MAX_DESCRIPTION + " characters");
        }
        String allowed = front.get("allowed-tools");
        List<String> allowedTools = allowed == null || allowed.isBlank()
                ? List.of() : List.of(allowed.strip().split("\\s+"));
        Map<String, String> contents = new LinkedHashMap<>();
        for (String reference : references) {
            contents.put(reference, readText(ROOT + name + "/" + reference));
        }
        return new DubheSkillDefinition(
                new DubheSkillMetadata(name, description.strip(), allowedTools),
                FrontMatter.body(document).strip(),
                contents
        );
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