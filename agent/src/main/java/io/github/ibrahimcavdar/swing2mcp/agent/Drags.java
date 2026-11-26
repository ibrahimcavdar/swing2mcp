package io.github.ibrahimcavdar.swing2mcp.agent;

import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JList;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.plaf.basic.BasicSliderUI;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumnModel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureRecognizer;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetContext;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Dragging with the mouse, three ways:
 * <ul>
 * <li>mouse: press, a series of drags and a release as real mouse events inside the JVM, delivered
 * the way AWT delivers them (to the component under the pointer, then grabbed by it until the
 * release). Pans maps, moves sliders, split pane dividers, table columns. No focus needed.</li>
 * <li>transfer: application drag and drop through Swing's data transfer, without the operating
 * system's drag and drop: the source's TransferHandler creates the data, the target's drop target
 * gets drag-enter/over/drop events (so canImport and importData run with a real drop location),
 * then the source finishes the export (exportDone). No focus needed.</li>
 * <li>os: the real mouse through java.awt.Robot (the window comes to the front).</li>
 * </ul>
 */
final class Drags {

    private static final long CHECK_TIMEOUT_MS = 3000;
    private static final int DEFAULT_DURATION_MS = 300;
    private static final boolean JAVA_8 = System.getProperty("java.specification.version", "").startsWith("1.");

    private final RefRegistry refs;

    Drags(RefRegistry refs) {
        this.refs = refs;
    }

    /** Where a drag starts and ends, and how; computed on the EDT. */
    private static final class Plan {
        Component source;
        Actions.Spot from;
        Component target;
        Actions.Spot to;
        String mode;
        /** Transfer mode: the component whose TransferHandler exports. */
        JComponent exporter;
        /** Mouse mode: the component that gets the events (window or heavyweight) and the points in it. */
        Component dispatcher;
        Point start;
        Point end;
        /** The component the press lands on, if it is not the source or inside it. */
        Component pressed;
        String stateBefore;
        BufferedImage pictureBefore;
        /** Added to the result, e.g. why nothing was dropped. */
        String note = "";
    }

    Actions.Result drag(final Map<String, String> p) throws Exception {
        final Component source = Actions.header(resolve(p, "ref"), p.get("header"));
        final Component target = Text.isBlank(p.get("toRef")) ? null : Actions.header(resolve(p, "toRef"), p.get("toHeader"));
        final Integer dx = Actions.optInt(p, "dx");
        final Integer dy = Actions.optInt(p, "dy");
        final boolean toPart = !Text.isBlank(p.get("toValue")) || p.get("toRow") != null || p.get("toX") != null;
        if (target == null && dx == null && dy == null && !toPart) {
            throw new IllegalArgumentException("Say where to drag to: toRef (with toValue, toRow or toX/toY), or dx/dy");
        }
        final String mode = Text.isBlank(p.get("mode")) ? "auto" : p.get("mode").trim().toLowerCase(Locale.ROOT);
        if (!Arrays.asList("auto", "mouse", "transfer", "os").contains(mode)) {
            throw new IllegalArgumentException("mode must be auto (default), mouse, transfer or os");
        }
        final StringBuilder waited = new StringBuilder();
        final Plan plan = Actions.whenUsable(p, waited, new Callable<Plan>() {
            public Plan call() {
                Actions.ensureUsable(source, false);
                if (target != null) {
                    Actions.ensureUsable(target, false);
                }
                Plan plan = new Plan();
                plan.source = source;
                plan.from = sourceSpot(source, p);
                plan.target = target != null ? target : source;
                plan.to = targetSpot(plan.target, p, plan.target == source ? plan.from : null, dx, dy);
                plan.mode = mode(plan, mode);
                Keys.noteWindow(Keys.windowOf(source));
                return plan;
            }
        });
        if (plan.mode.equals("transfer")) {
            return transfer(plan, p, waited.toString());
        }
        if (plan.mode.equals("os")) {
            return OsMouse.drag(plan.source, plan.from, plan.target, plan.to, p, waited
                    + "Dragged " + route(plan) + " with the real mouse (operating system)");
        }
        return mouse(plan, p, waited.toString());
    }

    private Component resolve(Map<String, String> p, String key) throws Exception {
        String ref = p.get(key);
        if (Text.isBlank(ref)) {
            throw new IllegalArgumentException("Missing parameter: " + key);
        }
        return new Locators(refs).resolve(ref.trim(), p);
    }

    private static String route(Plan plan) {
        String to = plan.to.what.startsWith(" by ") ? plan.to.what : " to" + plan.to.what;
        if (plan.target == plan.source) {
            return "in " + Actions.describe(plan.source) + " from" + plan.from.what + to;
        }
        return "from " + Actions.describe(plan.source) + plan.from.what + " to " + Actions.describe(plan.target) + plan.to.what;
    }

    // ---------------------------------------------------------------- where

