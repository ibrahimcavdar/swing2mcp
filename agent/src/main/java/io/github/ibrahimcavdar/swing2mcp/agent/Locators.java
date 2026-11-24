package io.github.ibrahimcavdar.swing2mcp.agent;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Finds a component by what the snapshot says about it, for callers that cannot keep a ref from one run
 * of the application to the next (a test that is run again later). Wherever a ref is accepted, a locator
 * is too. It is written like the start of a snapshot line:
 *
 * <pre>
 *   button "Save"                      role and name
 *   field=txtName                      the Java field that holds the component
 *   textfield field=txtName            both
 *   dialog "Confirm" &gt; button "OK"     inside something (a window, group, panel, tabs...)
 *   button "..." #2                    the second match, in snapshot order
 * </pre>
 *
 * A locator must match exactly one component. None (yet) is waited for like a component that is not
 * usable yet; several is an error at once, because waiting does not make a locator less ambiguous.
 */
final class Locators {

    /** The first word of every snapshot line that describes a component. */
    static final List<String> ROLES = Arrays.asList(
            "frame", "dialog", "window", "internalframe", "desktop", "filechooser",
            "menubar", "menu", "menuitem", "checkmenuitem", "radiomenuitem", "popupmenu",
            "toolbar", "group", "panel", "tabs", "splitpane",
            "label", "image", "button", "togglebutton", "checkbox", "radio",
            "textfield", "passwordfield", "textarea", "textpane",
            "combobox", "list", "table", "tree", "spinner", "slider", "progressbar",
            "custom", "canvas");

    /** Roles whose line names the component's class before its text. */
    private static final List<String> CLASS_FIRST = Arrays.asList("custom", "panel", "canvas");
    private static final Pattern REF = Pattern.compile("e\\d+");
    private static final int LISTED = 12;

    private final RefRegistry refs;

    Locators(RefRegistry refs) {
        this.refs = refs;
    }

    static boolean isRef(String target) {
        return REF.matcher(target.trim()).matches();
    }

    /** One part of a locator, between " > ". */
    private static final class Step {
        String role;
        String name;
        String field;

        boolean matches(SnapshotBuilder.Entry entry) {
            if (role != null && !role.equals(roleOf(entry.line))) {
                return false;
            }
            if (field != null && !field.equals(entry.field)) {
                return false;
            }
            return name == null || sameName(name, nameOf(entry.line));
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(role == null ? "" : role);
            if (name != null) {
                sb.append(sb.length() > 0 ? " " : "").append(Text.quote(name));
            }
            if (field != null) {
                sb.append(sb.length() > 0 ? " " : "").append("field=").append(field);
            }
            return sb.toString();
        }
    }

    /** What a locator found: the component and its part of the snapshot. */
    static final class Found {
        final Component component;
        final String text;

        Found(Component component, String text) {
            this.component = component;
            this.text = text;
        }
    }

    /**
     * The component a ref or locator stands for. Call from a request thread: a locator that matches
     * nothing yet is waited for, up to timeoutMs.
     */
    Component resolve(final String target, Map<String, String> p) throws Exception {
        if (isRef(target)) {
            return byRef(target);
        }
        return Actions.whenUsable(p, new StringBuilder(), new Callable<Component>() {
            public Component call() {
                return find(target).component;
            }
        });
    }

    /** As {@link #resolve}, but on the EDT and without waiting: nothing matching is a {@link Actions.NotReady}. */
    Component now(String target) {
        return isRef(target) ? byRef(target) : find(target).component;
    }

    private Component byRef(String ref) {
        Component c = refs.resolve(ref.trim());
        if (c == null) {
            throw new IllegalArgumentException("Unknown or disposed ref " + ref.trim() + "; take a new snapshot");
        }
        return c;
    }

