package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.Map;

/** A text field, password field, text area or spinner. */
public final class TextInput extends Locator {

    TextInput(SwingApp app, String locator) {
        super(app, locator);
    }

    /**
     * Replaces the content by typing, key by key, as a user does: the field takes the focus and the
     * application's key listeners, filters and formatters run.
     */
    public void fill(String text) {
        type(text, false, false);
    }

    /** Types at the end of what is there. */
    public void append(String text) {
        type(text, true, false);
    }

    /** As {@link #fill}, then presses Enter. */
    public void fillAndSubmit(String text) {
        type(text, false, true);
    }

    private void type(String text, boolean append, boolean submit) {
        Map<String, Object> params = target();
        params.put("text", text);
        if (append) {
            params.put("append", true);
        }
        if (submit) {
            params.put("submit", true);
        }
        act("/type", params);
    }

    /** The text in it; null for a password field, whose text the agent does not give out. */
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
