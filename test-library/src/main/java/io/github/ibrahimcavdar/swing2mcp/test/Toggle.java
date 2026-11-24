package io.github.ibrahimcavdar.swing2mcp.test;

/** A check box, radio button or toggle button. */
public final class Toggle extends Locator {

    Toggle(SwingApp app, String locator) {
        super(app, locator);
    }

    public boolean isChecked() {
        return require().has("checked");
    }

    /** Clicks it unless it is checked already. */
    public void check() {
        if (!isChecked()) {
            click();
        }
    }

    /** Clicks it if it is checked. */
    public void uncheck() {
        if (isChecked()) {
            click();
        }
    }

    public void shouldBeChecked() {
        should("be checked", new Check() {
            public boolean ok(State state) {
                return state != null && state.has("checked");
            }
        });
    }

    public void shouldBeUnchecked() {
        should("be unchecked", new Check() {
            public boolean ok(State state) {
                return state != null && state.has("unchecked");
            }
        });
    }
}
