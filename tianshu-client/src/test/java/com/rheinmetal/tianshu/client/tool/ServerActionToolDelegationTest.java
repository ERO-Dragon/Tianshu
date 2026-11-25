package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.server.ServerActionExecutionPort;
import com.rheinmetal.tianshu.api.server.ServerActionResult;
import com.rheinmetal.tianshu.api.tool.TianshuTools;
import com.rheinmetal.tianshu.api.tool.ToolAutonomy;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An action tool asks the execution port, passes the whitelisted arguments, and keeps the server's refusal code. */
class ServerActionToolDelegationTest {

    private static final ToolId XP = ToolId.of("minecraft", "server.xp");
    private static final ToolCaller FULL = ToolCaller.agent("lab", true, ToolAutonomy.FULL);

    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void register() {
        ServerActionTools.tools().forEach(TianshuTools::register);
        TianshuTools.setEnabled(XP, true);
    }

    @AfterEach
    void clean() {
        TianshuTools.clearEnabledOverride(XP);
        ServerActionTools.ids().forEach(TianshuTools::unregister);
        TianshuRuntimePorts.clear();
    }

    @Test
    void theActionReachesTheExecutorWithItsWhitelistedArguments() throws Exception {
        install((actor, action, params) -> {
            calls.add(action + " " + params);
            return ServerActionResult.ok("changed");
        });

        JsonObject args = new JsonObject();
        args.addProperty("levels", 3);
        JsonObject envelope = TianshuTools.callJson(XP, args, FULL, ToolSide.SERVER).get(2, TimeUnit.SECONDS);

        assertTrue(envelope.get("ok").getAsBoolean());
        assertEquals(List.of("xp {levels=3}"), calls);
    }

    @Test
    void theServerRefusalKeepsItsOwnCode() {
        install((actor, action, params) ->
                ServerActionResult.of(ServerActionResult.Status.DENIED, "operator permission is required"));

        JsonObject args = new JsonObject();
        args.addProperty("levels", 1);

        assertEquals("NO_PERMISSION", refusalCode(XP, args));
    }

    @Test
    void aDisabledActionIsReportedAsDisabledRatherThanFailed() {
        install((actor, action, params) ->
                ServerActionResult.of(ServerActionResult.Status.DISABLED, "action is disabled on this server"));

        JsonObject args = new JsonObject();
        args.addProperty("levels", 1);

        assertEquals("DISABLED", refusalCode(XP, args));
    }

    @Test
    void withNoExecutorTheToolSaysSoInsteadOfPretending() {
        TianshuRuntimePorts.clear();
        JsonObject args = new JsonObject();
        args.addProperty("levels", 1);

        assertEquals("UNSUPPORTED_SIDE", refusalCode(XP, args));
    }

    private void install(ServerActionExecutionPort port) {
        TianshuRuntimePorts.register(ServerActionExecutionPort.class, port);
    }

    private String refusalCode(ToolId id, JsonObject args) {
        try {
            JsonObject envelope = TianshuTools.callJson(id, args, FULL, ToolSide.SERVER).get(2, TimeUnit.SECONDS);
            if (envelope.has("ok") && envelope.get("ok").getAsBoolean()) {
                return "OK";
            }
            return envelope.has("code") ? envelope.get("code").getAsString() : String.valueOf(envelope);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ToolException toolFailure) {
                return toolFailure.code().name();
            }
            return String.valueOf(failure.getCause());
        } catch (Exception other) {
            return other.getClass().getSimpleName();
        }
    }

    /** A port that records calls and always refuses, used to prove the mapping without a server. */
    private static ServerActionExecutionPort refusing(ServerActionResult.Status status) {
        return (actor, action, params) -> ServerActionResult.of(status, "refused");
    }

    private static String unused(Map<String, String> params) {
        return params.toString();
    }
}