package io.github.swing2mcp.agent;

import java.lang.instrument.Instrumentation;

/**
 * Entry point of the Java agent.
 *
 * Loaded either at startup ({@code -javaagent:swing2mcp-agent.jar[=options]}) or
 * into a running JVM through the Attach API. Options are comma separated
 * {@code key=value} pairs:
 *   dir   registration directory (default ~/.swing2mcp/jvms)
 *   port  HTTP port on 127.0.0.1 (default 0 = random)
 *
 * The agent never touches AWT here; Swing is only accessed when a request arrives.
 */
public final class AgentMain {

    private static volatile AgentServer server;

    private AgentMain() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args);
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args);
    }

    private static synchronized void start(String args) {
        if (server != null) {
            return;
        }
        try {
            server = AgentServer.start(AgentOptions.parse(args));
        } catch (Throwable t) {
            // Never break the host application because of the agent.
            System.err.println("[swing2mcp] agent failed to start: " + t);
        }
    }
}
