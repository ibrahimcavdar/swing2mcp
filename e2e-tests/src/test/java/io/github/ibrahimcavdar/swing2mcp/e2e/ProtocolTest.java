package io.github.ibrahimcavdar.swing2mcp.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.ibrahimcavdar.swing2mcp.e2e.McpSession.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The server implements the MCP protocol itself, so the protocol is tested here: what a client may send
 * besides well-formed tool calls. No application is needed, and no display.
 */
class ProtocolTest {

    private McpSession mcp;

    @BeforeEach
    void startServer() throws Exception {
        mcp = TestEnvironment.startServer("protocol");
    }

    @AfterEach
    void stopServer() throws Exception {
        mcp.close();
    }

    @Test
    void agreesOnTheClientsProtocolRevisionOrOffersItsNewest() throws Exception {
        assertEquals("2024-11-05", initialize("2024-11-05").get("protocolVersion").getAsString());
        assertEquals("2025-06-18", initialize("2025-06-18").get("protocolVersion").getAsString());
        assertEquals("2025-06-18", initialize("2099-01-01").get("protocolVersion").getAsString());
    }

    @Test
    void saysWhatItIs() throws Exception {
        JsonObject result = initialize("2025-06-18");
        assertEquals("swing2mcp", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertFalse(result.getAsJsonObject("serverInfo").get("version").getAsString().isEmpty());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
        assertTrue(result.get("instructions").getAsString().contains("snapshot"));
    }

    @Test
    void answersPing() throws Exception {
        JsonObject reply = mcp.reply("ping", new JsonObject(), 30);
        assertTrue(reply.has("result"), reply.toString());
    }

    @Test
    void refusesWhatItDoesNotImplement() throws Exception {
        JsonObject reply = mcp.reply("resources/list", new JsonObject(), 30);
        assertEquals(-32601, reply.getAsJsonObject("error").get("code").getAsInt(), reply.toString());
    }

    @Test
    void refusesAnUnknownTool() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", "no_such_tool");
        JsonObject reply = mcp.reply("tools/call", params, 30);
        assertEquals(-32602, reply.getAsJsonObject("error").get("code").getAsInt(), reply.toString());
    }

    @Test
    void survivesALineThatIsNotJson() throws Exception {
        JsonObject reply = mcp.replyToLine("{ this is not json");
        assertEquals(-32700, reply.getAsJsonObject("error").get("code").getAsInt(), reply.toString());
        assertTrue(mcp.reply("ping", new JsonObject(), 30).has("result"), "the server must keep working");
    }

    @Test
    void reportsAMissingArgumentAsAToolError() throws Exception {
        Result result = mcp.call("click");
        assertTrue(result.error());
        assertEquals("Missing argument: ref", result.text());
    }

    @Test
    void reportsThatNoApplicationIsConnected() throws Exception {
        // Unless another application with the agent happens to run on this machine.
        Result result = mcp.call("snapshot");
        if (result.error()) {
            assertTrue(result.text().contains("launch_app") || result.text().contains("connect_app"), result.text());
        }
    }

    @Test
    void keepsNonAsciiTextIntact() throws Exception {
        // The text comes back inside the error message, through JSON in both directions.
        JsonObject params = new JsonObject();
        params.addProperty("name", "İzmir \"Ayşe\" \\ ✓ \n satır");
        JsonObject reply = mcp.reply("tools/call", params, 30);
        assertEquals("Unknown tool: İzmir \"Ayşe\" \\ ✓ \n satır",
                reply.getAsJsonObject("error").get("message").getAsString());
    }

    private JsonObject initialize(String protocolVersion) throws Exception {
        JsonObject client = new JsonObject();
        client.addProperty("name", "protocol-test");
        client.addProperty("version", "0");
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", protocolVersion);
        params.add("capabilities", new JsonObject());
        params.add("clientInfo", client);
        return mcp.reply("initialize", params, 30).getAsJsonObject("result");
    }
}
