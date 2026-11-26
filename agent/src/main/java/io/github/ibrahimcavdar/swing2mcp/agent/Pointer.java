package io.github.ibrahimcavdar.swing2mcp.agent;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.ref.WeakReference;

import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

/**
 * The mouse as the application sees it. Two things a real mouse cannot avoid are kept here.
 *
 * <p>It is somewhere: it cannot press at a point without getting there first, and it leaves what it was
 * over. Applications rely on that (a shape that follows the mouse, a rollover, a panel that opens when it
 * is pointed at), so every mouse action arrives at its point before it does anything else.
 *
 * <p>It acts on a point of a window, not on a component: mouse events are handed to the window, as the
 * operating system does, and AWT finds who gets them. That is the topmost component at the point that
 * listens to the mouse, so a click on a label inside a clickable panel reaches the panel, a click on a
 * spinner reaches the text field in it, and something that lies on top takes the click. AWT also keeps
 * track of mouse-entered and mouse-exited this way, together with the real mouse.
 */
final class Pointer {

    /** The window (or heavyweight component) the mouse was last moved into. */
    private static WeakReference<Component> in = new WeakReference<Component>(null);

    private Pointer() {
    }

    /** Where the operating system would deliver a mouse event for c: its window, or a heavyweight on the way. */
    static Component dispatcher(Component c) {
        Component d = c;
        while (d.isLightweight() && d.getParent() != null) {
            d = d.getParent();
        }
        return d;
    }

    /** On the EDT: the mouse gets to p inside c with no button pressed. Returns p as a point of {@link #dispatcher}. */
    static Point arrive(Component c, Point p, int modifiers) {
        Component d = dispatcher(c);
        Point at = SwingUtilities.convertPoint(c, p, d);
        long now = System.currentTimeMillis();
        Component before = in.get();
        if (before != d) {
            if (before != null && before.isShowing()) {
                before.dispatchEvent(new MouseEvent(before, MouseEvent.MOUSE_EXITED, now, modifiers, -1, -1, 0, false,
                        MouseEvent.NOBUTTON));
            }
            in = new WeakReference<Component>(d);
            d.dispatchEvent(new MouseEvent(d, MouseEvent.MOUSE_ENTERED, now, modifiers, at.x, at.y, 0, false,
                    MouseEvent.NOBUTTON));
        }
        d.dispatchEvent(new MouseEvent(d, MouseEvent.MOUSE_MOVED, now, modifiers, at.x, at.y, 0, false,
                MouseEvent.NOBUTTON));
        return at;
    }

    /** On the EDT: a press, release or click at a point of the dispatcher d, where the mouse has arrived. */
    static void button(Component d, int id, Point at, int modifiers, int clickCount, boolean popupTrigger, int button) {
        d.dispatchEvent(new MouseEvent(d, id, System.currentTimeMillis(), modifiers, at.x, at.y, clickCount,
                popupTrigger, button));
    }

    /** On the EDT: one notch of the mouse wheel at a point of d; down is toward the user (scrolls down). */
    static void wheel(Component d, Point at, int modifiers, boolean down) {
        d.dispatchEvent(new MouseWheelEvent(d, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers, at.x, at.y,
                0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, down ? 1 : -1));
    }

    /**
     * On the EDT: scrolls so that p of c is in view, as a user would before using the mouse there.
     * Returns false if it still is not (the component is cut off there by what contains it).
     */
    static boolean bringIntoView(Component c, Point p) {
        if (!(c instanceof JComponent)) {
            return true;
        }
        JComponent jc = (JComponent) c;
        if (jc.getVisibleRect().contains(p)) {
            return true;
        }
        Rectangle around = new Rectangle(p.x - 12, p.y - 12, 24, 24).intersection(new Rectangle(0, 0, c.getWidth(), c.getHeight()));
        if (!around.isEmpty() && c.getParent() instanceof JComponent) {
            // through the parent: a text field keeps scrollRectToVisible for its own text
            around.translate(c.getX(), c.getY());
            ((JComponent) c.getParent()).scrollRectToVisible(around);
        }
        return jc.getVisibleRect().contains(p);
    }

    /**
     * On the EDT: what lies on top of c at p and would get a mouse event there instead of it. Null if the
     * event reaches c, a part of c, or what c is a part of (a label without mouse listeners inside a
     * clickable panel).
     */
    static Component cover(Component c, Point p) {
        Component d = dispatcher(c);
        if (!(d instanceof Container)) {
            return null;
        }
        Point at = SwingUtilities.convertPoint(c, p, d);
        Component hit = target((Container) d, at.x, at.y, c);
        if (hit == null || hit == c || SwingUtilities.isDescendingFrom(hit, c) || SwingUtilities.isDescendingFrom(c, hit)) {
            return null;
        }
        // An internal frame that is not selected has a glass pane that selects it and passes the mouse on.
        Container parent = hit.getParent();
        if (parent instanceof JRootPane && ((JRootPane) parent).getGlassPane() == hit
                && parent.getParent() instanceof JInternalFrame && SwingUtilities.isDescendingFrom(c, parent)) {
            return null;
        }
        return hit;
    }

    /**
     * The component AWT gives a mouse event at x, y of a container to (Container.getMouseEventTarget):
     * the topmost first, and a component only counts if it listens to the mouse; one that does not lets
     * the mouse through to what is under it. The named component always counts: it may take the mouse
     * without a listener (enableEvents), which cannot be seen from outside.
     */
    private static Component target(Container in, int x, int y, Component named) {
        for (int i = 0; i < in.getComponentCount(); i++) {
            Component child = in.getComponent(i);
            if (child == null || !child.isVisible() || !child.isLightweight()
                    || !child.contains(x - child.getX(), y - child.getY())) {
                continue;
            }
            if (child instanceof Container) {
                Component deeper = target((Container) child, x - child.getX(), y - child.getY(), named);
                if (deeper != null) {
                    return deeper;
                }
            } else if (takesMouse(child, named)) {
                return child;
            }
        }
        return in.contains(x, y) && takesMouse(in, named) ? in : null;
    }

    private static boolean takesMouse(Component c, Component named) {
        return c == named || c.getMouseListeners().length > 0 || c.getMouseMotionListeners().length > 0
                || c.getMouseWheelListeners().length > 0;
    }
}