    /** On the EDT: the start point (an item, a slider's thumb, a split pane's divider, x/y, or the center). */
    private static Actions.Spot sourceSpot(Component c, Map<String, String> p) {
        Integer x = Actions.optInt(p, "x");
        Integer y = Actions.optInt(p, "y");
        if (x != null && y != null) {
            return new Actions.Spot(new Point(x, y), " " + x + "," + y, -1, -1, null);
        }
        Integer row = Actions.optInt(p, "row");
        Integer col = Actions.optInt(p, "col");
        if (c instanceof JTable && row != null && col == null) {
            col = Actions.selectionColumn((JTable) c, row); // not a check box cell: the press would toggle it
        }
        Actions.Spot spot = Actions.itemSpot(c, row, col, Text.isBlank(p.get("value")) ? null : p.get("value"), true);
        if (spot != null) {
            return edge(spot, p.get("edge"));
        }
        if (c instanceof JSlider) {
            JSlider slider = (JSlider) c;
            return new Actions.Spot(sliderPoint(slider, slider.getValue(), null), " its thumb (value " + slider.getValue() + ")",
                    -1, -1, null);
        }
        if (c instanceof JSplitPane && ((JSplitPane) c).getUI() instanceof BasicSplitPaneUI) {
            Component divider = ((BasicSplitPaneUI) ((JSplitPane) c).getUI()).getDivider();
            if (divider != null) {
                Rectangle b = divider.getBounds();
                return new Actions.Spot(new Point(b.x + b.width / 2, b.y + b.height / 2), " its divider (at "
                        + ((JSplitPane) c).getDividerLocation() + ")", -1, -1, b);
            }
        }
        return new Actions.Spot(new Point(c.getWidth() / 2, c.getHeight() / 2), " its center", -1, -1, null);
    }

    /** edge=left/right/top/bottom: the edge of the cell or header column instead of its center (e.g. to resize a column). */
    private static Actions.Spot edge(Actions.Spot spot, String edge) {
        if (Text.isBlank(edge) || spot.cell == null) {
            return spot;
        }
        Rectangle r = spot.cell;
        Point at = new Point(spot.point);
        String e = edge.trim().toLowerCase(Locale.ROOT);
        if (e.equals("right")) {
            at.x = r.x + r.width - 2;
        } else if (e.equals("left")) {
            at.x = r.x + 1;
        } else if (e.equals("bottom")) {
            at.y = r.y + r.height - 2;
        } else if (e.equals("top")) {
            at.y = r.y + 1;
        } else {
            throw new IllegalArgumentException("edge must be left, right, top or bottom");
        }
        return new Actions.Spot(at, spot.what + " (" + e + " edge)", spot.row, spot.col, r);
    }

    /**
     * On the EDT: the end point in target: dx/dy from the start, toX/toY, a slider value or divider
     * location (toValue), an item (toValue/toRow/toCol, at=before/on/after), or the center.
     */
    private static Actions.Spot targetSpot(Component t, Map<String, String> p, Actions.Spot from, Integer dx, Integer dy) {
        if (from != null && (dx != null || dy != null)) {
            Point at = new Point(from.point.x + (dx == null ? 0 : dx), from.point.y + (dy == null ? 0 : dy));
            return new Actions.Spot(at, " by " + (dx == null ? 0 : dx) + "," + (dy == null ? 0 : dy), -1, -1, null);
        }
        if (dx != null || dy != null) {
            throw new IllegalArgumentException("dx/dy move from the start point; leave out toRef or give toX/toY instead");
        }
        Integer x = Actions.optInt(p, "toX");
        Integer y = Actions.optInt(p, "toY");
        if (x != null && y != null) {
            return new Actions.Spot(new Point(x, y), " " + x + "," + y, -1, -1, null);
        }
        String value = Text.isBlank(p.get("toValue")) ? null : p.get("toValue").trim();
        if (t instanceof JSlider && value != null) {
            JSlider slider = (JSlider) t;
            int v = number(value, "toValue");
            if (v < slider.getMinimum() || v > slider.getMaximum()) {
                throw new IllegalArgumentException("toValue " + v + " is outside the slider's range " + slider.getMinimum()
                        + ".." + slider.getMaximum());
            }
            return new Actions.Spot(sliderPoint(slider, v, from == null ? null : from.point), " value " + v, -1, -1, null);
        }
        if (t instanceof JSplitPane && value != null) {
            JSplitPane split = (JSplitPane) t;
            int v = number(value, "toValue");
            int half = split.getDividerSize() / 2;
            Point at = from != null ? new Point(from.point) : new Point(split.getWidth() / 2, split.getHeight() / 2);
            if (split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) {
                at.x = v + half;
            } else {
                at.y = v + half;
            }
            return new Actions.Spot(at, " divider location " + v, -1, -1, null);
        }
        Integer row = Actions.optInt(p, "toRow");
        Integer col = Actions.optInt(p, "toCol");
        boolean afterLast = false;
        int count = rowCount(t);
        if (row != null && count >= 0 && row == count && count > 0) {
            row = count - 1; // one past the end: after the last entry
            afterLast = true;
        }
        Actions.Spot spot = Actions.itemSpot(t, row, col, value, true);
        if (spot == null) {
            if (value != null || row != null) {
                throw new IllegalArgumentException(Actions.describe(t) + " has no items; give toX/toY or leave out toValue/toRow");
            }
            return new Actions.Spot(new Point(t.getWidth() / 2, t.getHeight() / 2), " its center", -1, -1, null);
        }
        String at = afterLast ? "after" : Text.isBlank(p.get("at")) ? "on" : p.get("at").trim().toLowerCase(Locale.ROOT);
        Rectangle r = spot.cell;
        Point point = new Point(spot.point);
        boolean horizontal = t instanceof JTableHeader;
        int quarter = Math.max(1, (horizontal ? r.width : r.height) / 8);
        if (at.equals("before")) {
            if (horizontal) {
                point.x = r.x + quarter;
            } else {
                point.y = r.y + quarter;
            }
        } else if (at.equals("after")) {
            if (horizontal) {
                point.x = r.x + r.width - 1 - quarter;
            } else {
                point.y = r.y + r.height - 1 - quarter;
            }
        } else if (!at.equals("on")) {
            throw new IllegalArgumentException("at must be before, on or after");
        }
        return new Actions.Spot(point, (at.equals("on") ? "" : " " + at) + spot.what, spot.row, spot.col, r);
    }

