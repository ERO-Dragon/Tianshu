package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.TagKind;
import com.rheinmetal.tianshu.api.game.TagQuery;
import com.rheinmetal.tianshu.api.game.TagQueryPort;
import com.rheinmetal.tianshu.api.game.TagQueryResult;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The tag source behind {@code tianshu:tag.query}. Item and block tags are synced to the client, so the lookup is
 * local; the work runs on the client thread because the registries belong to the running game.
 */
public final class TagQuerySource implements TagQueryPort {

    @Override
    public CompletableFuture<TagQueryResult> query(TagQuery query) {
        CompletableFuture<TagQueryResult> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                result.complete(lookup(query));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static TagQueryResult lookup(TagQuery query) {
        String raw = query.itemId() != null ? query.itemId() : query.tagId();
        Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            return new TagQueryResult(List.of());
        }
        List<String> entries = query.itemId() != null ? tagsOf(query.kind(), id) : entriesOf(query.kind(), id);
        return new TagQueryResult(entries);
    }

    private static List<String> tagsOf(TagKind kind, Identifier id) {
        return kind == TagKind.BLOCK
                ? tagsOf(BuiltInRegistries.BLOCK, id)
                : tagsOf(BuiltInRegistries.ITEM, id);
    }

    private static List<String> entriesOf(TagKind kind, Identifier id) {
        return kind == TagKind.BLOCK
                ? entriesOf(BuiltInRegistries.BLOCK, id)
                : entriesOf(BuiltInRegistries.ITEM, id);
    }

    private static <T> List<String> tagsOf(Registry<T> registry, Identifier id) {
        return registry.get(id)
                .map(holder -> holder.tags().map(tag -> tag.location().toString()).sorted().toList())
                .orElse(List.of());
    }

    private static <T> List<String> entriesOf(Registry<T> registry, Identifier id) {
        TagKey<T> tag = TagKey.create(registry.key(), id);
        return registry.get(tag)
                .map(set -> set.stream()
                        .map(Holder::value)
                        .map(registry::getKey)
                        .filter(Objects::nonNull)
                        .map(Identifier::toString)
                        .sorted()
                        .toList())
                .orElse(List.of());
    }
}
