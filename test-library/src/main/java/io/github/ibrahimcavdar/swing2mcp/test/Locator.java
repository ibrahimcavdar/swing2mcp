package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One component of the application, described but not looked up yet. Every method finds it anew, waiting
 * until it is on screen (and, to act on it, usable), so a test needs no sleeps. A method that cannot do
 * its work throws an {@link AssertionError} that says what is on screen instead.
 *
 * <p>The subclasses add what only their kind of component can do: a {@link TextInput} can be filled, a
 * {@link Table} has rows.
 */
public class Locator {

    final SwingApp app;
    final String locator;

    Locator(SwingApp app, String locator) {
        this.app = app;
        this.locator = locator;
    }

    /** Request parameters that name this component. */
    final Map<String, Object> target() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("ref", locator);
        params.put("timeoutMs", app.timeoutMs());
        return params;
    }

    /** As {@link #target}, to read the rows or items of the component: as many as the agent gives at once. */
    final Map<String, Object> allRows() {
        Map<String, Object> params = target();
        params.put("limit", 1000);
        return params;
    }

    final void act(String endpoint, Map<String, Object> params) {
        app.post(endpoint, params);
    }

    /**
     * A mouse click as a user's: the mouse goes to the middle of the component (scrolled into view if it
     * was not) and presses there. If something else lies on top at that point, the click fails and says
     * what covers the component.
     */
    public void click() {
        act("/click", target());
    }

    public void doubleClick() {
        Map<String, Object> params = target();
        params.put("clickCount", 2);
        act("/click", params);
    }

    public void rightClick() {
        Map<String, Object> params = target();
        params.put("button", "right");
        act("/click", params);
    }

    /**
     * A click at one point of the component, for components that paint themselves (a drawing, a map, a
     * chart) and so have no parts to name. x and y are pixels from its top left corner, as in a
     * {@link #screenshot()} of it.
     */
    public void clickAt(int x, int y) {
        act("/click", at(x, y));
    }

    public void doubleClickAt(int x, int y) {
        Map<String, Object> params = at(x, y);
        params.put("clickCount", 2);
        act("/click", params);
    }

    /** A right click at one point of the component; a context menu it opens is then on screen. */
    public void rightClickAt(int x, int y) {
        Map<String, Object> params = at(x, y);
        params.put("button", "right");
        act("/click", params);
    }

    /**
     * Moves the mouse over the component without pressing a button, for what an application does when it
     * is pointed at: a rollover, a panel that opens on hover. Clicks and drags move the mouse to their
     * own point first, so this is only for pointing without clicking.
     */
    public void hover() {
        act("/hover", target());
    }

    /** As {@link #hover()}, at one point of the component: pixels from its top left corner. */
    public void hoverAt(int x, int y) {
        act("/hover", at(x, y));
    }

    /**
     * Moves the mouse over the component and turns the mouse wheel there: scrolls a list, zooms a map.
     * A positive number of notches is down (toward the user), a negative one up.
     */
    public void scroll(int notches) {
        Map<String, Object> params = target();
        params.put("wheel", notches);
        act("/hover", params);
    }

    /** As {@link #scroll(int)}, at one point of the component: pixels from its top left corner. */
    public void scrollAt(int x, int y, int notches) {
        Map<String, Object> params = at(x, y);
        params.put("wheel", notches);
        act("/hover", params);
    }

    /**
     * Presses the mouse at one point of the component, moves it to another and releases it there: pans a
     * map, moves a shape, draws a line. The points are pixels from the component's top left corner.
     */
    public void drag(int fromX, int fromY, int toX, int toY) {
        Map<String, Object> params = at(fromX, fromY);
        params.put("toX", toX);
        params.put("toY", toY);
        act("/drag", params);
    }

    /**
     * Presses keys in this component, wherever the keyboard focus is now: {@code tree.pressKey("F2")},
     * {@code table.pressKey("Ctrl+A")}. {@link SwingApp#pressKey} presses them where the focus is.
     */
    public void pressKey(String keys) {
        Map<String, Object> params = target();
        params.put("key", keys);
        act("/key", params);
    }

    private Map<String, Object> at(int x, int y) {
        Map<String, Object> params = target();
        params.put("x", x);
        params.put("y", y);
        return params;
    }

    /**
     * Clicks, and answers the dialog that the click opens with one of its buttons: its text, or
     * ok / yes / no / cancel in any language. Returns what the dialog said.
     */
    public String clickAndAnswer(String dialogButton) {
        Map<String, Object> params = target();
        params.put("dialog", dialogButton);
        return app.post("/click", params);
    }

    /** Whether it is on screen right now; does not wait. */
    public boolean exists() {
        return state(0) != null;
    }

    public boolean isEnabled() {
        return !require().has("disabled");
    }

    /** Its lines of the snapshot, e.g. {@code button "Save" [ref=e24] field=btnSave}. */
    public String describe() {
        return require().text;
    }

    /** A PNG picture of it, painted by the application itself. */
    public byte[] screenshot() {
        return app.getBytes("/screenshot", target());
    }

    // ---------------------------------------------------------------- checks, which wait

    public void shouldExist() {
        require();
    }

    public void shouldNotExist() {
        should("not be on screen", new Check() {
            public boolean ok(State state) {
                return state == null;
            }
        });
    }

    public void shouldBeEnabled() {
        should("be enabled", new Check() {
            public boolean ok(State state) {
                return state != null && !state.has("disabled");
            }
        });
    }

    public void shouldBeDisabled() {
        should("be disabled", new Check() {
            public boolean ok(State state) {
                return state != null && state.has("disabled");
            }
        });
    }

    // ---------------------------------------------------------------- for the subclasses

    interface Check {
        /** state is null while the component is not on screen. */
        boolean ok(State state);
    }

    /** Looks again and again until the check holds; fails after the application's timeout. */
    final void should(String expectation, Check check) {
        long deadline = System.currentTimeMillis() + app.timeoutMs();
        while (true) {
            State state = state(0);
            if (check.ok(state)) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw app.failure(locator + " should " + expectation + ", but after " + app.timeoutMs()
                        + " ms " + (state == null ? "it is not on screen." : "it is:\n" + state.text));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + locator + " to " + expectation);
            }
        }
    }

    /** What the snapshot says about it now, after waiting for it to be on screen. */
    final State require() {
        return State.of(app.get("/locate", target()));
    }

    /** As {@link #require}, waiting at most this long; null when it is not on screen. */
    final State state(long timeoutMs) {
        Map<String, Object> params = target();
        params.put("timeoutMs", timeoutMs);
        String answer = app.getOrNull("/locate", params);
        return answer == null ? null : State.of(answer);
    }

    @Override
    public String toString() {
        return locator;
    }
}
