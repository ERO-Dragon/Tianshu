package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityDescriptor;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published contract of the action tools: the cheat-grade ones are FULL-tier world changes that ship off, and
 * speaking is the local, middle-tier exception.
 */
class ServerActionToolsTest {

    @BeforeEach
    void register() {
        ServerActionTools.tools().forEach(TianshuTools::register);
        TianshuTools.register(new SayTool());
    }

    @AfterEach
    void unregister() {
        ServerActionTools.ids().forEach(TianshuTools::unregister);
        TianshuTools.unregister(SayTool.ID);
    }

    @Test
    void everyActionIsAFullTierWorldChangeThatShipsOff() {
        for (ToolId id : ServerActionTools.ids()) {
            CapabilityDescriptor row = CapabilityCatalog.find(CapabilityId.parse(id.toString())).orElse(null);
            assertNotNull(row, id + " is not published in the capability table");
            assertEquals("FULL", row.attributes().get("autonomy").getAsString(), id + " must require the FULL tier");
            assertEquals("server_write", row.attributes().get("category").getAsString(), id + " must be a world change");
            assertFalse(row.attributes().get("enabled").getAsBoolean(), id + " ships switched off");
        }
    }

    @Test
    void speakingIsAMiddleTierLocalToolThatShipsOn() {
        CapabilityDescriptor row = CapabilityCatalog.find(CapabilityId.parse(SayTool.ID.toString())).orElse(null);

        assertNotNull(row, "speaking is published like any other tool");
        assertEquals("CONSERVATIVE", row.attributes().get("autonomy").getAsString(),
                "chat grants nothing, so it sits in the middle tier");
        assertEquals("local", row.attributes().get("category").getAsString(),
                "chat is what any player may do, so it is not a server world change");
        assertTrue(row.attributes().get("enabled").getAsBoolean(), "chat ships on");
    }

    @Test
    void theWhitelistCoversEveryActionTheAdaptersExecute() {
        // One row per adapter branch: a missing id here means the model cannot ask for it at all.
        assertEquals(14, ServerActionTools.ids().size());
    }
}