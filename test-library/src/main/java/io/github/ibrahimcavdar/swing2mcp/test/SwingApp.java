package io.github.ibrahimcavdar.swing2mcp.test;

import io.github.ibrahimcavdar.swing2mcp.client.AgentClient;
import io.github.ibrahimcavdar.swing2mcp.client.Apps;
import io.github.ibrahimcavdar.swing2mcp.client.Processes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A running Swing application in a test. Works with any test framework: a step that cannot be done, or a
 * check that does not hold, throws an {@link AssertionError}.
 *
 * <pre>
 * try (SwingApp app = SwingApp.launch(Main.class)) {
 *     app.textField("User name").fill("admin");
 *     app.passwordField("Password").fill("admin");
 *     app.button("Log In").click();
 *     app.table().shouldContain("Ali Veli");
 * }
 * </pre>
 *
 * The application is not changed and runs in a process of its own, started with the swing2mcp agent,
 * which this library talks to on the local machine. No MCP and no AI tool is involved. Swing needs a
 * display, so on a build server without one the tests run under a virtual display (on Linux:
 * {@code xvfb-run}).
 */
public final class SwingApp extends Scope implements AutoCloseable {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);

    private final Apps apps;
    private final AgentClient client;
    private long timeoutMs = 10000;
    private File screenshots;

    private SwingApp(Apps apps, AgentClient client) {
        this.apps = apps;
        this.client = client;
    }

    /**
     * Starts the application from its main class, in a process of its own, with the Java and the class
     * path that run the tests. {@link #close()} ends it.
     */
    public static SwingApp launch(Class<?> mainClass, String... arguments) {
        return launch(mainClass.getName(), arguments);
    }

    /** As {@link #launch(Class, String...)}, for a main class the tests do not compile against. */
    public static SwingApp launch(String mainClassName, String... arguments) {
        List<String> program = new ArrayList<String>();
        program.add(Paths.get(System.getProperty("java.home"), "bin", Processes.isWindows() ? "java.exe" : "java")
                .toString());
        program.add("-cp");
        program.add(System.getProperty("java.class.path"));
        program.add(mainClassName);
        program.addAll(Arrays.asList(arguments));
        return start(program, null, null);
    }

    /**
     * Starts the application with a command line as typed into a shell, for applications that are started
     * by a script or need their own Java: {@code "java -jar app.jar"}, {@code "bin\\start.bat"}. The
     * command runs in workingDirectory, or in the current directory if that is null.
     */
    public static SwingApp launchCommand(String command, File workingDirectory) {
        return start(null, command, workingDirectory);
    }

    public static SwingApp launchCommand(String command) {
        return start(null, command, null);
    }

    private static SwingApp start(List<String> program, String command, File workingDirectory) {
        Apps apps = Apps.ofUser();
        Path cwd = workingDirectory == null ? null : workingDirectory.toPath();
        try {
            if (program != null) {
                apps.launch(program, cwd, START_TIMEOUT);
            } else {
                apps.launch(command, cwd, START_TIMEOUT);
            }
            return new SwingApp(apps, apps.current());
        } catch (IOException e) {
            throw new IllegalStateException("The application could not be started: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the application", e);
        }
    }

    /**
     * How long actions wait for their component to be on screen and usable, and checks for what they
     * expect. Default: 10 seconds.
     */
    public SwingApp timeout(long milliseconds) {
        this.timeoutMs = milliseconds;
        return this;
    }

    /**
     * When a step or check fails, saves a picture of the application into this directory; the error says
     * where. Off by default.
     */
    public SwingApp screenshotsOnFailure(File directory) {
        this.screenshots = directory;
        return this;
    }

    public long pid() {
        return client.pid;
    }

    // ---------------------------------------------------------------- the application as a whole

    /** Whether the application is still running. */
    public boolean isRunning() {
        return Processes.isAlive(client.pid, client.port);
    }

    /** Waits until the application has ended, for a test of its own way out (an Exit menu, a last window). */
    public void shouldHaveExited() {
        if (!Processes.waitForExit(client.pid, client.port, timeoutMs)) {
            throw new AssertionError("The application should have ended, but after " + timeoutMs
                    + " ms it is still running.\n" + snapshotOrNothing());
        }
    }

    private String snapshotOrNothing() {
        try {
            return snapshot();
        } catch (RuntimeException | AssertionError e) {
            return "";
        }
    }

    /** Everything on screen as text: each window as an outline of its components. */
    public String snapshot() {
        return get("/snapshot", new LinkedHashMap<String, Object>());
    }

    /** A PNG picture of the open dialog, or else of the active window. */
    public byte[] screenshot() {
        return getBytes("/screenshot", new LinkedHashMap<String, Object>());
    }

    /** Waits until this text is somewhere on screen. */
    public void shouldShow(String text) {
        waitFor(text, false);
    }

    /** Waits until this text is nowhere on screen any more. */
    public void shouldNotShow(String text) {
        waitFor(text, true);
    }

    private void waitFor(String text, boolean gone) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("text", text);
        params.put("gone", gone);
        params.put("timeoutMs", timeoutMs);
        get("/wait", params);
    }

    /**
     * Presses keys where the keyboard focus is: {@code "Enter"}, {@code "Ctrl+S"}, {@code "Tab Tab Enter"}.
     */
    public void pressKey(String keys) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("key", keys);
        params.put("timeoutMs", timeoutMs);
        post("/key", params);
    }

    /** Opens a menu level by level and clicks the last item: {@code "File > Export > PDF"}. */
    public void selectMenu(String path) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("path", path);
        params.put("timeoutMs", timeoutMs);
        post("/menu", params);
    }

    /**
     * Presses a button of the dialog that is open, by text or by role (ok, yes, no, cancel, which work in
     * any language). Waits for a dialog to open. Returns what the dialog said.
     */
    public String answerDialog(String button) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("button", button);
        params.put("timeoutMs", timeoutMs);
        return post("/dialog", params);
    }

    /** Types an answer into the input dialog that is open and presses its OK button. */
    public String answerDialogWith(String text) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("text", text);
        params.put("button", "ok");
        params.put("timeoutMs", timeoutMs);
        return post("/dialog", params);
    }

    /** Picks a file or directory in the file chooser that is open and presses its Open or Save button. */
    public String chooseFile(File file) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("path", file.getAbsolutePath());
        params.put("timeoutMs", timeoutMs);
        return post("/dialog", params);
    }

    /** Ends the application. */
    @Override
    public void close() {
        try {
            apps.stop(client.pid);
        } catch (IOException e) {
            // it has ended already
        }
    }

    // ---------------------------------------------------------------- for this package

    @Override
    SwingApp app() {
        return this;
    }

    @Override
    String prefix() {
        return "";
    }

    long timeoutMs() {
        return timeoutMs;
    }

    /** The error for a step or check that failed; with a picture of the application if that was asked for. */
    AssertionError failure(String message) {
        if (screenshots != null) {
            try {
                byte[] png = client.getBytes("/screenshot", null, Duration.ofSeconds(10));
                Files.createDirectories(screenshots.toPath());
                Path file = screenshots.toPath().resolve("failure-" + System.currentTimeMillis() + ".png");
                Files.write(file, png);
                message += "\nScreenshot: " + file.toAbsolutePath();
            } catch (IOException e) {
                message += "\n(No screenshot: " + e.getMessage() + ")";
            }
        }
        return new AssertionError(message);
    }

    /** Room for the agent's own wait and for the action itself. */
    private Duration httpTimeout(Map<String, Object> params) {
        Object given = params.get("timeoutMs");
        long wait = given instanceof Number ? ((Number) given).longValue() : timeoutMs;
        Object text = params.get("text");
        return Duration.ofMillis(wait + 30000 + 100L * (text == null ? 0 : text.toString().length()));
    }

    /** An action that ends the application (a click on "Exit") is not a failure: its answer says so. */
    String post(String path, Map<String, Object> params) {
        try {
            return client.post(path, params, httpTimeout(params));
        } catch (AgentClient.AppExited e) {
            return e.getMessage();
        } catch (IOException e) {
            throw failure(e.getMessage());
        }
    }

    String get(String path, Map<String, Object> params) {
        try {
            return client.get(path, params, httpTimeout(params));
        } catch (IOException e) {
            throw failure(e.getMessage());
        }
    }

    /** As {@link #get}, but null when the agent answers that the locator matches nothing on screen. */
    String getOrNull(String path, Map<String, Object> params) {
        try {
            return client.get(path, params, httpTimeout(params));
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            if (message.startsWith("Nothing on screen matches") || message.startsWith("Only ")) {
                return null;
            }
            throw failure(message);
        }
    }

    byte[] getBytes(String path, Map<String, Object> params) {
        try {
            return client.getBytes(path, params, httpTimeout(params));
        } catch (IOException e) {
            throw failure(e.getMessage());
        }
    }
}
