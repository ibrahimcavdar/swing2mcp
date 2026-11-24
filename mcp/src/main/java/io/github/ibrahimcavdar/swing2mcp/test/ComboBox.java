package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.Map;

/** A combo box. */
public final class ComboBox extends Locator {

    ComboBox(SwingApp app, String locator) {
        super(app, locator);
    }

    /** Picks the entry with this text. */
    public void select(String value) {
        Map<String, Object> params = target();
        params.put("value", value);
        act("/select", params);
    }

    /** The text of the selected entry. */
    public String value() {
        return require().quoted("value");
    }

    public void shouldHaveValue(final String expected) {
        should("have the value \"" + expected + "\"", new Check() {
            public boolean ok(State state) {
                return state != null && expected.equals(state.quoted("value"));
            }
        });
    }
}
