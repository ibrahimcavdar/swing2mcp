package io.github.ibrahimcavdar.swing2mcp.agent;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

/**
 * Applies freshly compiled classes to the running application.
 *
 * At start the agent records what every class file in the class directories looks like
 * (per-method bytecode hashes). On reload it compares the class files with that baseline,
 * redefines the loaded classes that changed, and works out which methods changed:
 * <ul>
 *   <li>only handler/painting/logic methods changed: nothing is rebuilt, the UI keeps its state;</li>
 *   <li>a screen's construction code changed (constructor, init method, or what they call):
 *       the screens of that class are rebuilt (see {@link Rebuilder}).</li>
 * </ul>
 * Structural changes (new or removed methods/fields, other super type) are refused on a standard
 * JVM before anything is applied, with an explanation; enhanced JVMs (JetBrains Runtime with
 * -XX:+AllowEnhancedClassRedefinition, DCEVM) get them applied.
 */
final class HotReload {

    private static final class Baseline {
        final long fileHash;
        /** null when the class file may already differ from what the JVM loaded. */
        final ClassInfo info;

        Baseline(long fileHash, ClassInfo info) {
            this.fileHash = fileHash;
            this.info = info;
        }
    }

    private static final class Change {
        final Class<?> loaded;
        final byte[] bytes;
        final ClassInfo before;
        final ClassInfo after;
        final Set<String> changedMethods = new TreeSet<String>();

        Change(Class<?> loaded, byte[] bytes, ClassInfo before, ClassInfo after) {
            this.loaded = loaded;
            this.bytes = bytes;
            this.before = before;
            this.after = after;
        }
    }

    /** File hash of a class file whose relation to the loaded class is not known. */
    private static final long UNKNOWN = Long.MIN_VALUE;

    private final Instrumentation inst;
    private final RefRegistry refs;
    private final long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final Map<String, Baseline> baseline = new HashMap<String, Baseline>();
    private final Set<File> scannedDirs = new HashSet<File>();
    private final Thread initialScan;

    HotReload(Instrumentation inst, RefRegistry refs) {
        this.inst = inst;
        this.refs = refs;
        // Record the baseline in the background so that application startup is not delayed.
        initialScan = new Thread(new Runnable() {
            public void run() {
                synchronized (HotReload.this) {
                    for (File dir : classDirectories()) {
                        scanBaseline(dir);
                    }
                }
            }
        }, "swing2mcp-baseline");
        initialScan.setDaemon(true);
        initialScan.setPriority(Thread.MIN_PRIORITY);
        initialScan.start();
    }

    /** "enhanced", "standard" or "unavailable", for /ping. */
    String capability() {
        if (inst == null || !inst.isRedefineClassesSupported()) {
            return "unavailable";
        }
        return enhancedRedefinition() ? "enhanced" : "standard";
    }

    // ------------------------------------------------------------------ reload

    Actions.Result reload(Map<String, String> params) throws Exception {
        if (inst == null || !inst.isRedefineClassesSupported()) {
            throw new IllegalArgumentException("This JVM does not allow redefining classes for the agent.");
        }
        initialScan.join(60000);
        String mode = Text.isBlank(params.get("rebuild")) ? "auto" : params.get("rebuild").trim().toLowerCase();
        if (!mode.equals("auto") && !mode.equals("none") && !mode.equals("all")) {
            throw new IllegalArgumentException("rebuild must be auto, none or all");
        }
        List<Component> explicit = resolveRefs(params.get("refs"));

        List<File> dirs = classDirectories();
        if (!Text.isBlank(params.get("classesDir"))) {
            File extra = new File(params.get("classesDir").trim()).getAbsoluteFile();
            if (!extra.isDirectory()) {
                throw new IllegalArgumentException("Not a directory: " + extra);
            }
            dirs.add(0, extra);
        }
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("No class directories on the classpath (the application runs from jars). "
                    + "Pass classesDir (e.g. target/classes), or start it with mvn swing2mcp:run.");
        }

