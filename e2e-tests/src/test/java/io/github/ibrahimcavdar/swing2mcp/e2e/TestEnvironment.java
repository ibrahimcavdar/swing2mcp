package io.github.ibrahimcavdar.swing2mcp.e2e;

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

    /**
     * The java executable that runs the server and the application: the one that runs the tests, or
     * another one given with -Dswing2mcp.testJava (e.g. a Java 8, which cannot run the tests themselves).
     */
    static String java() {
        String other = System.getProperty("swing2mcp.testJava", "").trim();
        return other.isEmpty() ? Path.of(System.getProperty("java.home"), "bin", "java").toString() : other;
    }

    /** Whether that Java can attach to other JVMs: a JDK has the Attach API, a JRE does not. */
    static boolean canAttach() {
        if (System.getProperty("swing2mcp.testJava", "").trim().isEmpty()) {
            return ModuleLayer.boot().findModule("jdk.attach").isPresent();
        }
        Path home = Path.of(java()).toAbsolutePath().getParent().getParent();
        if (Files.exists(home.resolve("lib/tools.jar")) || Files.exists(home.resolve("../lib/tools.jar"))) {
            return true; // a Java 8 JDK
        }
        try {
            Process modules = new ProcessBuilder(java(), "--list-modules").redirectErrorStream(true).start();
            String listed = new String(modules.getInputStream().readAllBytes());
            modules.waitFor();
            return listed.contains("jdk.attach");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
