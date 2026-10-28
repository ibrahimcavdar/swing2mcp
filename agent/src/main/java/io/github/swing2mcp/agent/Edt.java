package io.github.swing2mcp.agent;

import java.awt.EventQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Helpers for running code on the Event Dispatch Thread without ever blocking forever. */
final class Edt {

    static final class BusyException extends Exception {
        BusyException(long timeoutMs) {
            super("UI thread (EDT) did not respond within " + timeoutMs + " ms; the application may be busy");
        }
    }

    private Edt() {
    }

    static <T> T call(Callable<T> task, long timeoutMs) throws Exception {
        if (EventQueue.isDispatchThread()) {
            return task.call();
        }
        FutureTask<T> future = new FutureTask<T>(task);
        EventQueue.invokeLater(future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BusyException(timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Waits until the EDT has processed everything queued so far. This also works while
     * a modal dialog is open, because modal dialogs keep pumping events in a nested loop.
     */
    static boolean flush(long timeoutMs) {
        try {
            call(new Callable<Object>() {
                public Object call() {
                    return null;
                }
            }, timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