        List<Change> changes = new ArrayList<Change>();
        int notLoaded = 0;
        synchronized (this) {
            Map<String, List<Class<?>>> loaded = loadedClasses();
            Set<File> classpathDirs = new HashSet<File>(classDirectories());
            OriginalBytes originals = new OriginalBytes(jvmStart);
            try {
                for (File dir : dirs) {
                    // Only a directory the classes were loaded from tells what the JVM runs. For any other
                    // directory (e.g. a module that runs from its jar) the original comes from the jar.
                    if (!scannedDirs.contains(dir) && classpathDirs.contains(dir)) {
                        scanBaseline(dir);
                    }
                    for (File file : classFiles(dir)) {
                        String name = className(dir, file);
                        byte[] bytes = read(file);
                        long hash = crc(bytes);
                        Baseline before = baseline.get(name);
                        if (before != null && before.fileHash == hash) {
                            continue;
                        }
                        List<Class<?>> classes = loaded.get(name);
                        if (classes == null) {
                            if (before != null && before.info != null) {
                                notLoaded++; // changed, but loads the new version when first used
                            }
                            baseline.put(name, new Baseline(hash, ClassInfo.parse(bytes)));
                            continue;
                        }
                        ClassInfo after = null;
                        for (Class<?> type : classes) {
                            ClassInfo beforeInfo = before == null ? null : before.info;
                            if (beforeInfo == null) {
                                // No usable record: read the class as it was loaded (from its jar or directory).
                                byte[] original = originals.of(type);
                                if (original != null && crc(original) == hash) {
                                    continue; // the same bytes the JVM runs: not a change
                                }
                                beforeInfo = original == null ? null : ClassInfo.parse(original);
                            }
                            if (after == null) {
                                after = ClassInfo.parse(bytes);
                            }
                            changes.add(new Change(type, bytes, beforeInfo, after));
                        }
                        if (after == null) {
                            baseline.put(name, new Baseline(hash, ClassInfo.parse(bytes)));
                        }
                    }
                }
            } finally {
                originals.close();
            }
            if (changes.isEmpty()) {
                return new Actions.Result("No changed classes to reload in " + dirs
                        + (notLoaded > 0 ? " (" + notLoaded + " changed classes are not loaded yet; they will be used as is)" : "")
                        + ". Did the compilation finish?", true);
            }

            List<String> structural = new ArrayList<String>();
            for (Change change : changes) {
                structural.addAll(structuralDifferences(change));
                change.changedMethods.addAll(changedMethods(change));
            }
            boolean enhanced = enhancedRedefinition();
            if (!structural.isEmpty() && !enhanced) {
                StringBuilder sb = new StringBuilder("Not reloaded. This JVM only allows changes inside existing methods, "
                        + "and these changes alter the shape of a class:\n");
                boolean modifiers = false;
                for (String s : structural) {
                    sb.append("  - ").append(s).append('\n');
                    modifiers |= s.contains(MODIFIERS_CHANGED);
                }
                if (modifiers) {
                    sb.append("Changed class modifiers in unchanged sources mean the classes were compiled by a different ")
                            .append("JDK or compiler than the running version (e.g. the IDE instead of Maven). Compile ")
                            .append("with the one that built the running application, or restart it.\n");
                }
                sb.append("Nothing was changed in the running application. Options: restart it (mvn swing2mcp:run), ")
                        .append("rewrite the change to stay inside existing methods, or run the application on JetBrains ")
                        .append("Runtime with -XX:+AllowEnhancedClassRedefinition.");
                return new Actions.Result(sb.toString(), true);
            }

            ClassDefinition[] definitions = new ClassDefinition[changes.size()];
            for (int i = 0; i < changes.size(); i++) {
                definitions[i] = new ClassDefinition(changes.get(i).loaded, changes.get(i).bytes);
            }
            try {
                inst.redefineClasses(definitions);
            } catch (Throwable t) {
                return new Actions.Result("Not reloaded: the JVM rejected the new classes (" + t
                        + "). Nothing was changed; restart the application to apply this change.", true);
            }
            for (Change change : changes) {
                baseline.put(change.loaded.getName(), new Baseline(crc(change.bytes), change.after));
            }
        }