    /** Must run on the EDT. */
    Found find(String locator) {
        List<Step> steps = new ArrayList<Step>();
        int nth = parse(locator, steps);
        SnapshotBuilder.Options options = new SnapshotBuilder.Options();
        options.expandMenus = true; // items of closed menus can be clicked, so they can be found
        SnapshotBuilder snapshot = new SnapshotBuilder(refs, options).collectEntries();
        snapshot.build(null);
        Step last = steps.get(steps.size() - 1);
        List<SnapshotBuilder.Entry> matches = new ArrayList<SnapshotBuilder.Entry>();
        List<SnapshotBuilder.Entry> sameRole = new ArrayList<SnapshotBuilder.Entry>();
        for (SnapshotBuilder.Entry entry : snapshot.entries()) {
            if (!inside(entry.parent, steps, steps.size() - 2)) {
                continue;
            }
            if (last.matches(entry)) {
                matches.add(entry);
            } else if (last.role != null && last.role.equals(roleOf(entry.line))) {
                sameRole.add(entry);
            }
        }
        String shown = shown(steps);
        if (matches.isEmpty() || nth > matches.size()) {
            throw new Actions.NotReady(nth > 0 && !matches.isEmpty()
                    ? "only " + matches.size() + " on screen match " + shown + ", not " + nth
                    : "nothing on screen matches " + shown, others(last, sameRole));
        }
        if (nth == 0 && matches.size() > 1) {
            StringBuilder sb = new StringBuilder().append(matches.size()).append(" components match ").append(shown)
                    .append(":");
            for (int i = 0; i < Math.min(matches.size(), LISTED); i++) {
                sb.append("\n  #").append(i + 1).append("  ").append(where(matches.get(i)));
            }
            throw new IllegalArgumentException(sb.append("\nSay which: put what it is inside in front (e.g. dialog "
                    + "\"Title\" > ").append(last).append("), add field=..., or #1..#").append(matches.size())
                    .append(" for one of them in this order.").toString());
        }
        SnapshotBuilder.Entry entry = matches.get(nth == 0 ? 0 : nth - 1);
        return new Found(entry.component, snapshot.textOf(entry));
    }

    /** Whether the steps up to index i match ancestors of an entry, outermost first. */
    private static boolean inside(SnapshotBuilder.Entry from, List<Step> steps, int i) {
        if (i < 0) {
            return true;
        }
        for (SnapshotBuilder.Entry ancestor = from; ancestor != null; ancestor = ancestor.parent) {
            if (steps.get(i).matches(ancestor) && inside(ancestor.parent, steps, i - 1)) {
                return true;
            }
        }
        return false;
    }

    /** For the error when nothing matches: what there is instead. */
    private static String others(Step last, List<SnapshotBuilder.Entry> sameRole) {
        if (last.role == null) {
            return "";
        }
        if (sameRole.isEmpty()) {
            return "; there is no other " + last.role + " there either";
        }
        Set<String> names = new LinkedHashSet<String>();
        for (SnapshotBuilder.Entry entry : sameRole) {
            String name = nameOf(entry.line);
            names.add(name != null ? Text.quote(name) : entry.field != null ? "field=" + entry.field : "(no name)");
        }
        StringBuilder sb = new StringBuilder("; ").append(last.role).append(" there: ");
        int count = 0;
        for (String name : names) {
            if (count == LISTED) {
                sb.append(", … ").append(names.size() - LISTED).append(" more");
                break;
            }
            sb.append(count++ > 0 ? ", " : "").append(name);
        }
        return sb.toString();
    }

    private static String where(SnapshotBuilder.Entry entry) {
        String line = entry.line.length() > 100 ? entry.line.substring(0, 100) + "…" : entry.line;
        SnapshotBuilder.Entry top = entry;
        while (top.parent != null) {
            top = top.parent;
        }
        if (top == entry) {
            return line;
        }
        String name = nameOf(top.line);
        return line + "  (in " + roleOf(top.line) + (name == null ? "" : " " + Text.quote(name)) + ")";
    }

