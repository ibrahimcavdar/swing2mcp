package io.github.swing2mcp.agent;

import java.util.regex.Pattern;

/** Small string helpers for producing compact, AI-readable output. */
final class Text {

    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern BREAKS = Pattern.compile("(?i)<br\\s*/?>|</p>|</div>|</li>");

    private Text() {
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** Strips Swing's {@code <html>} markup down to plain text. */
    static String plain(String s) {
        if (s == null) {
            return null;
        }
        if (s.regionMatches(true, 0, "<html>", 0, 6)) {
            s = BREAKS.matcher(s).replaceAll(" ");
            s = TAGS.matcher(s).replaceAll("");
            s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        }
        return s.trim();
    }

    /** Label text as a field name: "Full Name: *" becomes "Full Name". */
    static String labelName(String s) {
        s = plain(s);
        if (s == null) {
            return null;
        }
        s = s.trim();
        while (s.endsWith(":") || s.endsWith("*")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    static String quote(String s, int max) {
        if (s == null) {
            return "\"\"";
        }
        String truncated = s.length() > max ? s.substring(0, max) + "…" : s;
        StringBuilder sb = new StringBuilder(truncated.length() + 2).append('"');
        for (int i = 0; i < truncated.length(); i++) {
            char c = truncated.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                continue;
            } else if (c == '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    static String quote(String s) {
        return quote(s, 120);
    }
}
