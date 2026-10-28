package io.github.swing2mcp.agent;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out human-readable names for components. Built once per snapshot, on the EDT.
 *
 * Order: JLabel.setLabelFor, explicit accessible name, tooltip, a label to the left on
 * the same row or directly above, and finally the Java field that holds the component.
 */
final class Naming {

    private static final Map<Class<?>, Field[]> COMPONENT_FIELDS = new ConcurrentHashMap<Class<?>, Field[]>();
    private static final int MAX_LEFT_GAP = 250;
    private static final int MAX_ABOVE_GAP = 30;

    private final Map<Component, JLabel> labels = new IdentityHashMap<Component, JLabel>();
    private final Map<Component, Integer> labelDistance = new IdentityHashMap<Component, Integer>();
    private final Set<JLabel> consumedLabels = Collections.newSetFromMap(new IdentityHashMap<JLabel, Boolean>());
    private final Map<Component, String> fieldNames = new IdentityHashMap<Component, String>();
    private final Set<Object> scannedOwners = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

    static boolean isJdkClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("jdk.") || name.startsWith("apple.");
    }

    /** Components that get their name from a nearby label rather than from their own text. */
    static boolean isField(Component c) {
        return c instanceof JTextComponent || c instanceof JComboBox || c instanceof JList
                || c instanceof JSpinner || c instanceof JSlider || c instanceof JTable || c instanceof JTree;
    }

    void indexWindow(Window window) {
        List<JLabel> windowLabels = new ArrayList<JLabel>();
        List<Component> fields = new ArrayList<Component>();
        collect(window, windowLabels, fields);

        for (JLabel label : windowLabels) {
            Component target = label.getLabelFor();
            if (target != null) {
                consumedLabels.add(label);
                if (!labels.containsKey(target)) {
                    labels.put(target, label);
                    labelDistance.put(target, -1);
                }
            }
        }

        for (JLabel label : windowLabels) {
            if (label.getLabelFor() != null || Text.isBlank(Text.labelName(label.getText()))) {
                continue;
            }
            Rectangle lr = SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), window);
            Component best = null;
            int bestScore = Integer.MAX_VALUE;
            for (Component field : fields) {
                int score = score(lr, fieldBounds(field, window));
                if (score >= 0 && score < bestScore) {
                    best = field;
                    bestScore = score;
                }
            }
            if (best == null) {
                continue;
            }
            Integer previous = labelDistance.get(best);
            if (previous == null || (previous >= 0 && bestScore < previous)) {
                JLabel replaced = labels.put(best, label);
                if (replaced != null) {
                    consumedLabels.remove(replaced);
                }
                labelDistance.put(best, bestScore);
                consumedLabels.add(label);
            }
        }
    }

    /** Lower is better; -1 means "this label does not describe that field". */
    private static int score(Rectangle label, Rectangle field) {
        int labelCenterY = label.y + label.height / 2;
        boolean sameRow = labelCenterY >= field.y - 4 && labelCenterY <= field.y + field.height + 4;
        int gapLeft = field.x - (label.x + label.width);
        if (sameRow && gapLeft >= -4 && gapLeft <= MAX_LEFT_GAP) {
            return Math.max(0, gapLeft);
        }
        int gapAbove = field.y - (label.y + label.height);
        boolean alignedAbove = label.x >= field.x - 10 && label.x <= field.x + field.width;
        if (gapAbove >= -4 && gapAbove <= MAX_ABOVE_GAP && alignedAbove) {
            return 1000 + Math.max(0, gapAbove);
        }
        return -1;
    }

    /** A field inside a scroll pane is laid out by the scroll pane, so measure that instead. */
    private static Rectangle fieldBounds(Component field, Window window) {
        Component box = field;
        if (field.getParent() instanceof JViewport && field.getParent().getParent() != null) {
            box = field.getParent().getParent();
        }
        return SwingUtilities.convertRectangle(box.getParent(), box.getBounds(), window);
    }

    private static void collect(Container container, List<JLabel> labels, List<Component> fields) {
        for (Component child : container.getComponents()) {
            if (!child.isShowing()) {
                continue;
            }
            if (child instanceof JLabel) {
                labels.add((JLabel) child);
            } else if (isField(child)) {
                fields.add(child);
            }
            // Do not look inside composite widgets (combo editors, spinner text fields...).
            if (child instanceof Container && !(child instanceof JComboBox) && !(child instanceof JSpinner)) {
                collect((Container) child, labels, fields);
            }
        }
    }

    boolean isConsumed(JLabel label) {
        return consumedLabels.contains(label);
    }

    String nameFor(Component c) {
        JLabel label = labels.get(c);
        if (label != null && !Text.isBlank(Text.labelName(label.getText()))) {
            return Text.labelName(label.getText());
        }
        String accessible = c.getAccessibleContext() == null ? null : c.getAccessibleContext().getAccessibleName();
        if (!Text.isBlank(accessible) && !(c instanceof JTextComponent && accessible.equals(((JTextComponent) c).getText()))) {
            return Text.plain(accessible);
        }
        if (c instanceof JComponent && !Text.isBlank(((JComponent) c).getToolTipText())) {
            return Text.plain(((JComponent) c).getToolTipText());
        }
        return null;
    }

    /** Name for a button-like component: its text, then tooltip, then accessible name. */
    String buttonName(AbstractButton b) {
        String text = Text.plain(b.getText());
        if (!Text.isBlank(text)) {
            return text;
        }
        if (!Text.isBlank(b.getToolTipText())) {
            return Text.plain(b.getToolTipText());
        }
        String accessible = b.getAccessibleContext() == null ? null : b.getAccessibleContext().getAccessibleName();
        return Text.isBlank(accessible) ? null : Text.plain(accessible);
    }

    String fieldName(Component c) {
        return fieldNames.get(c);
    }

    /**
     * Records which application fields point at which components, e.g. that
     * {@code CustomerPanel.txtName} holds a given JTextField. Only application classes
     * are inspected, so this works on Java 8 through 21+ without --add-opens.
     */
    void scanOwner(Object owner) {
        if (owner == null || !scannedOwners.add(owner)) {
            return;
        }
        for (Class<?> type = owner.getClass(); type != null && !isJdkClass(type); type = type.getSuperclass()) {
            for (Field field : componentFields(type)) {
                try {
                    Object value = field.get(owner);
                    if (value instanceof Component && !fieldNames.containsKey(value)) {
                        fieldNames.put((Component) value, field.getName());
                    }
                } catch (Exception ignored) {
                    // inaccessible field; naming is best effort
                }
            }
        }
    }

    private static Field[] componentFields(Class<?> type) {
        Field[] cached = COMPONENT_FIELDS.get(type);
        if (cached != null) {
            return cached;
        }
        List<Field> result = new ArrayList<Field>();
        try {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || field.getName().indexOf('$') >= 0
                        || Modifier.isStatic(field.getModifiers())
                        || !Component.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    result.add(field);
                } catch (RuntimeException ignored) {
                    // e.g. InaccessibleObjectException on a module-protected class
                }
            }
        } catch (Throwable ignored) {
            // NoClassDefFoundError for an optional field type
        }
        cached = result.toArray(new Field[0]);
        COMPONENT_FIELDS.put(type, cached);
        return cached;
    }
}