    private static String shown(List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (Step step : steps) {
            sb.append(sb.length() > 0 ? " > " : "").append(step);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- snapshot lines

    static String roleOf(String line) {
        int end = 0;
        while (end < line.length() && line.charAt(end) != ' ' && line.charAt(end) != '[') {
            end++;
        }
        return line.substring(0, end);
    }

    /** The quoted name right after the role (and the class, where the line has one), or null. */
    static String nameOf(String line) {
        String role = roleOf(line);
        int at = role.length();
        if (CLASS_FIRST.contains(role)) {
            at = skipSpaces(line, at);
            while (at < line.length() && line.charAt(at) != ' ' && line.charAt(at) != '[') {
                at++;
            }
        }
        at = skipSpaces(line, at);
        if (at >= line.length() || line.charAt(at) != '"') {
            return null;
        }
        StringBuilder name = new StringBuilder();
        readQuoted(line, at, name);
        return name.toString();
    }

    /** Snapshot lines cut long names short and end them with "…". */
    private static boolean sameName(String wanted, String shown) {
        if (shown == null) {
            return false;
        }
        if (shown.endsWith("…") && wanted.length() >= shown.length()) {
            return wanted.startsWith(shown.substring(0, shown.length() - 1));
        }
        return wanted.trim().equals(shown.trim());
    }

    // ---------------------------------------------------------------- parsing

    /** Fills the steps and returns the number after "#", or 0. */
    private static int parse(String locator, List<Step> steps) {
        Step step = new Step();
        int nth = 0;
        int at = 0;
        while (true) {
            at = skipSpaces(locator, at);
            if (at >= locator.length()) {
                break;
            }
            if (nth > 0) {
                throw bad(locator, "#" + nth + " must be the last part");
            }
            char c = locator.charAt(at);
            if (c == '>') {
                steps.add(checked(step, locator));
                step = new Step();
                at++;
            } else if (c == '"') {
                if (step.name != null) {
                    throw bad(locator, "two names in one part");
                }
                StringBuilder name = new StringBuilder();
                at = readQuoted(locator, at, name);
                step.name = name.toString();
            } else {
                int end = at;
                while (end < locator.length() && " \">".indexOf(locator.charAt(end)) < 0) {
                    end++;
                }
                String word = locator.substring(at, end);
                at = end;
                if (word.startsWith("#")) {
                    try {
                        nth = Integer.parseInt(word.substring(1));
                    } catch (NumberFormatException e) {
                        nth = 0;
                    }
                    if (nth < 1) {
                        throw bad(locator, word + " is not a position; the first match is #1");
                    }
                } else if (word.startsWith("field=") && word.length() > 6) {
                    step.field = word.substring(6);
                } else if (step.role != null || step.name != null || step.field != null) {
                    throw bad(locator, "unexpected " + Text.quote(word) + "; names go in double quotes");
                } else if (!ROLES.contains(word)) {
                    throw bad(locator, Text.quote(word) + " is not a role. Roles: " + ROLES);
                } else {
                    step.role = word;
                }
            }
        }
        steps.add(checked(step, locator));
        return nth;
    }

    private static Step checked(Step step, String locator) {
        if (step.role == null && step.name == null && step.field == null) {
            throw bad(locator, "an empty part");
        }
        return step;
    }

    private static IllegalArgumentException bad(String locator, String why) {
        return new IllegalArgumentException("Cannot read the locator " + locator + ": " + why
                + ". Examples: button \"Save\", field=txtName, dialog \"Confirm\" > button \"OK\", button \"...\" #2");
    }

    /** Reads a quoted string as {@link Text#quote} writes it; returns the index after the closing quote. */
    private static int readQuoted(String s, int openingQuote, StringBuilder into) {
        int at = openingQuote + 1;
        while (at < s.length() && s.charAt(at) != '"') {
            char c = s.charAt(at);
            if (c == '\\' && at + 1 < s.length() && (s.charAt(at + 1) == '"' || s.charAt(at + 1) == 'n')) {
                into.append(s.charAt(at + 1) == 'n' ? '\n' : '"');
                at += 2;
            } else {
                into.append(c);
                at++;
            }
        }
        return at + 1;
    }

    private static int skipSpaces(String s, int at) {
        while (at < s.length() && s.charAt(at) == ' ') {
            at++;
        }
        return at;
    }
}
