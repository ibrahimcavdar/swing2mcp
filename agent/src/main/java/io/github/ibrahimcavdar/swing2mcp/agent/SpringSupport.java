package io.github.ibrahimcavdar.swing2mcp.agent;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Finds the application's Spring context without any change to the application and uses it
 * to list beans and open screens directly. Spring is never referenced at compile time: the
 * agent must work with any Spring version, or without Spring at all.
 *
 * Search order: static fields of application classes (the classic "SpringContext holder"),
 * JVM shutdown hooks (registerShutdownHook), then the object graph behind the visible windows.
 */
final class SpringSupport {

    private static final String BEAN_FACTORY = "org.springframework.beans.factory.BeanFactory";
    private static final String LISTABLE = "org.springframework.beans.factory.ListableBeanFactory";
    private static final String SINGLETON_REGISTRY = "org.springframework.beans.factory.config.SingletonBeanRegistry";
    private static final String APPLICATION_CONTEXT = "org.springframework.context.ApplicationContext";
    private static final int MAX_GRAPH_OBJECTS = 20000;
    private static final int MAX_GRAPH_DEPTH = 10;

    private final Instrumentation inst;
    private WeakReference<Object> found = new WeakReference<Object>(null);
    private String foundVia;
    private boolean javaBaseOpened;

    SpringSupport(Instrumentation inst) {
        this.inst = inst;
    }

    // ---------------------------------------------------------------- endpoints

    String beans(String filter) throws Exception {
        final Object factory = beanFactory();
        String[] names = (String[]) call(factory, LISTABLE, "getBeanDefinitionNames", new Class<?>[0]);
        Arrays.sort(names);
        List<String> ui = new ArrayList<String>();
        List<String> other = new ArrayList<String>();
        int infrastructure = 0;
        for (final String name : names) {
            Class<?> type = beanType(factory, name);
            String typeName = type == null ? "?" : simpleName(type);
            if (name.startsWith("org.springframework.") || (type != null && type.getName().startsWith("org.springframework."))) {
                infrastructure++;
                continue;
            }
            if (!Text.isBlank(filter) && !(name + " " + typeName).toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }
            boolean prototype = Boolean.TRUE.equals(call(factory, BEAN_FACTORY, "isPrototype", new Class<?>[]{String.class}, name));
            StringBuilder line = new StringBuilder("  ").append(name).append(" : ").append(typeName)
                    .append(prototype ? " (prototype)" : " (singleton)");
            if (type != null && Component.class.isAssignableFrom(type)) {
                line.append(" ").append(swingKind(type));
                if (!prototype && isOnScreen(factory, name)) {
                    line.append(" [on screen]");
                }
                ui.add(line.toString());
            } else {
                other.add(line.toString());
            }
        }
        StringBuilder sb = new StringBuilder("Spring ").append(simpleName(found.get().getClass()))
                .append(" found via ").append(foundVia).append(".\n");
        sb.append("\nUI beans (open_spring_bean can show these directly):\n");
        appendLines(sb, ui);
        sb.append("\nOther beans:\n");
        appendLines(sb, other);
        if (infrastructure > 0) {
            sb.append("\n(").append(infrastructure).append(" Spring infrastructure beans hidden)\n");
        }
        return sb.toString();
    }