        final Set<Class<?>> rebuildTypes = rebuildTypes(changes, mode);
        final List<Component> explicitTargets = explicit;
        final List<String> rebuilt = new ArrayList<String>();
        StringBuilder sb = new StringBuilder("Reloaded ").append(changes.size()).append(changes.size() == 1 ? " class" : " classes")
                .append(" into the running application:\n");
        for (Change change : changes) {
            sb.append("  ").append(change.loaded.getName()).append(": ")
                    .append(change.before == null ? "all methods (no earlier version recorded)"
                            : change.changedMethods.isEmpty() ? "no method bodies changed" : "changed " + describe(change.changedMethods))
                    .append('\n');
        }
        if (notLoaded > 0) {
            sb.append("  (").append(notLoaded).append(" other changed classes are not loaded yet; they need no reload)\n");
        }
        String description = sb.toString().trim();
        Actions.Result result = Actions.perform(description, new Runnable() {
            public void run() {
                rebuilt.addAll(rebuildOnEdt(rebuildTypes, explicitTargets));
                for (Window window : Window.getWindows()) {
                    window.repaint();
                }
            }
        });
        StringBuilder message = new StringBuilder(description);
        if (rebuilt.isEmpty()) {
            message.append("\nNo screen needed rebuilding: open screens kept their state (typed text, selections). ")
                    .append("Changed event handlers and logic take effect on the next action.");
        } else {
            message.append("\nScreens whose construction code changed (rebuilt ones lost their UI state):");
            for (String line : rebuilt) {
                message.append("\n  ").append(line);
            }
        }
        // perform() may have added notes, e.g. that the UI thread is still busy
        message.append(result.message.substring(description.length()));
        return new Actions.Result(message.toString(), result.settled);
    }

    // ------------------------------------------------------------------ deciding what to rebuild

    /** Component classes whose existing instances must be rebuilt. */
    private Set<Class<?>> rebuildTypes(List<Change> changes, String mode) {
        Set<Class<?>> result = new LinkedHashSet<Class<?>>();
        if (mode.equals("none")) {
            return result;
        }
        Map<String, Change> byName = new HashMap<String, Change>();
        for (Change change : changes) {
            byName.put(change.loaded.getName(), change);
        }
        for (Change change : changes) {
            if (!Component.class.isAssignableFrom(change.loaded)) {
                continue;
            }
            if (mode.equals("all") || constructionChanged(change, byName)) {
                result.add(change.loaded);
            }
        }
        return result;
    }

    /**
     * True when code that runs while the component is being built changed: constructors, the init
     * method, methods they call (transitively, within the class), and constructors of the classes
     * they instantiate (e.g. anonymous listeners created in initComponents).
     */
    private static boolean constructionChanged(Change change, Map<String, Change> byName) {
        if (change.before == null) {
            return true;
        }
        Set<String> roots = new HashSet<String>();
        for (String method : change.after.methods.keySet()) {
            if (method.startsWith("<init>")) {
                roots.add(method);
            }
        }
        Method init = Rebuilder.findInitMethod(change.loaded);
        if (init != null && init.getDeclaringClass() == change.loaded) {
            roots.add(init.getName() + "()V");
        }
        Set<String> reached = new HashSet<String>();
        List<String> queue = new ArrayList<String>(roots);
        Set<String> instantiated = new HashSet<String>();
        while (!queue.isEmpty()) {
            String method = queue.remove(queue.size() - 1);
            if (!reached.add(method)) {
                continue;
            }
            ClassInfo.MethodInfo info = change.after.methods.get(method);
            if (info == null) {
                continue;
            }
            queue.addAll(info.calls);
            instantiated.addAll(info.news);
        }
        for (String method : reached) {
            if (change.changedMethods.contains(method)) {
                return true;
            }
        }
        for (String type : instantiated) {
            Change nested = byName.get(type);
            if (nested != null && nested != change) {
                for (String method : nested.changedMethods) {
                    if (method.startsWith("<init>")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Runs on the EDT. */
    private List<String> rebuildOnEdt(Set<Class<?>> types, List<Component> explicit) {
        List<Component> targets = new ArrayList<Component>(explicit);
        if (!types.isEmpty()) {
            for (Window window : Window.getWindows()) {
                if (window.isDisplayable()) {
                    collectInstances(window, types, targets);
                }
            }
        }
        // Outer components first. A successfully rebuilt container rebuilds its children too, so
        // those are skipped; children of a container that could not be rebuilt are still tried.
        Collections.sort(targets, new java.util.Comparator<Component>() {
            public int compare(Component a, Component b) {
                return depth(a) - depth(b);
            }
        });
        Set<Component> done = Collections.newSetFromMap(new IdentityHashMap<Component, Boolean>());
        Set<Component> rebuilt = Collections.newSetFromMap(new IdentityHashMap<Component, Boolean>());
        List<String> lines = new ArrayList<String>();
        for (Component target : targets) {
            if (!done.add(target) || hasAncestorIn(target, rebuilt)) {
                continue;
            }
            String outcome = Rebuilder.rebuild(target);
            if (Rebuilder.succeeded(outcome)) {
                rebuilt.add(target);
            }
            String name = target.getClass().getName();
            lines.add(name.substring(name.lastIndexOf('.') + 1) + " [ref=" + refs.refFor(target) + "]: " + outcome);
        }
        return lines;
    }

    private static int depth(Component component) {
        int depth = 0;
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) {
            depth++;
        }
        return depth;
    }

    private static void collectInstances(Component component, Set<Class<?>> types, List<Component> into) {
        for (Class<?> type = component.getClass(); type != null && !Naming.isJdkClass(type); type = type.getSuperclass()) {
            if (types.contains(type)) {
                into.add(component);
                break;
            }
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collectInstances(child, types, into);
            }
        }
    }

    private static boolean hasAncestorIn(Component component, Set<Component> set) {
        for (Container parent = component.getParent(); parent != null; parent = parent.getParent()) {
            if (set.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ diffing

    private static Set<String> changedMethods(Change change) {
        Set<String> result = new TreeSet<String>();
        for (Map.Entry<String, ClassInfo.MethodInfo> method : change.after.methods.entrySet()) {
            ClassInfo.MethodInfo old = change.before == null ? null : change.before.methods.get(method.getKey());
            if (old == null || old.hash != method.getValue().hash) {
                result.add(method.getKey());
            }
        }
        return result;
    }

    /** Human-readable shape changes; compares with the loaded class when no baseline exists. */
    private static List<String> structuralDifferences(Change change) {
        String simple = change.after.name.substring(change.after.name.lastIndexOf('.') + 1);
        Set<String> oldMethods;
        Map<String, String> oldFields;
        String oldSuper;
        List<String> oldInterfaces;
        if (change.before != null) {
            oldMethods = change.before.methods.keySet();
            oldFields = change.before.fields;
            oldSuper = change.before.superName;
            oldInterfaces = change.before.interfaces;
        } else {
            oldMethods = new HashSet<String>();
            for (Method m : change.loaded.getDeclaredMethods()) {
                oldMethods.add(m.getName() + descriptor(m.getParameterTypes(), m.getReturnType()));
            }
            for (Constructor<?> c : change.loaded.getDeclaredConstructors()) {
                oldMethods.add("<init>" + descriptor(c.getParameterTypes(), void.class));
            }
            oldFields = new LinkedHashMap<String, String>();
            for (Field f : change.loaded.getDeclaredFields()) {
                oldFields.put(f.getName(), descriptor(f.getType()));
            }
            oldSuper = change.loaded.getSuperclass() == null ? null : change.loaded.getSuperclass().getName();
            oldInterfaces = new ArrayList<String>();
            for (Class<?> i : change.loaded.getInterfaces()) {
                oldInterfaces.add(i.getName());
            }
        }
        List<String> result = new ArrayList<String>();
        for (String method : change.after.methods.keySet()) {
            if (method.startsWith("<clinit>")) {
                continue; // reflection cannot see static initializers; they are not re-run anyway
            }
            if (!oldMethods.contains(method)) {
                result.add(simple + ": method added " + readable(method) + lambdaHint(method));
            }
        }
        for (String method : oldMethods) {
            if (!method.startsWith("<clinit>") && !change.after.methods.containsKey(method)) {
                result.add(simple + ": method removed " + readable(method) + lambdaHint(method));
            }
        }
        for (Map.Entry<String, String> field : change.after.fields.entrySet()) {
            String old = oldFields.get(field.getKey());
            if (old == null) {
                result.add(simple + ": field added " + field.getKey());
            } else if (!old.equals(field.getValue())) {
                result.add(simple + ": field type changed " + field.getKey());
            }
        }
        for (String field : oldFields.keySet()) {
            if (!change.after.fields.containsKey(field)) {
                result.add(simple + ": field removed " + field);
            }
        }
        if (oldSuper != null && !oldSuper.equals(change.after.superName)) {
            result.add(simple + ": superclass changed to " + change.after.superName);
        }
        if (!new HashSet<String>(oldInterfaces).equals(new HashSet<String>(change.after.interfaces))) {
            result.add(simple + ": implemented interfaces changed");
        }
        // Reflection reports nested classes with their source modifiers, not the class file flags the JVM
        // compares, so this check needs a recorded earlier version.
        if (change.before != null
                && (change.before.access & REDEFINE_CHECKED_FLAGS) != (change.after.access & REDEFINE_CHECKED_FLAGS)) {
            result.add(simple + MODIFIERS_CHANGED + "from [" + flags(change.before.access) + "] to ["
                    + flags(change.after.access) + "]");
        }
        return result;
    }

    /** Class file flags the JVM refuses to see changed on redefinition (JVM_RECOGNIZED_CLASS_MODIFIERS). */
    private static final int REDEFINE_CHECKED_FLAGS = 0x7631;

    private static final String MODIFIERS_CHANGED = ": class modifiers changed ";

    private static String flags(int access) {
        String[] names = {"public", "final", "super", "interface", "abstract", "synthetic", "annotation", "enum"};
        int[] bits = {0x0001, 0x0010, 0x0020, 0x0200, 0x0400, 0x1000, 0x2000, 0x4000};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length; i++) {
            if ((access & bits[i]) != 0) {
                sb.append(sb.length() == 0 ? "" : " ").append(names[i]);
            }
        }
        return sb.toString();
    }

    private static String lambdaHint(String method) {
        return method.startsWith("lambda$") ? " (a lambda; each lambda is a hidden method)" : "";
    }

    private static String readable(String method) {
        int paren = method.indexOf('(');
        String name = method.substring(0, paren);
        return (name.equals("<init>") ? "constructor" : name) + method.substring(paren);
    }

    private static String describe(Set<String> methods) {
        List<String> names = new ArrayList<String>();
        for (String method : methods) {
            String name = method.substring(0, method.indexOf('('));
            names.add(name.equals("<init>") ? "constructor" : name.equals("<clinit>") ? "static initializer (not re-run)" : name);
        }
        return new ArrayList<String>(new LinkedHashSet<String>(names)).toString().replace("[", "").replace("]", "");
    }

    static String descriptor(Class<?>[] parameters, Class<?> returnType) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : parameters) {
            sb.append(descriptor(p));
        }
        return sb.append(')').append(descriptor(returnType)).toString();
    }

    static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (type.isPrimitive()) {
            if (type == void.class) return "V";
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == int.class) return "I";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            return "D";
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    // ------------------------------------------------------------------ files and classes

    /** Directories of the classpath, including those listed in manifest Class-Path entries. */
    static List<File> classDirectories() {
        Set<File> result = new LinkedHashSet<File>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.length() == 0) {
                continue;
            }
            File file = new File(entry).getAbsoluteFile();
            if (file.isDirectory()) {
                result.add(file);
            } else if (file.isFile() && entry.endsWith(".jar")) {
                result.addAll(manifestDirectories(file));
            }
        }
        return new ArrayList<File>(result);
    }

    private static List<File> manifestDirectories(File jar) {
        List<File> result = new ArrayList<File>();
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(jar);
            Manifest manifest = jarFile.getManifest();
            String classPath = manifest == null ? null : manifest.getMainAttributes().getValue("Class-Path");
            if (classPath == null) {
                return result;
            }
            URL base = jar.getParentFile().toURI().toURL();
            for (String entry : classPath.trim().split("\\s+")) {
                try {
                    File file = new File(new URI(new URL(base, entry).toString()));
                    if (file.isDirectory()) {
                        result.add(file.getAbsoluteFile());
                    }
                } catch (Exception ignored) {
                    // not a local path
                }
            }
        } catch (IOException ignored) {
            // unreadable jar
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        return result;
    }

    /**
     * Records the current class files of a directory. A file written after the JVM started may
     * already differ from the loaded class: it is recorded as unknown, so the first reload applies
     * it and treats every method as changed.
     */
    private void scanBaseline(File dir) {
        scannedDirs.add(dir);
        for (File file : classFiles(dir)) {
            try {
                if (file.lastModified() > jvmStart) {
                    baseline.put(className(dir, file), new Baseline(UNKNOWN, null));
                    continue;
                }
                byte[] bytes = read(file);
                baseline.put(className(dir, file), new Baseline(crc(bytes), ClassInfo.parse(bytes)));
            } catch (IOException ignored) {
                // unreadable or not a class file
            }
        }
    }

    private Map<String, List<Class<?>>> loadedClasses() {
        Map<String, List<Class<?>>> result = new HashMap<String, List<Class<?>>>();
        for (Class<?> type : inst.getAllLoadedClasses()) {
            if (type.getClassLoader() == null || type.isArray() || !inst.isModifiableClass(type)) {
                continue;
            }
            List<Class<?>> list = result.get(type.getName());
            if (list == null) {
                list = new ArrayList<Class<?>>(1);
                result.put(type.getName(), list);
            }
            list.add(type);
        }
        return result;
    }

    private static List<File> classFiles(File dir) {
        List<File> result = new ArrayList<File>();
        collectClassFiles(dir, result);
        return result;
    }

    private static void collectClassFiles(File dir, List<File> into) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectClassFiles(child, into);
            } else if (child.getName().endsWith(".class") && !child.getName().equals("module-info.class")
                    && !child.getName().equals("package-info.class")) {
                into.add(child);
            }
        }
    }

    private static String className(File dir, File file) {
        String relative = file.getAbsolutePath().substring(dir.getAbsolutePath().length() + 1);
        return relative.substring(0, relative.length() - ".class".length()).replace(File.separatorChar, '.');
    }

    private static byte[] read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int n = in.read(bytes, offset, bytes.length - offset);
                if (n < 0) {
                    break;
                }
                offset += n;
            }
            return offset == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, offset);
        } finally {
            in.close();
        }
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (crc.getValue() << 32) ^ bytes.length;
    }

    private List<Component> resolveRefs(String value) {
        List<Component> result = new ArrayList<Component>();
        if (Text.isBlank(value)) {
            return result;
        }
        for (String ref : value.split("[,\\s]+")) {
            if (ref.length() == 0) {
                continue;
            }
            Component component = refs.resolve(ref);
            if (component == null) {
                throw new IllegalArgumentException("Unknown or disposed ref " + ref + "; take a new snapshot");
            }
            result.add(component);
        }
        return result;
    }

    /** JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition, or a DCEVM build. */
    static boolean enhancedRedefinition() {
        String vm = System.getProperty("java.vm.name", "") + " " + System.getProperty("java.vm.version", "");
        if (vm.toLowerCase().contains("dcevm") || vm.contains("Dynamic Code Evolution")) {
            return true;
        }
        try {
            Class<?> mxType = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object bean = ManagementFactory.class.getMethod("getPlatformMXBean", Class.class).invoke(null, mxType);
            Object option = mxType.getMethod("getVMOption", String.class).invoke(bean, "AllowEnhancedClassRedefinition");
            return "true".equals(option.getClass().getMethod("getValue").invoke(option));
        } catch (Throwable e) {
            return false; // option unknown on this JVM
        }
    }
}
