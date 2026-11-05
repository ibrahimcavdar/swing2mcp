package io.github.swing2mcp.agent;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects exceptions the application does not handle (on the UI thread and on other threads), so
 * that the next tool result can tell the AI that its action broke something.
 *
 * It wraps the default uncaught exception handler and passes every exception on to the handler that
 * was there before (or prints it like the JVM does), so the application behaves as without the
 * agent. Applications often install their own default handler during startup; {@link #check()}
 * runs before each request and wraps a newly installed handler, and a handler set directly on the
 * UI thread as well.
 */
final class ErrorMonitor {

    private static final int MAX_KEPT = 20;
    private static final int MAX_FRAMES = 6;

    private static final ErrorMonitor INSTANCE = new ErrorMonitor();

    private final List<String> unreported = new ArrayList<String>();
    private int dropped;

    private ErrorMonitor() {
    }

    static ErrorMonitor get() {
        return INSTANCE;
    }

    private final class Recorder implements Thread.UncaughtExceptionHandler {
        final Thread.UncaughtExceptionHandler next;

        Recorder(Thread.UncaughtExceptionHandler next) {
            this.next = next;
        }

        public void uncaughtException(Thread thread, Throwable error) {
            try {
                record(thread, error);
            } catch (Throwable ignored) {
                // never get in the way of the application's own handling
            }
            if (next != null) {
                next.uncaughtException(thread, error);
            } else if (!(error instanceof ThreadDeath)) {
                // what ThreadGroup.uncaughtException prints when there is no default handler
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                error.printStackTrace(System.err);
            }
        }
    }

    /** Makes sure our recorder sees uncaught exceptions; cheap enough to call before every request. */
    void check() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (!(current instanceof Recorder)) {
            Thread.setDefaultUncaughtExceptionHandler(new Recorder(current));
        }
        // A handler set on the UI thread itself would bypass the default handler.
        EventQueue.invokeLater(new Runnable() {
            public void run() {
                Thread edt = Thread.currentThread();
                Thread.UncaughtExceptionHandler own = edt.getUncaughtExceptionHandler();
                if (own != edt.getThreadGroup() && !(own instanceof Recorder)) {
                    edt.setUncaughtExceptionHandler(new Recorder(own));
                }
            }
        });
    }

    /** Reports an exception that an action caught itself (it would otherwise have reached the handler). */
    void recordCaught(Throwable error) {
        record(Thread.currentThread(), error);
    }

    private synchronized void record(Thread thread, Throwable error) {
        if (unreported.size() >= MAX_KEPT) {
            unreported.remove(0);
            dropped++;
        }
        unreported.add(describe("in thread \"" + thread.getName() + "\"", error));
    }

    /**
     * Text to append to a tool result for the exceptions since the last report, or "" if there were
     * none. Each exception is reported once.
     */
    synchronized String takeReport() {
        if (unreported.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nAPPLICATION ERRORS (uncaught exceptions since the last tool call");
        if (dropped > 0) {
            sb.append("; ").append(dropped).append(" older ones not shown");
        }
        sb.append("):\n");
        for (String error : unreported) {
            sb.append(error);
        }
        unreported.clear();
        dropped = 0;
        return sb.toString();
    }

    static String describe(String where, Throwable error) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(where).append(": ").append(error).append('\n');
        appendFrames(sb, error);
        Throwable cause = error.getCause();
        for (int depth = 0; cause != null && cause != error && depth < 5; depth++) {
            sb.append("    caused by ").append(cause).append('\n');
            if (cause.getCause() == null || cause.getCause() == cause) {
                appendFrames(sb, cause); // the root cause is usually where the bug is
                break;
            }
            error = cause;
            cause = cause.getCause();
        }
        return sb.toString();
    }

    private static void appendFrames(StringBuilder sb, Throwable error) {
        StackTraceElement[] frames = error.getStackTrace();
        int shown = 0;
        int i = 0;
        for (; i < frames.length && shown < MAX_FRAMES; i++) {
            if (frames[i].getClassName().startsWith("io.github.swing2mcp.")) {
                continue;
            }
            sb.append("      at ").append(frames[i]).append('\n');
            shown++;
        }
        if (i < frames.length) {
            sb.append("      ...\n");
        }
    }
}
