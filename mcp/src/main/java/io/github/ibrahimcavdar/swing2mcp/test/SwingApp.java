package io.github.ibrahimcavdar.swing2mcp.test;

import io.github.ibrahimcavdar.swing2mcp.mcp.AppSession;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A running Swing application in a test. Works with any test framework: a step that cannot be done, or a
 * check that does not hold, throws an {@link AssertionError}.
 *
 * <pre>
 * try (SwingApp app = SwingApp.launch("java -jar target/app.jar")) {
 *     app.textField("User name").fill("admin");
 *     app.passwordField("Password").fill("admin");
 *     app.button("Log In").click();
 *     app.table().shouldContain("Ali Veli");
 * }
 * </pre>
 *
 * The application is not changed: it is started with the swing2mcp agent, which this library talks to
 * on the local machine. No MCP and no AI tool is involved. Swing needs a display, so on a build server
 * without one the tests run under a virtual display (on Linux: {@code xvfb-run}).
 */
public final class SwingApp extends Scope implements AutoCloseable {

    private final AppSession session;
    private final boolean launched;
    private long timeoutMs = 10000;

    private SwingApp(AppSession session, boolean launched) {
        this.session = session;
        this.launched = launched;
    }

    /**
     * Starts the application with a shell command, e.g. {@code java -jar app.jar} or a start script, in the
     * current directory. {@link #close()} ends it.
     */
    public static SwingApp launch(String command) {
        return launch(command, null);
    }

    public static SwingApp launch(String command, File workingDirectory) {
        try {
            return new SwingApp(AppSession.launch(command, workingDirectory, 60000), true);
        } catch (IOException e) {
            throw new IllegalStateException("The application could not be started: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the application", e);
        }
    }

    /**
     * Connects to an application that is running already. If it was started without the agent, the agent
     * is loaded into it, which needs the tests to run on a JDK. {@link #close()} leaves it running.
     */
    public static SwingApp connect(long pid) {
        try {
            return new SwingApp(AppSession.connect(pid), false);
        } catch (IOException e) {
            throw new IllegalStateException("Could not connect to pid " + pid + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while connecting to pid " + pid, e);
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

    public long pid() {
        return session.pid();
    }

    // ---------------------------------------------------------------- the application as a whole

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

    /** Ends the application if this test started it. */
    @Override
    public void close() {
        if (launched) {
            try {
                session.stop();
            } catch (IOException e) {
                // it has ended already
            }
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

    /** Room for the agent's own wait and for the action itself. */
    private long httpTimeout(Map<String, Object> params) {
        Object given = params.get("timeoutMs");
        long wait = given instanceof Number ? ((Number) given).longValue() : timeoutMs;
        Object text = params.get("text");
        return wait + 30000 + 100L * (text == null ? 0 : text.toString().length());
    }

    String post(String path, Map<String, Object> params) {
        try {
            return session.post(path, params, httpTimeout(params));
        } catch (IOException e) {
            throw new AssertionError(e.getMessage());
        }
    }

    String get(String path, Map<String, Object> params) {
        try {
            return session.get(path, params, httpTimeout(params));
        } catch (IOException e) {
            throw new AssertionError(e.getMessage());
        }
    }

    /** As {@link #get}, but null when the agent answers that the locator matches nothing on screen. */
    String getOrNull(String path, Map<String, Object> params) {
        try {
            return session.get(path, params, httpTimeout(params));
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            if (message.startsWith("Nothing on screen matches") || message.startsWith("Only ")) {
                return null;
            }
            throw new AssertionError(message);
        }
    }

    byte[] getBytes(String path, Map<String, Object> params) {
        try {
            return session.getBytes(path, params, httpTimeout(params));
        } catch (IOException e) {
            throw new AssertionError(e.getMessage());
        }
    }
}