    /** Shows a window bean, or a panel bean in a new frame, without navigating the UI. */
    Actions.Result open(final String name) throws Exception {
        if (Text.isBlank(name)) {
            throw new IllegalArgumentException("Missing parameter: name");
        }
        final Object factory = beanFactory();
        Boolean exists = (Boolean) call(factory, BEAN_FACTORY, "containsBean", new Class<?>[]{String.class}, name);
        if (!exists) {
            throw new IllegalArgumentException("No bean named " + Text.quote(name) + "; see spring_beans");
        }
        Class<?> type = beanType(factory, name);
        if (type == null || !Component.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(Text.quote(name) + " is not a Swing window or panel ("
                    + (type == null ? "unknown type" : type.getName()) + ")");
        }
        java.awt.Dialog modal = Edt.call(new Callable<java.awt.Dialog>() {
            public java.awt.Dialog call() {
                return SnapshotBuilder.activeModalDialog();
            }
        }, 3000);
        if (modal != null) {
            throw new IllegalArgumentException("Blocked by modal dialog " + Text.quote(modal.getTitle()) + "; close it first");
        }
        final String[] outcome = new String[1];
        final String description = "Opened Spring bean " + Text.quote(name);
        Actions.Result result = Actions.perform(description, new Runnable() {
            public void run() {
                try {
                    outcome[0] = show(call(factory, BEAN_FACTORY, "getBean", new Class<?>[]{String.class}, name), name);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        // A modal window bean is still "opening" (nested loop) when perform returns, so outcome may be unset.
        return outcome[0] == null ? result
                : new Actions.Result(outcome[0] + result.message.substring(description.length()), result.settled);
    }

    /** Runs on the EDT. Returns what was done, for the reply. */
    private static String show(Object bean, String name) {
        if (bean instanceof Window) {
            Window window = (Window) bean;
            if (window.isShowing()) {
                window.toFront();
                return "Bean " + Text.quote(name) + " was already open; brought it to front";
            }
            if (window.getWidth() == 0) {
                window.pack();
            }
            window.setLocationRelativeTo(null);
            window.setVisible(true); // a modal dialog keeps the EDT in a nested loop; that is fine
            return "Opened window bean " + Text.quote(name);
        }
        Component component = (Component) bean;
        if (component.isShowing()) {
            Window window = SwingUtilities.getWindowAncestor(component);
            if (window != null) {
                window.toFront();
            }
            return "Bean " + Text.quote(name) + " is already on screen";
        }
        if (component.getParent() != null) {
            // Already part of the UI but hidden, e.g. in another tab: select that tab.
            for (Component c = component; c.getParent() != null; c = c.getParent()) {
                if (c.getParent() instanceof JTabbedPane) {
                    JTabbedPane tabs = (JTabbedPane) c.getParent();
                    tabs.setSelectedComponent(c);
                    return "Bean " + Text.quote(name) + " was in a hidden tab; selected it";
                }
            }
            return "Bean " + Text.quote(name) + " is part of a screen that is not visible; it was not moved";
        }
        JFrame frame = new JFrame("swing2mcp: " + name);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(component);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        return "Opened panel bean " + Text.quote(name) + " in a new frame " + Text.quote(frame.getTitle());
    }

    // ---------------------------------------------------------------- locating the context

    /** The bean factory behind the found context; throws when the application has no Spring. */
    private Object beanFactory() throws Exception {
        Object target = locate();
        if (target == null) {
            throw new IllegalArgumentException("No Spring context found in this application. Spring features are "
                    + "not available; snapshot, click and the other tools work as usual.");
        }
        if (implementsType(target.getClass(), APPLICATION_CONTEXT)) {
            return call(target, APPLICATION_CONTEXT, "getAutowireCapableBeanFactory", new Class<?>[0]);
        }
        return target;
    }

    private synchronized Object locate() throws Exception {
        Object cached = found.get();
        if (cached != null && isActive(cached)) {
            return cached;
        }
        found = new WeakReference<Object>(null);
        Object context = fromStaticFields();
        if (context == null) {
            context = fromShutdownHooks();
        }
        if (context == null) {
            context = Edt.call(new Callable<Object>() {
                public Object call() {
                    return fromWindows();
                }
            }, 5000);
        }
        if (context != null) {
            found = new WeakReference<Object>(context);
        }
        return context;
    }

    private Object fromStaticFields() {
        Object factoryOnly = null;
        for (Class<?> type : inst.getAllLoadedClasses()) {
            String name = type.getName();
            if (Naming.isJdkClass(type) || name.startsWith("org.springframework.")
                    || name.startsWith("io.github.ibrahimcavdar.swing2mcp.") || type.isArray()) {
                continue;
            }
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable e) {
                continue; // classes with unresolvable field types
            }
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers()) || !implementsType(field.getType(), BEAN_FACTORY)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null && isActive(value)) {
                        if (implementsType(value.getClass(), APPLICATION_CONTEXT)) {
                            foundVia = "static field " + simpleName(type) + "." + field.getName();
                            return value;
                        }
                        if (factoryOnly == null) {
                            factoryOnly = value;
                            foundVia = "static field " + simpleName(type) + "." + field.getName();
                        }
                    }
                } catch (Throwable ignored) {
                    // not readable; keep looking
                }
            }
        }
        return factoryOnly;
    }

    /** Spring's registerShutdownHook() leaves a JVM shutdown hook that references the context. */
    private Object fromShutdownHooks() {
        try {
            openJavaLang();
            Field hooksField = Class.forName("java.lang.ApplicationShutdownHooks").getDeclaredField("hooks");
            hooksField.setAccessible(true);
            Map<?, ?> hooks;
            synchronized (Class.forName("java.lang.ApplicationShutdownHooks")) {
                hooks = new IdentityHashMap<Object, Object>((Map<?, ?>) hooksField.get(null));
            }
            for (Object hook : hooks.keySet()) {
                // Spring 3-5: anonymous Thread subclass (this$0); Spring 6: lambda Runnable passed to Thread.
                Object context = contextInFields(hook);
                if (context == null) {
                    context = contextInFields(runnableOf((Thread) hook));
                }
                if (context != null) {
                    foundVia = "the JVM shutdown hook " + Text.quote(((Thread) hook).getName());
                    return context;
                }
            }
        } catch (Throwable ignored) {
            // hooks not accessible on this JVM
        }
        return null;
    }

    /** The Runnable a Thread was created with: Thread.target up to Java 18, Thread.holder.task from 19. */
    private static Object runnableOf(Thread thread) throws Exception {
        try {
            Field target = Thread.class.getDeclaredField("target");
            target.setAccessible(true);
            return target.get(thread);
        } catch (NoSuchFieldException e) {
            Field holder = Thread.class.getDeclaredField("holder");
            holder.setAccessible(true);
            Object fields = holder.get(thread);
            Field task = fields.getClass().getDeclaredField("task");
            task.setAccessible(true);
            return task.get(fields);
        }
    }

    private static Object contextInFields(Object holder) throws IllegalAccessException {
        if (holder == null) {
            return null;
        }
        for (Class<?> type = holder.getClass(); type != null && type != Thread.class && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(holder);
                if (value != null && implementsType(value.getClass(), APPLICATION_CONTEXT)) {
                    return value;
                }
            }
        }
        return null;
    }

    /** Breadth-first walk over application objects reachable from the visible windows. Runs on the EDT. */
    private Object fromWindows() {
        Deque<Object[]> queue = new ArrayDeque<Object[]>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Window window : Window.getWindows()) {
            queue.add(new Object[]{window, 0});
        }
        Object factoryOnly = null;
        while (!queue.isEmpty() && seen.size() < MAX_GRAPH_OBJECTS) {
            Object[] entry = queue.poll();
            Object object = entry[0];
            int depth = (Integer) entry[1];
            if (object == null || !seen.add(object)) {
                continue;
            }
            Class<?> type = object.getClass();
            if (implementsType(type, APPLICATION_CONTEXT) && isActive(object)) {
                foundVia = "the object graph of the visible windows";
                return object;
            }
            if (factoryOnly == null && implementsType(type, BEAN_FACTORY) && isActive(object)) {
                factoryOnly = object;
                foundVia = "the object graph of the visible windows (bean factory only)";
            }
            if (depth >= MAX_GRAPH_DEPTH) {
                continue;
            }
            if (object instanceof Container) {
                for (Component child : ((Container) object).getComponents()) {
                    queue.add(new Object[]{child, depth + 1});
                }
            }
            if (type.isArray() && !type.getComponentType().isPrimitive()) {
                Object[] items = (Object[]) object;
                for (int i = 0; i < Math.min(items.length, 200); i++) {
                    queue.add(new Object[]{items[i], depth + 1});
                }
                continue;
            }
            for (Class<?> k = type; k != null && !Naming.isJdkClass(k); k = k.getSuperclass()) {
                for (Field field : k.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        queue.add(new Object[]{field.get(object), depth + 1});
                    } catch (Throwable ignored) {
                        // inaccessible field
                    }
                }
            }
        }
        return factoryOnly;
    }

    /** On Java 9+, java.lang internals are closed; Instrumentation can open them to this agent. */
    private void openJavaLang() {
        if (javaBaseOpened) {
            return;
        }
        javaBaseOpened = true;
        try {
            Method getModule = Class.class.getMethod("getModule");
            Object javaBase = getModule.invoke(Object.class);
            Object agentModule = getModule.invoke(SpringSupport.class);
            Class<?> moduleType = Class.forName("java.lang.Module");
            Method redefine = Instrumentation.class.getMethod("redefineModule", moduleType, Set.class, Map.class,
                    Map.class, Set.class, Map.class);
            Map<String, Set<Object>> opens = new HashMap<String, Set<Object>>();
            opens.put("java.lang", new HashSet<Object>(Collections.singleton(agentModule)));
            redefine.invoke(inst, javaBase, Collections.emptySet(), Collections.emptyMap(), opens,
                    Collections.emptySet(), Collections.emptyMap());
        } catch (NoSuchMethodException e) {
            // Java 8: no modules, nothing to open
        } catch (Exception ignored) {
            // the shutdown-hook strategy will simply find nothing
        }
    }

    // ---------------------------------------------------------------- reflection helpers

    /** A closed context is not worth returning; plain bean factories have no such state. */
    private static boolean isActive(Object candidate) {
        try {
            Method isActive = candidate.getClass().getMethod("isActive");
            return Boolean.TRUE.equals(isActive.invoke(candidate));
        } catch (Exception e) {
            return true;
        }
    }

    private static Class<?> beanType(Object factory, String name) {
        try {
            return (Class<?>) call(factory, BEAN_FACTORY, "getType", new Class<?>[]{String.class}, name);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isOnScreen(Object factory, String name) {
        try {
            if (!Boolean.TRUE.equals(call(factory, SINGLETON_REGISTRY, "containsSingleton", new Class<?>[]{String.class}, name))) {
                return false; // not created yet; do not create it just to look
            }
            Object bean = call(factory, BEAN_FACTORY, "getBean", new Class<?>[]{String.class}, name);
            return bean instanceof Component && ((Component) bean).isShowing();
        } catch (Exception e) {
            return false;
        }
    }

    /** Invokes a method through the named Spring interface, so non-public implementation classes are fine. */
    static Object call(Object target, String interfaceName, String method, Class<?>[] types, Object... args)
            throws Exception {
        Class<?> api = findType(target.getClass(), interfaceName);
        if (api == null) {
            throw new IllegalStateException(target.getClass().getName() + " is not a " + interfaceName);
        }
        try {
            return api.getMethod(method, types).invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
        }
    }

    static boolean implementsType(Class<?> type, String name) {
        return findType(type, name) != null;
    }

    private static Class<?> findType(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        if (type.getName().equals(name)) {
            return type;
        }
        for (Class<?> iface : type.getInterfaces()) {
            Class<?> found = findType(iface, name);
            if (found != null) {
                return found;
            }
        }
        return findType(type.getSuperclass(), name);
    }

    private static String swingKind(Class<?> type) {
        if (java.awt.Dialog.class.isAssignableFrom(type)) {
            return "window:dialog";
        }
        if (Window.class.isAssignableFrom(type)) {
            return "window";
        }
        return JComponent.class.isAssignableFrom(type) ? "panel" : "component";
    }

    /** Simple class name without CGLIB/proxy suffixes. */
    private static String simpleName(Class<?> type) {
        String name = type.getName();
        int proxy = name.indexOf("$$");
        if (proxy > 0) {
            name = name.substring(0, proxy);
        }
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static void appendLines(StringBuilder sb, List<String> lines) {
        if (lines.isEmpty()) {
            sb.append("  (none)\n");
        }
        for (String line : lines) {
            sb.append(line).append('\n');
        }
    }
}
