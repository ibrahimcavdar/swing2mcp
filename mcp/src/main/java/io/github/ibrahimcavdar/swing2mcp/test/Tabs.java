package io.github.ibrahimcavdar.swing2mcp.test;

import java.util.Map;

/** A tabbed pane. */
public final class Tabs extends Locator {

    Tabs(SwingApp app, String locator) {
        super(app, locator);
    }

    /** Shows the tab with this title. */
    public void select(String title) {
        Map<String, Object> params = target();
        params.put("value", title);
        act("/select", params);
    }
}
