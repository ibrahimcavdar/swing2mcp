package io.github.swing2mcp.e2e;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Where the built jars are, and how to start things. The paths come from the surefire configuration. */
final class TestEnvironment {

    private TestEnvironment() {
    }

    /** Swing needs a display; on a headless machine the tests are skipped (CI uses xvfb-run). */
    static void requireDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display: run under xvfb-run to include these tests");
    }

    static McpSession startServer(String name) throws IOException {
        Path jar = Path.of(required("swing2mcp.serverJar"));
        if (!Files.exists(jar)) {
            throw new IOException(jar + " does not exist. Run the tests with mvn verify or mvn install.");
        }
        Path work = Path.of(required("swing2mcp.workDir"));
        Files.createDirectories(work);
        return new McpSession(jar, work.resolve("mcp-server-" + name + ".log"));
    }

    static Path demoDir() {
        return Path.of(required("swing2mcp.demoDir")).toAbsolutePath().normalize();
    }

    /** The java executable that runs the tests; it also runs the server and the application. */
    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** A shell command for launch_app, to be run in {@link #demoDir()}. */
    static String demoCommand() {
        return "\"" + java() + "\" -jar target/demo-app.jar";
    }

    static long pidIn(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) {
            throw new AssertionError("No pid (" + regex + ") in:\n" + text);
        }
        return Long.parseLong(m.group(1));
    }

    static void kill(long pid) {
        if (pid > 0) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null) {
            throw new IllegalStateException("System property " + property + " is not set; run the tests with Maven.");
        }
        return value;
    }
}
