package io.github.swing2mcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * swing2mcp MCP server (stdio). Register with Claude Code:
 *
 *   claude mcp add swing -- java -jar swing2mcp.jar
 *
 * stdout carries the MCP protocol, so nothing else may be printed there.
 */
public final class Main {

    static final String VERSION = "0.1.0";

    private static final String INSTRUCTIONS = """
            swing2mcp lets you see and operate Java Swing desktop applications.
            Typical flow: launch_app (or list_apps/connect_app for one already running) -> wait_for -> \
            snapshot -> click / type_text / select_option using [ref=eN] values from the snapshot. \
            Actions return the updated snapshot. A modal dialog blocks other windows until it is closed. \
            Use screenshot to check visual layout or custom-painted components.""";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        McpJsonMapper json = McpJsonDefaults.getMapper();
        Apps apps = new Apps(Path.of(System.getProperty("user.home"), ".swing2mcp"));
        Tools tools = new Tools(apps, json);

        CountDownLatch clientGone = new CountDownLatch(1);
        InputStream stdin = new FilterInputStream(System.in) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n < 0) {
                    clientGone.countDown();
                }
                return n;
            }
        };

        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(json, stdin, System.out))
                .serverInfo("swing2mcp", VERSION)
                .instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools.specifications())
                .build();
        // The client closing stdin means it is done with us.
        clientGone.await();
        server.closeGracefully();
        System.exit(0);
    }
}