    private static int rowCount(Component c) {
        if (c instanceof JList) {
            return ((JList<?>) c).getModel().getSize();
        }
        if (c instanceof JTable) {
            return ((JTable) c).getRowCount();
        }
        if (c instanceof JTree) {
            return ((JTree) c).getRowCount();
        }
        return -1;
    }

    private static int number(String value, String what) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a number here: " + value);
        }
    }

    /**
     * The point of a slider's thumb for a value. Across the slider the thumb is found with the
     * look and feel's thumbRect on Java 8 (a protected field), else the middle of the slider.
     */
    private static Point sliderPoint(JSlider slider, int value, Point keep) {
        boolean horizontal = slider.getOrientation() == JSlider.HORIZONTAL;
        int along = -1;
        if (slider.getUI() instanceof BasicSliderUI) {
            // the thumb's center for a value: invert the public valueForX/YPosition (the other way is protected)
            BasicSliderUI ui = (BasicSliderUI) slider.getUI();
            int length = horizontal ? slider.getWidth() : slider.getHeight();
            long sum = 0;
            int hits = 0;
            int best = -1;
            int bestGap = Integer.MAX_VALUE;
            for (int pos = 0; pos < length; pos++) {
                int v = horizontal ? ui.valueForXPosition(pos) : ui.valueForYPosition(pos);
                if (v == value) {
                    sum += pos;
                    hits++;
                } else if (Math.abs(v - value) < bestGap) {
                    bestGap = Math.abs(v - value);
                    best = pos;
                }
            }
            along = hits > 0 ? (int) (sum / hits) : best;
        }
        if (along < 0) {
            double f = (value - slider.getMinimum()) / (double) Math.max(1, slider.getMaximum() - slider.getMinimum());
            along = horizontal ? (int) (f * slider.getWidth()) : (int) ((1 - f) * slider.getHeight());
        }
        int across;
        if (keep != null) {
            across = horizontal ? keep.y : keep.x;
        } else {
            Rectangle thumb = thumbRect(slider);
            across = thumb != null ? (horizontal ? thumb.y + thumb.height / 2 : thumb.x + thumb.width / 2)
                    : (horizontal ? slider.getHeight() / 2 : slider.getWidth() / 2);
        }
        return horizontal ? new Point(along, across) : new Point(across, along);
    }

    private static Rectangle thumbRect(JSlider slider) {
        if (!JAVA_8 || !(slider.getUI() instanceof BasicSliderUI)) {
            return null; // Java 9+: no reflection into the JDK
        }
        try {
            java.lang.reflect.Field field = BasicSliderUI.class.getDeclaredField("thumbRect");
            field.setAccessible(true);
            Rectangle r = (Rectangle) field.get(slider.getUI());
            return r == null || r.isEmpty() ? null : new Rectangle(r);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- which way

    /** On the EDT: picks the mode and checks that it is safe and possible. */
    private static String mode(Plan plan, String requested) {
        Window window = Keys.windowOf(plan.source);
        Point onScreen = screen(plan.source, plan.from.point);
        Point inWindow = new Point(onScreen.x - window.getLocationOnScreen().x, onScreen.y - window.getLocationOnScreen().y);
        Component deepest = SwingUtilities.getDeepestComponentAt(window, inWindow.x, inWindow.y);
        if (deepest == null) {
            throw new IllegalArgumentException("The start point" + plan.from.what + " is outside the window");
        }
        JComponent swing = swingDragSource(deepest);
        String awt = awtDragSource(deepest);
        if (requested.equals("auto")) {
            requested = swing != null ? "transfer" : "mouse";
            if (swing == null && awt == null && plan.target != plan.source) {
                plan.note = "\n" + Actions.describe(plan.source) + " does not use Swing or AWT drag and drop"
                        + (swingDragDisabled(deepest) ? " (dragging is not enabled on it)" : "")
                        + ", so this was a plain mouse drag: " + Actions.describe(plan.source) + " got all the mouse events,"
                        + " as with a real mouse. Anything dropped on " + Actions.describe(plan.target)
                        + " comes from the application's own mouse code; check the snapshot.";
            }
            if (swing == null && awt != null) {
                throw new IllegalArgumentException(Actions.describe(deepest) + " starts the operating system's drag and "
                        + "drop itself (" + awt + "), which in-application events cannot complete. Use mode=os (the real "
                        + "mouse; the window comes to the front).");
            }
        }
        if (requested.equals("mouse") && (swing != null || awt != null)) {
            throw new IllegalArgumentException("Dragging " + Actions.describe(swing != null ? swing : deepest) + " with "
                    + "in-application mouse events would start the operating system's drag and drop, which could drop the "
                    + "data into another program under the real mouse pointer. Use mode=transfer"
                    + (swing != null ? " (the default for it)" : "") + " or mode=os.");
        }
        if (requested.equals("transfer")) {
            plan.exporter = swing != null ? swing : transferSource(plan.source);
            if (plan.exporter == null) {
                throw new IllegalArgumentException(Actions.describe(plan.source) + " is not a drag source: it has no "
                        + "TransferHandler that exports data" + (awt != null ? " (it uses AWT drag and drop: try mode=os)"
                        : swingDragDisabled(deepest) ? " (dragging is not enabled on it, so a user cannot drag from it either)"
                        : "; to move something on screen use mode=mouse"));
            }
        }
        if (requested.equals("mouse")) {
            plan.dispatcher = deepest;
            while (plan.dispatcher.isLightweight() && plan.dispatcher.getParent() != null) {
                plan.dispatcher = plan.dispatcher.getParent(); // the window or a heavyweight: where the OS delivers
            }
            Point origin = plan.dispatcher.getLocationOnScreen();
            plan.start = new Point(onScreen.x - origin.x, onScreen.y - origin.y);
            Point end = screen(plan.target, plan.to.point);
            plan.end = new Point(end.x - origin.x, end.y - origin.y);
            Component receiver = deepest;
            while (receiver != null && receiver.getMouseListeners().length == 0 && receiver.getMouseMotionListeners().length == 0) {
                receiver = receiver.getParent();
            }
            if (receiver != null && receiver != plan.source && !SwingUtilities.isDescendingFrom(receiver, plan.source)
                    && !SwingUtilities.isDescendingFrom(plan.source, receiver)) {
                plan.pressed = receiver;
            }
            plan.stateBefore = state(plan.source);
            if (pictured(plan.source)) {
                plan.pictureBefore = Screenshots.render(plan.source);
            }
        }
        return requested;
    }

    private static Point screen(Component c, Point p) {
        Point origin = c.getLocationOnScreen();
        return new Point(origin.x + p.x, origin.y + p.y);
    }

    /** The component under the pointer (or an ancestor) that starts Swing drag and drop on a mouse drag. */
    private static JComponent swingDragSource(Component deepest) {
        for (Component at = deepest; at != null && !(at instanceof Window); at = at.getParent()) {
            if (!(at instanceof JComponent)) {
                continue;
            }
            JComponent jc = (JComponent) at;
            Boolean enabled = dragEnabled(jc);
            if (enabled != null) {
                if (enabled && jc.getTransferHandler() != null) {
                    return jc;
                }
                return null; // e.g. a list without drag: a drag selects
            }
            TransferHandler handler = jc.getTransferHandler();
            if (handler != null && !(handler instanceof javax.swing.plaf.UIResource)
                    && handler.getSourceActions(jc) != TransferHandler.NONE && jc.getMouseMotionListeners().length > 0) {
                return jc; // its own mouse code calls exportAsDrag
            }
        }
        return null;
    }

    private static boolean swingDragDisabled(Component deepest) {
        for (Component at = deepest; at != null && !(at instanceof Window); at = at.getParent()) {
            if (at instanceof JComponent && Boolean.FALSE.equals(dragEnabled((JComponent) at))) {
                return true;
            }
        }
        return false;
    }

    /** A component with a TransferHandler that exports, for mode=transfer on components without dragEnabled. */
    private static JComponent transferSource(Component c) {
        for (Component at = c; at != null && !(at instanceof Window); at = at.getParent()) {
            if (at instanceof JComponent) {
                JComponent jc = (JComponent) at;
                TransferHandler handler = jc.getTransferHandler();
                if (dragEnabled(jc) == null && handler != null && handler.getSourceActions(jc) != TransferHandler.NONE) {
                    return jc;
                }
            }
        }
        return null;
    }

    /** getDragEnabled() of lists, tables, trees and text components; null if the component has none. */
    private static Boolean dragEnabled(JComponent c) {
        try {
            Method m = c.getClass().getMethod("getDragEnabled");
            return (Boolean) m.invoke(c);
        } catch (Exception e) {
            return null;
        }
    }

    /** "a drag gesture recognizer" if c or an ancestor listens with one (AWT drag and drop), else null. */
    private static String awtDragSource(Component deepest) {
        for (Component at = deepest; at != null && !(at instanceof Window); at = at.getParent()) {
            for (MouseListener l : at.getMouseListeners()) {
                if (l instanceof DragGestureRecognizer) {
                    return "a drag gesture recognizer on " + Actions.describe(at);
                }
            }
            for (MouseMotionListener l : at.getMouseMotionListeners()) {
                if (l instanceof DragGestureRecognizer) {
                    return "a drag gesture recognizer on " + Actions.describe(at);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- mode=mouse

    private static Actions.Result mouse(final Plan plan, Map<String, String> p, String waited) {
        final int button = Actions.button(p.get("button"));
        final int downMask = InputEvent.getMaskForButton(button);
        final int modifiers = Keys.modifiers(p.get("modifiers"));
        final boolean popupButton = button == MouseEvent.BUTTON3;
        double distance = plan.start.distance(plan.end);
        Integer stepsParam = Actions.optInt(p, "steps");
        final int moves = Math.max(1, Math.min(200, stepsParam != null ? stepsParam : (int) Math.max(4, Math.min(30, distance / 12))));
        Integer durationParam = Actions.optInt(p, "durationMs");
        int duration = Math.max(0, Math.min(10000, durationParam != null ? durationParam : DEFAULT_DURATION_MS));
        final Component d = plan.dispatcher;
        List<Runnable> steps = new ArrayList<Runnable>();
        steps.add(new Runnable() {
            public void run() {
                Pointer.arrive(d, plan.start, modifiers);
            }
        });
        steps.add(new Runnable() {
            public void run() {
                Window window = Keys.windowOf(plan.source);
                Component before = Keys.focusOwner(window);
                d.dispatchEvent(new MouseEvent(d, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), modifiers | downMask,
                        plan.start.x, plan.start.y, 1, popupButton && !Actions.WINDOWS, button));
                Keys.syncFocus(window, before);
            }
        });
        for (int i = 1; i <= moves; i++) {
            final int x = plan.start.x + (int) Math.round((plan.end.x - plan.start.x) * (double) i / moves);
            final int y = plan.start.y + (int) Math.round((plan.end.y - plan.start.y) * (double) i / moves);
            steps.add(new Runnable() {
                public void run() {
                    d.dispatchEvent(new MouseEvent(d, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
                            modifiers | downMask, x, y, 0, false, button));
                }
            });
        }
        steps.add(new Runnable() {
            public void run() {
                d.dispatchEvent(new MouseEvent(d, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), modifiers,
                        plan.end.x, plan.end.y, 1, popupButton && Actions.WINDOWS, button));
            }
        });
        String how = (modifiers != 0 ? " holding " + InputEvent.getModifiersExText(modifiers) : "")
                + (button == MouseEvent.BUTTON1 ? "" : button == MouseEvent.BUTTON3 ? " with the right button" : " with the middle button");
        String description = waited + "Dragged " + route(plan) + how + " (" + moves + " mouse moves, "
                + Math.round(distance) + " px)" + (plan.pressed != null ? "; the press landed on "
                + Actions.describe(plan.pressed) + ", which is on top there" : "");
        Actions.Result result = Actions.perform(description, steps, duration / (moves + 2));
        String outcome = "";
        if (result.settled && !result.failed) {
            try {
                outcome = outcome(plan);
            } catch (Exception e) {
                // only a hint
            }
        }
        return new Actions.Result(result.message + outcome + plan.note, result.settled, result.failed);
    }

    /** Longest wait for a component that repaints later (e.g. a map rendered in the background). */
    private static final long REPAINT_WAIT_MS = 1500;

    /**
     * After the drag, off the EDT: what changed for known components; for custom-painted ones
     * whether their picture changed, waiting a little for pictures rendered in the background.
     */
    private static String outcome(final Plan plan) throws Exception {
        StringBuilder sb = new StringBuilder();
        String after = Edt.call(new Callable<String>() {
            public String call() {
                return state(plan.source);
            }
        }, CHECK_TIMEOUT_MS);
        if (after != null) {
            sb.append('\n').append(Actions.describe(plan.source)).append(": ");
            sb.append(after.equals(plan.stateBefore) ? "unchanged, " + after : plan.stateBefore + " -> " + after);
            sb.append('.');
        }
        if (plan.pictureBefore == null) {
            return sb.toString();
        }
        long deadline = System.currentTimeMillis() + REPAINT_WAIT_MS;
        double percent;
        while (true) {
            BufferedImage now = Edt.call(new Callable<BufferedImage>() {
                public BufferedImage call() {
                    return plan.source.isShowing() ? Screenshots.render(plan.source) : null;
                }
            }, CHECK_TIMEOUT_MS);
            if (now == null) {
                return sb.toString();
            }
            percent = difference(plan.pictureBefore, now);
            if (percent > 0 || System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.sleep(150);
        }
        sb.append('\n').append(percent == 0 ? "Its picture did not change."
                : "Its picture changed (" + (percent < 1 ? "<1" : String.valueOf(Math.round(percent))) + "% of the pixels differ).");
        return sb.toString();
    }

    /** Components whose result is best told by their picture: custom-painted ones. */
    private static boolean pictured(Component c) {
        return !Naming.isJdkClass(c.getClass()) && c.getWidth() * (long) c.getHeight() <= 4000000L
                && c.getWidth() > 0 && c.getHeight() > 0 && !(c instanceof JSlider) && !(c instanceof JSplitPane)
                && !(c instanceof JList) && !(c instanceof JTable) && !(c instanceof JTree) && !(c instanceof JTextComponent);
    }

    /** Percentage of pixels that differ (0 only if none does). */
    private static double difference(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return 100;
        }
        int width = a.getWidth();
        int[] rowA = new int[width];
        int[] rowB = new int[width];
        long differ = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            a.getRGB(0, y, width, 1, rowA, 0, width);
            b.getRGB(0, y, width, 1, rowB, 0, width);
            for (int x = 0; x < width; x++) {
                if (rowA[x] != rowB[x]) {
                    differ++;
                }
            }
        }
        return differ * 100.0 / Math.max(1, (long) width * a.getHeight());
    }

    /** State a drag changes, for components that have one: slider value, divider, columns, scroll bar, frame position. */
    static String state(Component c) {
        if (c instanceof JSlider) {
            return "value " + ((JSlider) c).getValue();
        }
        if (c instanceof JSplitPane) {
            return "divider at " + ((JSplitPane) c).getDividerLocation();
        }
        if (c instanceof JTableHeader) {
            TableColumnModel columns = ((JTableHeader) c).getColumnModel();
            StringBuilder sb = new StringBuilder("columns ");
            for (int i = 0; i < columns.getColumnCount(); i++) {
                sb.append(i > 0 ? ", " : "").append(Text.quote(String.valueOf(columns.getColumn(i).getHeaderValue()), 30))
                        .append(' ').append(columns.getColumn(i).getWidth()).append("px");
            }
            return sb.toString();
        }
        if (c instanceof JScrollBar) {
            return "value " + ((JScrollBar) c).getValue();
        }
        if (c instanceof JInternalFrame) {
            Rectangle b = c.getBounds();
            return "at " + b.x + "," + b.y + " size " + b.width + "x" + b.height;
        }
        return null;
    }

    // ---------------------------------------------------------------- mode=transfer

    /** A drag-over event that reads the dragged data directly instead of through the native drag and drop. */
    private static final class DragOver extends DropTargetDragEvent {
        private final Transferable data;
        int accepted = DnDConstants.ACTION_NONE;
        boolean rejected;

        DragOver(DropTargetContext context, Point at, int action, int sourceActions, Transferable data) {
            super(context, at, action, sourceActions);
            this.data = data;
        }

        public DataFlavor[] getCurrentDataFlavors() {
            return data.getTransferDataFlavors();
        }

        public List<DataFlavor> getCurrentDataFlavorsAsList() {
            return Arrays.asList(getCurrentDataFlavors());
        }

        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return data.isDataFlavorSupported(flavor);
        }

        public Transferable getTransferable() {
            return data;
        }

        public void acceptDrag(int action) {
            accepted = action;
            rejected = false;
        }

        public void rejectDrag() {
            accepted = DnDConstants.ACTION_NONE;
            rejected = true;
        }
    }

    /** The drop, with the same direct access to the data; records what the target answered. */
    private static final class Drop extends DropTargetDropEvent {
        private final Transferable data;
        int accepted = DnDConstants.ACTION_NONE;
        boolean rejected;
        Boolean completed;

        Drop(DropTargetContext context, Point at, int action, int sourceActions, Transferable data) {
            super(context, at, action, sourceActions, true);
            this.data = data;
        }

        public DataFlavor[] getCurrentDataFlavors() {
            return data.getTransferDataFlavors();
        }

        public List<DataFlavor> getCurrentDataFlavorsAsList() {
            return Arrays.asList(getCurrentDataFlavors());
        }

        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return data.isDataFlavorSupported(flavor);
        }

        public Transferable getTransferable() {
            return data;
        }

        public void acceptDrop(int action) {
            accepted = action;
        }

        public void rejectDrop() {
            rejected = true;
        }

        public void dropComplete(boolean success) {
            completed = success;
        }

        public boolean isLocalTransfer() {
            return true;
        }
    }

    /** State of a transfer, shared by its EDT steps. */
    private static final class Transfer {
        JComponent exporter;
        TransferHandler handler;
        int action;
        int sourceActions;
        Transferable data;
        String dragged;
        Component dropComponent;
        DropTarget dropTarget;
        Point dropPoint;
        DragOver over;
        Drop drop;
        String location;
        String notes = "";
        boolean exportedEarly;
    }

    private Actions.Result transfer(final Plan plan, Map<String, String> p, String waited) throws Exception {
        final String actionParam = Text.isBlank(p.get("action")) ? null : p.get("action").trim().toLowerCase(Locale.ROOT);
        final int modifiers = Keys.modifiers(p.get("modifiers"));
        final Transfer t = new Transfer();
        t.exporter = plan.exporter;
        t.handler = plan.exporter.getTransferHandler();
        List<Runnable> steps = new ArrayList<Runnable>();
        // 1. Like the press that starts a drag: an item that is not selected gets selected first.
        if (plan.from.row >= 0 && !selected(plan.source, plan.from) && plan.source == plan.exporter) {
            steps.addAll(Actions.mouse(plan.source, new Point[]{plan.from.point}, 1, MouseEvent.BUTTON1, 0, null));
        }
        // 2. The drag starts: the source creates the data.
        steps.add(new Runnable() {
            public void run() {
                t.sourceActions = t.handler.getSourceActions(t.exporter);
                if (t.sourceActions == TransferHandler.NONE) {
                    throw new Actions.Stopped(Actions.describe(t.exporter) + " does not let anything be dragged from it now "
                            + "(its TransferHandler's getSourceActions returned NONE).");
                }
                t.action = dropAction(actionParam, modifiers, t.sourceActions, t.exporter);
                t.dragged = draggedText(t.exporter);
                t.data = createTransferable(t);
                if (t.data == null) {
                    throw new Actions.Stopped("Nothing was dragged: " + Actions.describe(t.exporter) + " created no data"
                            + " (createTransferable returned null" + (t.dragged == null ? "; is something selected?" : "") + ").");
                }
            }
        });
        // 3. The pointer leaves the source, enters the target and moves over it.
        steps.add(new Runnable() {
            public void run() {
                DropTarget sourceTarget = plan.exporter.getDropTarget();
                Point targetOnScreen = screen(plan.target, plan.to.point);
                Window window = Keys.windowOf(plan.target);
                Point inWindow = new Point(targetOnScreen.x - window.getLocationOnScreen().x,
                        targetOnScreen.y - window.getLocationOnScreen().y);
                Component deepest = SwingUtilities.getDeepestComponentAt(window, inWindow.x, inWindow.y);
                for (Component at = deepest; at != null; at = at.getParent()) {
                    if (at.getDropTarget() != null && at.getDropTarget().isActive()) {
                        t.dropComponent = at;
                        t.dropTarget = at.getDropTarget();
                        break;
                    }
                }
                if (sourceTarget != null && sourceTarget.isActive() && sourceTarget != t.dropTarget) {
                    Point start = plan.from.point;
                    if (plan.source != plan.exporter) {
                        start = SwingUtilities.convertPoint(plan.source, start, plan.exporter);
                    }
                    sourceTarget.dragEnter(new DragOver(sourceTarget.getDropTargetContext(), start, t.action,
                            t.sourceActions, t.data));
                    sourceTarget.dragExit(new DropTargetEvent(sourceTarget.getDropTargetContext()));
                }
                if (t.dropTarget == null) {
                    throw new Actions.Stopped("Nothing at " + Actions.describe(plan.target) + plan.to.what
                            + " accepts drops (no component there has a drop target).");
                }
                Point origin = t.dropComponent.getLocationOnScreen();
                t.dropPoint = new Point(targetOnScreen.x - origin.x, targetOnScreen.y - origin.y);
                t.over = new DragOver(t.dropTarget.getDropTargetContext(), t.dropPoint, t.action, t.sourceActions, t.data);
                t.dropTarget.dragEnter(t.over);
            }
        });
        steps.add(new Runnable() {
            public void run() {
                t.over = new DragOver(t.dropTarget.getDropTargetContext(), t.dropPoint, t.action, t.sourceActions, t.data);
                t.dropTarget.dragOver(t.over);
                t.location = dropLocation(t.dropComponent);
            }
        });
        // 4. The button is released: drop, or leave if the target refused.
        steps.add(new Runnable() {
            public void run() {
                if (t.over.rejected || t.over.accepted == DnDConstants.ACTION_NONE) {
                    t.dropTarget.dragExit(new DropTargetEvent(t.dropTarget.getDropTargetContext()));
                    return;
                }
                t.drop = new Drop(t.dropTarget.getDropTargetContext(), t.dropPoint, t.over.accepted, t.sourceActions, t.data);
                t.dropTarget.drop(t.drop);
            }
        });
        // 5. The drag ends: the source finishes the export (e.g. removes moved items).
        steps.add(new Runnable() {
            public void run() {
                boolean success = t.drop != null && Boolean.TRUE.equals(t.drop.completed) && !t.drop.rejected;
                int done = success ? (t.drop.accepted != DnDConstants.ACTION_NONE ? t.drop.accepted : t.action) : TransferHandler.NONE;
                if (t.exportedEarly) {
                    if (done == TransferHandler.MOVE) {
                        t.notes += "\nThe source's own (JDK) handler could not be told to finish the move on this Java "
                                + "version, so the data was copied, not moved.";
                    }
                    return;
                }
                exportDone(t, done);
            }
        });
        final String what = "from " + Actions.describe(plan.source) + plan.from.what + " to " + Actions.describe(plan.target)
                + plan.to.what;
        Actions.Result result = Actions.perform(waited + "Dragged " + what, steps);
        if (result.failed || !result.settled) {
            return result;
        }
        String message = Edt.call(new Callable<String>() {
            public String call() {
                return transferReport(t, what);
            }
        }, CHECK_TIMEOUT_MS);
        String head = waited.length() > 0 ? waited : "";
        boolean dropped = t.drop != null && Boolean.TRUE.equals(t.drop.completed);
        return new Actions.Result(head + message + t.notes, result.settled, !dropped);
    }

    private static boolean selected(Component c, Actions.Spot spot) {
        if (c instanceof JList) {
            return ((JList<?>) c).isSelectedIndex(spot.row);
        }
        if (c instanceof JTable) {
            return ((JTable) c).isRowSelected(spot.row);
        }
        return c instanceof JTree && ((JTree) c).isRowSelected(spot.row);
    }

    /** The drop action: action=move/copy/link, else as with the mouse (Ctrl copies, Shift moves, default move). */
    private static int dropAction(String requested, int modifiers, int sourceActions, Component source) {
        int action;
        if (requested != null) {
            action = requested.equals("move") ? TransferHandler.MOVE : requested.equals("copy") ? TransferHandler.COPY
                    : requested.equals("link") ? TransferHandler.LINK : -1;
            if (action < 0) {
                throw new Actions.Stopped("action must be move, copy or link");
            }
        } else if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0 && (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            action = TransferHandler.LINK;
        } else if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            action = TransferHandler.COPY;
        } else if ((sourceActions & TransferHandler.MOVE) != 0) {
            action = TransferHandler.MOVE;
        } else if ((sourceActions & TransferHandler.COPY) != 0) {
            action = TransferHandler.COPY;
        } else {
            action = TransferHandler.LINK;
        }
        if ((sourceActions & action) == 0) {
            throw new Actions.Stopped(Actions.describe(source) + " allows only " + actionNames(sourceActions)
                    + ", not " + actionNames(action) + ".");
        }
        return action;
    }

    private static String actionNames(int actions) {
        List<String> names = new ArrayList<String>();
        if ((actions & TransferHandler.MOVE) != 0) {
            names.add("move");
        }
        if ((actions & TransferHandler.COPY) != 0) {
            names.add("copy");
        }
        if ((actions & TransferHandler.LINK) != 0) {
            names.add("link");
        }
        return names.isEmpty() ? "none" : names.toString().replace("[", "").replace("]", "").replace(", ", "/");
    }

    /** What the user drags: the selected items or text. */
    private static String draggedText(Component c) {
        if (c instanceof JList) {
            JList<?> list = (JList<?>) c;
            int[] selected = list.getSelectedIndices();
            return selected.length == 0 ? null : quoted(selected, new ItemText() {
                public String text(int i) {
                    return Items.listText(list, i);
                }
            });
        }
        if (c instanceof JTable) {
            final JTable table = (JTable) c;
            int[] rows = table.getSelectedRows();
            return rows.length == 0 ? null : "rows [" + Items.ranges(rows, 10) + "]";
        }
        if (c instanceof JTree) {
            JTree tree = (JTree) c;
            return tree.getSelectionCount() == 0 ? null : Items.selectedPaths(tree, 5);
        }
        if (c instanceof JTextComponent) {
            String s = ((JTextComponent) c).getSelectedText();
            return s == null ? null : Text.quote(s, 60);
        }
        return null;
    }

    private interface ItemText {
        String text(int i);
    }

    private static String quoted(int[] indices, ItemText texts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, indices.length); i++) {
            sb.append(i > 0 ? ", " : "").append(Text.quote(texts.text(indices[i]), 40));
        }
        if (indices.length > 5) {
            sb.append(" (+").append(indices.length - 5).append(" more)");
        }
        return sb.toString();
    }

    /**
     * TransferHandler.createTransferable is protected (it is meant for the drag and drop machinery).
     * Called by reflection when the application overrides it or on Java 8; otherwise (a JDK handler
     * on Java 9+) through the public exportToClipboard with a private clipboard and COPY.
     */
    private static Transferable createTransferable(Transfer t) {
        Method create = protectedMethod(t.handler.getClass(), "createTransferable", JComponent.class);
        if (create != null && (JAVA_8 || !Naming.isJdkClass(create.getDeclaringClass()))) {
            try {
                create.setAccessible(true);
                return (Transferable) create.invoke(t.handler, t.exporter);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (Exception e) {
                // not accessible: use the public route below
            }
        }
        Clipboard clipboard = new Clipboard("swing2mcp drag");
        t.handler.exportToClipboard(t.exporter, clipboard, TransferHandler.COPY);
        t.exportedEarly = true;
        return clipboard.getContents(null);
    }

    private static void exportDone(Transfer t, int action) {
        Method done = protectedMethod(t.handler.getClass(), "exportDone", JComponent.class, Transferable.class, int.class);
        if (done == null || !(JAVA_8 || !Naming.isJdkClass(done.getDeclaringClass()))) {
            return; // the JDK's default does nothing
        }
        try {
            done.setAccessible(true);
            done.invoke(t.handler, t.exporter, t.data, action);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (Exception e) {
            t.notes += "\nCould not finish the export in the source: " + e;
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException(cause);
    }

    private static Method protectedMethod(Class<?> cls, String name, Class<?>... types) {
        for (Class<?> at = cls; at != null; at = at.getSuperclass()) {
            try {
                return at.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException e) {
                // look in the superclass
            }
        }
        return null;
    }

    /** The drop location a Swing component shows while something is dragged over it. */
    private static String dropLocation(Component c) {
        if (c instanceof JList) {
            JList.DropLocation dl = ((JList<?>) c).getDropLocation();
            if (dl == null) {
                return null;
            }
            int size = ((JList<?>) c).getModel().getSize();
            return dl.isInsert() ? (dl.getIndex() >= size ? "at the end (index " + dl.getIndex() + ")"
                    : "before item " + dl.getIndex() + " " + Text.quote(Items.listText((JList<?>) c, dl.getIndex()), 40))
                    : dl.getIndex() < 0 ? "on no item" : "on item " + dl.getIndex() + " "
                    + Text.quote(Items.listText((JList<?>) c, dl.getIndex()), 40);
        }
        if (c instanceof JTable) {
            JTable.DropLocation dl = ((JTable) c).getDropLocation();
            if (dl == null) {
                return null;
            }
            if (dl.isInsertRow()) {
                return dl.getRow() >= ((JTable) c).getRowCount() ? "at the end (row " + dl.getRow() + ")"
                        : "before row " + dl.getRow();
            }
            return dl.isInsertColumn() ? "before column " + dl.getColumn() : "on row " + dl.getRow() + " col " + dl.getColumn();
        }
        if (c instanceof JTree) {
            JTree.DropLocation dl = ((JTree) c).getDropLocation();
            if (dl == null || dl.getPath() == null) {
                return dl == null ? null : "on no node";
            }
            String path = Text.quote(Items.pathText((JTree) c, dl.getPath()));
            return dl.getChildIndex() < 0 ? "on node " + path : "into " + path + " at child " + dl.getChildIndex();
        }
        if (c instanceof JTextComponent) {
            JTextComponent.DropLocation dl = ((JTextComponent) c).getDropLocation();
            return dl == null ? null : "at text position " + dl.getIndex();
        }
        return null;
    }

    private static String transferReport(Transfer t, String what) {
        StringBuilder sb = new StringBuilder("Dragged ");
        if (t.dragged != null) {
            sb.append(t.dragged).append(' ');
        }
        sb.append(what).append(" (Swing data transfer, ").append(actionNames(t.action)).append(")");
        if (t.dropComponent != null && t.dropComponent != null) {
            sb.append("\nDrop target: ").append(Actions.describe(t.dropComponent))
                    .append(t.location != null ? ", " + t.location : " at " + t.dropPoint.x + "," + t.dropPoint.y).append('.');
        }
        if (t.drop == null) {
            sb.append("\nThe target refused the drop (its canImport said no for ").append(flavors(t.data))
                    .append(", ").append(actionNames(t.action)).append("). Nothing was imported; the source was told that "
                    + "the drag ended without a drop.");
        } else if (t.drop.rejected) {
            sb.append("\nThe target rejected the drop at the last moment; nothing was imported.");
        } else if (!Boolean.TRUE.equals(t.drop.completed)) {
            sb.append("\nThe target accepted the drop but its import failed (importData returned false or threw); "
                    + "the source was told that nothing was dropped.");
        } else {
            sb.append("\nThe target imported the data (").append(actionNames(t.drop.accepted)).append(")");
            sb.append(t.drop.accepted == TransferHandler.MOVE ? " and the source finished the move." : ".");
        }
        return sb.toString();
    }

    private static String flavors(Transferable data) {
        StringBuilder sb = new StringBuilder();
        for (DataFlavor f : data.getTransferDataFlavors()) {
            if (sb.length() > 120) {
                sb.append(", …");
                break;
            }
            sb.append(sb.length() > 0 ? ", " : "").append(f.getHumanPresentableName() != null ? f.getHumanPresentableName()
                    : f.getMimeType());
        }
        return sb.length() == 0 ? "data without flavors" : "data as " + sb;
    }
}
