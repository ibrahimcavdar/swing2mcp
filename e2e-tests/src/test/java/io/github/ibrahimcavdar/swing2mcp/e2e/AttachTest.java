package io.github.ibrahimcavdar.swing2mcp.e2e;

import static io.github.ibrahimcavdar.swing2mcp.e2e.McpSession.ok;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ibrahimcavdar.swing2mcp.e2e.McpSession.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/** Connecting to an application that was started without swing2mcp. */
class AttachTest {

    private McpSession mcp;
    private Process application;

    @BeforeEach
    void startApplicationWithoutAgent() throws Exception {
        TestEnvironment.requireDisplay();
        assumeTrue(ModuleLayer.boot().findModule("jdk.attach").isPresent(), "attach needs a JDK, not a JRE");
        application = new ProcessBuilder(TestEnvironment.java(), "-jar", "target/demo-app.jar")
                .directory(TestEnvironment.demoDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(Path.of(System.getProperty("swing2mcp.workDir"), "attach-app.log").toFile())
                .start();
        mcp = TestEnvironment.startServer("attach");
    }

    @AfterEach
    void stopEverything() throws Exception {
        if (application != null) {
            application.destroyForcibly();
        }
        if (mcp != null) {
            mcp.close();
        }
    }

    @Test
    void loadsTheAgentIntoARunningApplication() throws Exception {
        long pid = application.pid();
        Result listed = ok(mcp.call("list_apps"));
        String others = listed.text().substring(Math.max(0, listed.text().indexOf("no agent yet")));
        assertTrue(others.contains("pid " + pid + " "), "pid " + pid + " should be listed without agent:\n"
                + listed.text());

        Result connected = ok(mcp.call("connect_app", "pid", pid));
        assertTrue(connected.text().contains("Loaded the agent into running pid " + pid), connected.text());

        Result login = ok(mcp.call("wait_for", "text", "Log In", "timeoutMs", 30000));
        assertTrue(login.text().contains("dialog \"Login\""), login.text());
    }
}
