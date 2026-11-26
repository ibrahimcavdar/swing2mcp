package io.github.ibrahimcavdar.swing2mcp.agent;

import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;

/**
 * Where the mouse is as far as the application knows: the place the last mouse action moved it to.
 * A real mouse cannot press at a point without getting there first, and it leaves the component it was
 * over. Applications rely on that: a shape that follows the mouse, a rollover, a panel that opens when it
 * is pointed at. So every mouse action arrives at its point through here before it does anything else.
 */
final class Pointer {

    private static WeakReference<Component> over = new WeakReference<Component>(null);

    private Pointer() {
    }

    /** On the EDT: the mouse gets to p inside c with no button pressed. */
    static void arrive(Component c, Point p, int modifiers) {
        long now = System.currentTimeMillis();
        Component before = over.get();
        if (before != c) {
            if (before != null && before.isShowing()) {
                before.dispatchEvent(new MouseEvent(before, MouseEvent.MOUSE_EXITED, now, modifiers, -1, -1, 0, false,
                        MouseEvent.NOBUTTON));
            }
            over = new WeakReference<Component>(c);
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_ENTERED, now, modifiers, p.x, p.y, 0, false,
                    MouseEvent.NOBUTTON));
        }
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_MOVED, now, modifiers, p.x, p.y, 0, false,
                MouseEvent.NOBUTTON));
    }
}
