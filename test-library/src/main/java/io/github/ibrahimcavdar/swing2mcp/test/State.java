package io.github.ibrahimcavdar.swing2mcp.test;

/** What the agent's snapshot says about one component: its own line, split into the parts tests ask for. */
final class State {

    /** The component's lines of the snapshot. */
    final String text;
    /** The first line after its {@code [ref=eN]}: values and states such as {@code value="x" disabled}. */
    private final String states;

    private State(String text, String states) {
        this.text = text;
        this.states = states;
    }

    /** Reads the answer of /locate: {@code ref=eN}, then the lines. */
    static State of(String answer) {
        int firstBreak = answer.indexOf('\n');
        String text = firstBreak < 0 ? "" : answer.substring(firstBreak + 1).trim();
        String line = text.indexOf('\n') < 0 ? text : text.substring(0, text.indexOf('\n'));
        line = line.startsWith("- ") ? line.substring(2) : line;
        int ref = line.indexOf("[ref=");
        int end = ref < 0 ? -1 : line.indexOf(']', ref);
        return new State(text, end < 0 ? "" : " " + line.substring(end + 1).trim() + " ");
    }

    /** A state word such as disabled, checked, readonly; never text inside a quoted value. */
    boolean has(String word) {
        boolean quoted = false;
        for (int i = 0; i < states.length(); i++) {
            char c = states.charAt(i);
            if (c == '\\' && quoted) {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == ' ' && states.startsWith(word + " ", i + 1)) {
                return true;
            }
        }
        return false;
    }

    /** The quoted text after {@code key=}, e.g. value="Ali"; null if there is none. */
    String quoted(String key) {
        int at = states.indexOf(" " + key + "=\"");
        if (at < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = at + key.length() + 3; i < states.length() && states.charAt(i) != '"'; i++) {
            char c = states.charAt(i);
            if (c == '\\' && i + 1 < states.length() && (states.charAt(i + 1) == '"' || states.charAt(i + 1) == 'n')) {
                value.append(states.charAt(++i) == 'n' ? '\n' : '"');
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    /** The first number in the list after {@code key=[}, e.g. 2 for selected=[2,5-7]; -1 if there is none. */
    int firstOf(String key) {
        int at = states.indexOf(" " + key + "=[");
        if (at < 0) {
            return -1;
        }
        int start = at + key.length() + 3;
        int end = start;
        while (end < states.length() && Character.isDigit(states.charAt(end))) {
            end++;
        }
        return end == start ? -1 : Integer.parseInt(states.substring(start, end));
    }

    /** The number after {@code key=}, e.g. rows=4; -1 if there is none. */
    int number(String key) {
        int at = states.indexOf(" " + key + "=");
        if (at < 0) {
            return -1;
        }
        int start = at + key.length() + 2;
        int end = start;
        while (end < states.length() && (Character.isDigit(states.charAt(end)) || (end == start && states.charAt(end) == '-'))) {
            end++;
        }
        return end == start ? -1 : Integer.parseInt(states.substring(start, end));
    }
}
