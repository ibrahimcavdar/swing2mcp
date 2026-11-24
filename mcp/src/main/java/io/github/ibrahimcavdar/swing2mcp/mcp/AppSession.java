package io.github.ibrahimcavdar.swing2mcp.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

/**
 * One application with the agent, reached without MCP. Internal: this is what the test API in
 * {@code io.github.ibrahimcavdar.swing2mcp.test} is built on, and it may change without notice.
 */
public final class AppSession {

    private final Apps apps;
    private final AgentClient client;

    private AppSession(Apps apps, AgentClient client) {
        this.apps = apps;
        this.client = client;
    }

    private static Apps apps() {
        return new Apps(Paths.get(System.getProperty("user.home"), ".swing2mcp"));
    }

    /** Runs a shell command with the agent injected and waits for the agent to report. */
    public static AppSession launch(String command, File workingDirectory, long timeoutMs)
            throws IOException, InterruptedException {
        Apps apps = apps();
        apps.launch(command, workingDirectory == null ? null : workingDirectory.toPath(), Duration.ofMillis(timeoutMs));
        return new AppSession(apps, apps.current());
    }

    /** Connects to a running application, loading the agent into it first if it has none. */
    public static AppSession connect(long pid) throws IOException, InterruptedException {
        Apps apps = apps();
        apps.connectOrAttach(pid);
        return new AppSession(apps, apps.current());
    }

    public long pid() {
        return client.pid;
    }

    public String get(String path, Map<String, ?> params, long timeoutMs) throws IOException {
        return client.get(path, params, Duration.ofMillis(timeoutMs));
    }

    /** An action that ends the application (a click on "Exit") is not a failure: its answer says so. */
    public String post(String path, Map<String, ?> params, long timeoutMs) throws IOException {
        try {
            return client.post(path, params, Duration.ofMillis(timeoutMs));
        } catch (AgentClient.AppExited e) {
            return e.getMessage();
        }
    }

    public byte[] getBytes(String path, Map<String, ?> params, long timeoutMs) throws IOException {
        return client.getBytes(path, params, Duration.ofMillis(timeoutMs));
    }

    /** Ends the application's process at once. */
    public void stop() throws IOException {
        apps.stop(client.pid);
    }
}
