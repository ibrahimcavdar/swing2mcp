package io.github.ibrahimcavdar.swing2mcp.agent;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.plaf.FileChooserUI;
import javax.swing.plaf.basic.BasicFileChooserUI;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Modal dialogs as a user meets them: what a message box says, which buttons it offers, and the
 * parts of a file chooser. Buttons can be named by their text or, for message boxes and file
 * choosers, by their role (ok, yes, no, cancel, open, save), which does not depend on the
 * language of the user interface. Must run on the EDT.
 */
final class Dialogs {

    private Dialogs() {
    }

    /** The dialog to answer: the open modal dialog, else the most recently opened visible dialog. */
    static Window current() {
        Dialog modal = SnapshotBuilder.activeModalDialog();
        if (modal != null) {
            return modal;
        }
        Window found = null;
        for (Window window : SnapshotBuilder.showingWindows()) {
            if (window instanceof Dialog) {
                found = window;
            }
        }
        return found;
    }

    static String title(Window w) {
        return w instanceof Dialog ? ((Dialog) w).getTitle() : w instanceof Frame ? ((Frame) w).getTitle() : "";
    }

    static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && child.isShowing()) {
                return type.cast(child);
            }
            if (child instanceof Container) {
                T found = find((Container) child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends Component> void collect(Container root, Class<T> type, List<T> into) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && child.isShowing()) {
                into.add(type.cast(child));
            }
            if (child instanceof Container) {
                collect((Container) child, type, into);
            }
        }
    }

    /** Dialog "Title" (error: "message"), for results. */
    static String summary(Window w) {
        StringBuilder sb = new StringBuilder("dialog ").append(Text.quote(title(w)));
        JOptionPane pane = find(w, JOptionPane.class);
        if (pane != null) {
            sb.append(" (").append(messageType(pane.getMessageType())).append(": ")
                    .append(Text.quote(message(pane), 300)).append(')');
        }
        JFileChooser chooser = find(w, JFileChooser.class);
        if (chooser != null) {
            sb.append(" (file chooser in ").append(Text.quote(String.valueOf(chooser.getCurrentDirectory()))).append(')');
        }
        return sb.toString();
    }

    static String messageType(int type) {
        switch (type) {
            case JOptionPane.ERROR_MESSAGE:
                return "error";
            case JOptionPane.WARNING_MESSAGE:
                return "warning";
            case JOptionPane.QUESTION_MESSAGE:
                return "question";
            case JOptionPane.INFORMATION_MESSAGE:
                return "information";
            default:
                return "message";
        }
    }

    /** The text a message box shows (its message may be a string, an array or components). */
    static String message(JOptionPane pane) {
        StringBuilder sb = new StringBuilder();
        appendMessage(sb, pane.getMessage());
        return sb.toString().trim();
    }

    private static void appendMessage(StringBuilder sb, Object message) {
        if (message == null) {
            return;
        }
        if (message instanceof Object[]) {
            for (Object part : (Object[]) message) {
                appendMessage(sb, part);
            }
        } else if (message instanceof JLabel) {
            sb.append(Text.plain(((JLabel) message).getText())).append('\n');
        } else if (message instanceof JTextComponent) {
            sb.append(((JTextComponent) message).getText()).append('\n');
        } else if (message instanceof Container) {
            for (Component child : ((Container) message).getComponents()) {
                appendMessage(sb, child);
            }
        } else if (!(message instanceof Component)) {
            sb.append(Text.plain(String.valueOf(message))).append('\n');
        }
    }

    /**
     * A button of the dialog by its text (exact, then ignoring case, then contained) or by role.
     * Returns null for "close", which means the window's close box.
     */
    static AbstractButton button(Window w, String name) {
        List<AbstractButton> buttons = new ArrayList<AbstractButton>();
        collectButtons(w, buttons);
        String wanted = name.trim();
        for (String candidate : roleTexts(w, wanted)) {
            for (AbstractButton b : buttons) {
                if (candidate.equals(text(b))) {
                    return b;
                }
            }
        }
        for (AbstractButton b : buttons) {
            if (text(b).equalsIgnoreCase(wanted)) {
                return b;
            }
        }
        for (AbstractButton b : buttons) {
            if (wanted.length() > 2 && text(b).toLowerCase(Locale.ROOT).startsWith(wanted.toLowerCase(Locale.ROOT))) {
                return b;
            }
        }
        if (wanted.equalsIgnoreCase("close")) {
            return null;
        }
        List<String> names = new ArrayList<String>();
        for (AbstractButton b : buttons) {
            if (!text(b).isEmpty()) {
                names.add(text(b));
            }
        }
        throw new IllegalArgumentException("No button " + Text.quote(wanted) + " in " + summary(w) + "; buttons: " + names
                + ", or \"close\" for the window's close box");
    }

    /** Texts the button of a role has in this look and feel (e.g. yes = "Evet" in Turkish). */
    private static List<String> roleTexts(Window w, String role) {
        List<String> texts = new ArrayList<String>();
        String key = role.toLowerCase(Locale.ROOT);
        Locale locale = w.getLocale();
        if (key.equals("ok") || key.equals("yes") || key.equals("no") || key.equals("cancel")) {
            String text = UIManager.getString("OptionPane." + key + "ButtonText", locale);
            if (text != null) {
                texts.add(Text.plain(text));
            }
        }
        JFileChooser chooser = find(w, JFileChooser.class);
        if (chooser != null) {
            if (key.equals("open") || key.equals("save") || key.equals("approve")) {
                String text = approveText(chooser);
                if (text != null) {
                    texts.add(text);
                }
            }
            if (key.equals("cancel")) {
                String text = UIManager.getString("FileChooser.cancelButtonText", locale);
                if (text != null) {
                    texts.add(Text.plain(text));
                }
            }
        }
        return texts;
    }

    static String approveText(JFileChooser chooser) {
        String text = chooser.getApproveButtonText();
        FileChooserUI ui = chooser.getUI();
        if (text == null && ui != null) {
            text = ui.getApproveButtonText(chooser);
        }
        return text == null ? null : Text.plain(text);
    }

    private static String text(AbstractButton b) {
        return b.getText() == null ? "" : Text.plain(b.getText());
    }

    private static void collectButtons(Container root, List<AbstractButton> into) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton && child.isShowing() && child.isEnabled()) {
                into.add((AbstractButton) child);
            }
            if (child instanceof Container) {
                collectButtons((Container) child, into);
            }
        }
    }

    /** The field a user types the answer of an input dialog into (text field or combo box). */
    static Component inputField(Window w) {
        List<JTextComponent> texts = new ArrayList<JTextComponent>();
        collect(w, JTextComponent.class, texts);
        for (JTextComponent text : texts) {
            if (text.isEditable() && text.isEnabled()) {
                return text;
            }
        }
        return find(w, JComboBox.class);
    }

    /** Puts path into the file name field of the standard file chooser UI; false for other UIs. */
    static boolean setFileName(JFileChooser chooser, String path) {
        if (chooser.getUI() instanceof BasicFileChooserUI) {
            ((BasicFileChooserUI) chooser.getUI()).setFileName(path);
            return true;
        }
        return false;
    }
}
