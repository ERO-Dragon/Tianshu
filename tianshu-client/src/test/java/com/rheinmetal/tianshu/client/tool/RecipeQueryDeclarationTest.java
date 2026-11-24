package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Recipe lookup must be a server declaration, not a client-local tool. */
class RecipeQueryDeclarationTest {

    @AfterEach
    void clean() {
        TianshuTools.unregister(RecipeQueryTool.ID);
        ServerToolDeclarations.clearForTests();
        ClientTools.unregisterForTests();
        TianshuRuntimePorts.clear();
    }

    @Test
    void theClientDeclaresTheRecipeToolAndOnlyActivatesItFromTheServerCatalogue() {
        ClientTools.register();

        assertNull(TianshuTools.find(RecipeQueryTool.ID), "recipe lookup must not be a local tool");

        ServerToolDeclarations.activate(List.of(new com.rheinmetal.tianshu.api.tool.ToolCatalogEntry(
                RecipeQueryTool.ID.toString(), true)));

        assertNotNull(TianshuTools.find(RecipeQueryTool.ID));
        assertEquals(ToolCategory.SERVER_READ, TianshuTools.find(RecipeQueryTool.ID).category());
    }
}
