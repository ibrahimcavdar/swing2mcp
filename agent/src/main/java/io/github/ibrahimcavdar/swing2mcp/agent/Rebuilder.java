package io.github.ibrahimcavdar.swing2mcp.agent;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JViewport;
import javax.swing.RootPaneContainer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagLayout;
import java.awt.LayoutManager;
import java.awt.Window;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Rebuilds an existing screen after its construction code was reloaded. Must run on the EDT.
 *
 * Preferred: in place. The component is emptied and its own init method is called again
 * (initComponents, jbInit, $$$setupUI$$$, @PostConstruct ...). The object stays the same, so
 * fields elsewhere that point to it remain valid. Otherwise, for panels with a no-argument
 * constructor, a new instance replaces the old one in its parent, and application fields that
 * pointed to the old instance are updated.
 */
final class Rebuilder {

    /** Common names of UI construction methods: NetBeans, JBuilder, IntelliJ GUI designer, hand-written. */
    private static final String[] INIT_NAMES = {"initComponents", "jbInit", "$$$setupUI$$$", "initUI", "initGUI",
            "initGui", "initUi", "createUI", "buildUI", "createComponents", "setupUI", "initialize", "init"};

    private Rebuilder() {
    }

    /** No-argument instance method that builds the UI, searched through application superclasses. */
    static Method findInitMethod(Class<?> type) {
        for (Class<?> k = type; k != null && !Naming.isJdkClass(k); k = k.getSuperclass()) {
            for (Method method : k.getDeclaredMethods()) {
                if (method.getParameterTypes().length == 0 && !Modifier.isStatic(method.getModifiers())
                        && hasAnnotation(method, "PostConstruct")) {
                    return method;
                }
            }
        }
        for (String name : INIT_NAMES) {
            for (Class<?> k = type; k != null && !Naming.isJdkClass(k); k = k.getSuperclass()) {
                try {
                    Method method = k.getDeclaredMethod(name);
                    if (!Modifier.isStatic(method.getModifiers())) {
                        return method;
                    }
                } catch (NoSuchMethodException ignored) {
                    // try the next name
                }
            }
        }
        return null;
    }

    private static boolean hasAnnotation(Method method, String simpleName) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    static boolean succeeded(String outcome) {
        return outcome.startsWith(REBUILT) || outcome.startsWith(REPLACED);
    }

    private static final String REBUILT = "rebuilt in place";
    private static final String REPLACED = "replaced by a new instance";

    /** Returns what was done, for the reply. */
    static String rebuild(Component component) {
        Method init = findInitMethod(component.getClass());
        if (init != null) {
            return inPlace(component, init);
        }
        if (component instanceof Window) {
            return "not rebuilt: no init method found (its UI is built in the constructor). Close and reopen it, "
                    + "or restart the application.";
        }
        return replace(component);
    }

    private static String inPlace(Component component, Method init) {
        Container target = component instanceof RootPaneContainer
                ? ((RootPaneContainer) component).getContentPane() : (Container) component;
        target.removeAll();
        try {
            init.setAccessible(true);
            init.invoke(component);
        } catch (InvocationTargetException e) {
            return "rebuild failed: " + init.getName() + "() threw " + e.getCause();
        } catch (Exception e) {
            return "rebuild failed: cannot call " + init.getName() + "(): " + e;
        }
        component.invalidate();
        if (component instanceof Window) {
            component.validate();
        } else if (component instanceof JComponent) {
            ((JComponent) component).revalidate();
        }
        component.repaint();
        return REBUILT + " by calling " + init.getName() + "() again";
    }

    private static String replace(Component old) {
        Container parent = old.getParent();
        if (parent == null) {
            return "not rebuilt: it is not part of a window";
        }
        Constructor<?> constructor;
        try {
            constructor = old.getClass().getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            return "not rebuilt: no init method and no no-argument constructor. Reopen the screen or restart.";
        }
        Component fresh;
        try {
            constructor.setAccessible(true);
            fresh = (Component) constructor.newInstance();
        } catch (InvocationTargetException e) {
            return "rebuild failed: the constructor threw " + e.getCause();
        } catch (Exception e) {
            return "rebuild failed: " + e;
        }
        if (parent instanceof JViewport && parent.getParent() instanceof JScrollPane) {
            ((JScrollPane) parent.getParent()).setViewportView(fresh);
        } else if (parent instanceof JTabbedPane) {
            JTabbedPane tabs = (JTabbedPane) parent;
            tabs.setComponentAt(tabs.indexOfComponent(old), fresh);
        } else {
            LayoutManager layout = parent.getLayout();
            Object constraints = null;
            if (layout instanceof BorderLayout) {
                constraints = ((BorderLayout) layout).getConstraints(old);
            } else if (layout instanceof GridBagLayout) {
                constraints = ((GridBagLayout) layout).getConstraints(old);
            }
            int index = indexOf(parent, old);
            parent.remove(index);
            parent.add(fresh, constraints, index);
        }
        int updated = updateReferences(parent, old, fresh);
        parent.validate();
        parent.repaint();
        return REPLACED + " (no init method)" + (updated > 0 ? "; updated " + updated
                + " field reference(s) to it" : "");
    }

    private static int indexOf(Container parent, Component child) {
        Component[] children = parent.getComponents();
        for (int i = 0; i < children.length; i++) {
            if (children[i] == child) {
                return i;
            }
        }
        return -1;
    }

    /** Points application fields of the enclosing components from the old instance to the new one. */
    private static int updateReferences(Container from, Component old, Component fresh) {
        int count = 0;
        for (Component owner = from; owner != null; owner = owner.getParent()) {
            for (Class<?> k = owner.getClass(); k != null && !Naming.isJdkClass(k); k = k.getSuperclass()) {
                for (Field field : k.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !field.getType().isInstance(fresh)) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        if (field.get(owner) == old) {
                            field.set(owner, fresh);
                            count++;
                        }
                    } catch (Exception ignored) {
                        // final or inaccessible field
                    }
                }
            }
        }
        return count;
    }
}
