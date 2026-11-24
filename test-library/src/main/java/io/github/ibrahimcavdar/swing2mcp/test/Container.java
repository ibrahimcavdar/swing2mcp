package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A window, dialog, internal frame or titled group: components can be looked for inside it, which tells
 * apart two with the same name (the "OK" of one dialog and of another).
 */
public final class Container extends Scope {

    private final SwingApp app;
    private final String locator;

    Container(SwingApp app, String locator) {
        this.app = app;
        this.locator = locator;
    }

    @Override
    SwingApp app() {
        return app;
    }

    @Override
    String prefix() {
        return locator + " > ";
    }

    private Locator self() {
        return new Locator(app, locator);
    }

    /** Whether it is on screen right now; does not wait. */
    public boolean exists() {
        return self().exists();
    }

    /** Waits until it is on screen. */
    public void shouldExist() {
        self().shouldExist();
    }

    /** Waits until it is gone, e.g. a dialog that should have closed. */
    public void shouldNotExist() {
        self().shouldNotExist();
    }

    /** Waits until this text is somewhere in it, e.g. the message of a dialog. */
    public void shouldShow(final String text) {
        self().should("show \"" + text + "\"", new Locator.Check() {
            public boolean ok(State state) {
                return state != null && state.text.contains(text);
            }
        });
    }

    /** Its part of the snapshot: everything in it, as text. */
    public String describe() {
        return self().describe();
    }

    public byte[] screenshot() {
        return self().screenshot();
    }

    /**
     * For a dialog: presses one of its buttons, by text or by role (ok, yes, no, cancel, which work in any
     * language; "close" is the window's close box). Returns what the dialog said.
     */
    public String answer(String button) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("ref", locator);
        params.put("button", button);
        params.put("timeoutMs", app.timeoutMs());
        return app.post("/dialog", params);
    }

    @Override
    public String toString() {
        return locator;
    }
}
