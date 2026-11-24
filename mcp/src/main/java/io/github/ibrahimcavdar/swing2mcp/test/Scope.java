package io.github.ibrahimcavdar.swing2mcp.test;

/**
 * Where components are looked for: the whole application ({@link SwingApp}) or inside a window, group or
 * other container ({@link Container}). One method per kind of component, named after what the snapshot
 * calls it; the name is the one the snapshot shows in quotes (a button's text, a field's label).
 *
 * <p>Nothing is looked up when these methods are called. The component is found when something is done
 * with it, and that waits until it is on screen and usable.
 */
public abstract class Scope {

    Scope() {
    }

    abstract SwingApp app();

    /** The locator of this scope followed by " > ", or nothing for the whole application. */
    abstract String prefix();

    private String inside(String role, String name) {
        return prefix() + role + (name == null ? "" : " " + quote(name));
    }

    static String quote(String name) {
        return "\"" + name.replace("\r", "").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // ---------------------------------------------------------------- containers

    public Container frame(String title) {
        return new Container(app(), inside("frame", title));
    }

    public Container dialog(String title) {
        return new Container(app(), inside("dialog", title));
    }

    public Container internalFrame(String title) {
        return new Container(app(), inside("internalframe", title));
    }

    /** A panel with a titled border. */
    public Container group(String title) {
        return new Container(app(), inside("group", title));
    }

    // ---------------------------------------------------------------- components

    public Button button(String name) {
        return new Button(app(), inside("button", name));
    }

    public Toggle checkBox(String name) {
        return new Toggle(app(), inside("checkbox", name));
    }

    public Toggle radioButton(String name) {
        return new Toggle(app(), inside("radio", name));
    }

    public Toggle toggleButton(String name) {
        return new Toggle(app(), inside("togglebutton", name));
    }

    public TextInput textField(String name) {
        return new TextInput(app(), inside("textfield", name));
    }

    public TextInput passwordField(String name) {
        return new TextInput(app(), inside("passwordfield", name));
    }

    public TextInput textArea(String name) {
        return new TextInput(app(), inside("textarea", name));
    }

    public TextInput spinner(String name) {
        return new TextInput(app(), inside("spinner", name));
    }

    public ComboBox comboBox(String name) {
        return new ComboBox(app(), inside("combobox", name));
    }

    public ItemList list(String name) {
        return new ItemList(app(), inside("list", name));
    }

    /** The only list in this scope. */
    public ItemList list() {
        return new ItemList(app(), inside("list", null));
    }

    public Table table(String name) {
        return new Table(app(), inside("table", name));
    }

    /** The only table in this scope. */
    public Table table() {
        return new Table(app(), inside("table", null));
    }

    public Tree tree(String name) {
        return new Tree(app(), inside("tree", name));
    }

    /** The only tree in this scope. */
    public Tree tree() {
        return new Tree(app(), inside("tree", null));
    }

    /** The only tabbed pane in this scope. */
    public Tabs tabs() {
        return new Tabs(app(), inside("tabs", null));
    }

    public Locator label(String text) {
        return new Locator(app(), inside("label", text));
    }

    /** A menu item anywhere in the menus, also in a closed one. */
    public Button menuItem(String text) {
        return new Button(app(), inside("menuitem", text));
    }

    // ---------------------------------------------------------------- when there is no method for it

    /**
     * The component that a Java field of the application holds: the {@code field=} the snapshot shows,
     * e.g. {@code txtName}. For components without a name, or when two have the same one.
     */
    public AnyComponent field(String javaFieldName) {
        return new AnyComponent(app(), prefix() + "field=" + javaFieldName);
    }

    /**
     * A component by a locator written as the snapshot writes it, e.g. {@code slider "Zoom"},
     * {@code custom "Trash"} or {@code button "..." #2}.
     */
    public AnyComponent locator(String locator) {
        return new AnyComponent(app(), prefix() + locator);
    }
}
