package dev.juno.ui.folders;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.runtime.AgentRuntime;
import dev.juno.store.FolderTree;
import dev.juno.store.FoldersState;
import dev.juno.ui.Theme;
import dev.juno.ui.Ui;
import dev.juno.ui.board.BoardView;
import dev.juno.ui.pane.AgentPane;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * The Folders view (design §9): any number of folders you chose (notes, a project, your dev directory…), each an
 * expandable, collapsible, re-orderable root of one file tree; tabs with a text editor; and, if you like, one agent's
 * own pane beside them. That pane is the board's pane lent for the time, not a copy.
 */
public final class FoldersView {
    private static final String DRAG_FILE = "juno-file:";
    private static final String DRAG_FOLDER = "juno-folder:";
    /** "No agent" entry of the picker. */
    private static final Agent NONE = new Agent();

    /** One row of the tree: a file or folder of one listed folder. {@code path} is relative to that folder's root. */
    private record Item(FolderTree tree, String path, String name, boolean folder) {
        boolean top() {
            return path.isEmpty();
        }

        Path absolute() {
            return tree.root().resolve(path);
        }
    }

    /** A tree row that lists its folder the first time it is read, so a big directory costs nothing until it is opened. */
    private static final class Row extends TreeItem<Item> {
        private boolean loaded;

        Row(Item item) {
            super(item);
        }

        @Override public boolean isLeaf() {
            return !getValue().folder();
        }

        @Override public ObservableList<TreeItem<Item>> getChildren() {
            if (!loaded) {
                loaded = true;
                Item v = getValue();
                if (v != null && v.folder() && v.tree().exists()) {
                    for (FolderTree.Entry e : v.tree().list(v.path())) {
                        super.getChildren().add(new Row(new Item(v.tree(), e.path(), e.name(), e.folder())));
                    }
                }
            }
            return super.getChildren();
        }
    }

    private final AppContext ctx;
    private final BoardView board;
    private final BorderPane root = new BorderPane();
    private final TreeView<Item> tree = new TreeView<>();
    private final TabPane tabs = new TabPane();
    private final SplitPane split = new SplitPane();
    private final VBox treeBox = new VBox(6);
    private final StackPane editorArea = new StackPane();
    private final ComboBox<Agent> beside = new ComboBox<>();
    private final StackPane emptyCentred;
    private final FoldersState state;
    private final List<FolderTree> trees = new ArrayList<>();
    private AgentPane lent;
    private boolean showing;
    private boolean updating;

    public FoldersView(AppContext ctx, BoardView board) {
        this.ctx = ctx;
        this.board = board;
        this.state = FoldersState.load(ctx.home);

        beside.setConverter(new StringConverter<>() {
            @Override public String toString(Agent a) { return a == null || a == NONE ? "none" : a.name; }
            @Override public Agent fromString(String s) { return null; }
        });
        beside.valueProperty().addListener((o, a, b) -> {
            if (!updating && b != null) chooseBeside(b == NONE ? null : b);
        });
        Label besideLabel = new Label("Agent beside the files");
        Ui.withTip(besideLabel, "Shows that agent's pane here, the same pane that stays on the board: same output, same "
                + "input. Back on the board it returns to its cell.");
        Label hint = Ui.dim("Ctrl + and Ctrl - change the text size");
        HBox bar = new HBox(10, hint, Ui.grow(), besideLabel, beside);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.getStyleClass().add("folders-bar");

        Button add = Ui.button("+ Folder", this::addFolder);
        Ui.withTip(add, "Add a folder to this list: notes, a project, a whole directory");
        Button up = Ui.button("↑", () -> moveSelectedRoot(-1));
        Button down = Ui.button("↓", () -> moveSelectedRoot(1));
        Ui.withTip(up, "Move the selected folder up the list");
        Ui.withTip(down, "Move the selected folder down the list");
        Button refresh = Ui.button("⟳", this::refresh);
        Ui.withTip(refresh, "Reload the file tree and any file changed on disk");
        HBox treeBar = new HBox(6, add, up, down, refresh);
        tree.setShowRoot(false);
        tree.getStyleClass().add("folders-tree");
        tree.setCellFactory(tv -> new ItemCell());
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openSelected();
            else if (e.getCode() == KeyCode.DELETE) delete(selectedItem());
            else if (e.getCode() == KeyCode.F2) rename(selectedItem());
        });
        tree.setOnMouseClicked(e -> { // a folder opens and closes by itself on a double-click
            Item v = selectedItem();
            if (e.getClickCount() == 2 && v != null && !v.folder()) openFile(v);
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        treeBox.getChildren().addAll(treeBar, tree);
        treeBox.setPadding(new Insets(8));
        treeBox.getStyleClass().add("side-list");
        treeBox.setMinWidth(180);
        treeBox.setPrefWidth(260);

        Label none = Ui.dim("Open a file from the tree, or make a new one.");
        none.setPadding(new Insets(16));
        editorArea.getChildren().addAll(none, tabs);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getTabs().addListener((ListChangeListener<Tab>) c -> {
            tabs.setVisible(!tabs.getTabs().isEmpty());
            saveState();
        });
        tabs.setVisible(false);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!updating) saveState();
        });

        split.getItems().addAll(treeBox, editorArea);
        SplitPane.setResizableWithParent(treeBox, false);

        VBox empty = new VBox(10);
        empty.setAlignment(Pos.CENTER);
        empty.setPadding(new Insets(40));
        empty.setMaxWidth(560);
        empty.getChildren().addAll(Ui.label("No folders yet.", "label"),
                Ui.dim("Add the folders you want to browse and edit here: notes, a project you are working on, your "
                        + "whole dev directory. Each one can be opened, collapsed and moved in the list."),
                Ui.primary("Add a folder", this::addFolder));
        emptyCentred = new StackPane(empty);
        emptyCentred.getStyleClass().add("board-placeholder");

        root.setTop(bar);
        root.setCenter(split);
        root.getStyleClass().add("folders-view");
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::zoomKey);
        root.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() && e.getDeltaY() != 0) {
                zoom(e.getDeltaY() > 0 ? 1 : -1);
                e.consume();
            }
        });

        ctx.onSaved(() -> { if (showing) Platform.runLater(this::open); });
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void agentsChanged() {
                if (showing) Platform.runLater(FoldersView.this::agentsChanged);
            }
        });
    }

    public Node root() {
        return root;
    }

    // ---------------------------------------------------------------- showing and leaving

    /** The Folders view was switched to. */
    public void show() {
        showing = true;
        open();
    }

    /** The Folders view was switched away from: edits are saved and the lent agent pane goes back to the board. */
    public void leave() {
        if (!showing) return;
        flush();
        returnLent();
        showing = false;
    }

    /** Saves every edited file now (also on closing the window). */
    public void flush() {
        for (FileTab t : fileTabs()) t.save();
    }

    private void open() {
        trees.clear();
        for (String p : ctx.settings.folders) {
            try {
                trees.add(new FolderTree(Path.of(p)));
            } catch (InvalidPathException e) {
                // an unusable path is skipped; it can't be shown
            }
        }
        if (trees.isEmpty()) {
            returnLent();
            root.setCenter(emptyCentred);
            return;
        }
        root.setCenter(split);
        refresh();
        restoreTabs();
        fillAgents();
        Platform.runLater(this::layoutDividers);
        if (!state.agent.isBlank() && lent == null) {
            Agent a = ctx.runtime.all().stream().filter(x -> x.id.equals(state.agent)).findFirst().orElse(null);
            if (a != null) lend(a);
        }
    }

    private void refresh() {
        Set<String> expanded = new HashSet<>();
        collectExpanded(tree.getRoot(), expanded);
        Item selected = selectedItem();
        TreeItem<Item> hidden = new TreeItem<>();
        for (FolderTree t : trees) {
            Row top = new Row(new Item(t, "", label(t), true));
            String key = t.root().toString();
            top.setExpanded(!state.collapsed.contains(key));
            top.expandedProperty().addListener((o, was, is) -> {
                if (is) state.collapsed.remove(key);
                else if (!state.collapsed.contains(key)) state.collapsed.add(key);
                saveState();
            });
            hidden.getChildren().add(top);
        }
        tree.setRoot(hidden);
        restoreExpanded(hidden, expanded);
        if (selected != null) select(selected.absolute());
        for (FileTab t : fileTabs()) t.checkDisk();
    }

    private static String label(FolderTree t) {
        return t.root().getFileName() == null ? t.root().toString() : t.root().getFileName().toString();
    }

    private void collectExpanded(TreeItem<Item> it, Set<String> out) {
        if (it == null) return;
        for (TreeItem<Item> c : it.getChildren()) {
            if (!c.isExpanded()) continue;
            if (c.getValue() != null && !c.getValue().top()) out.add(c.getValue().absolute().toString());
            collectExpanded(c, out);
        }
    }

    private void restoreExpanded(TreeItem<Item> it, Set<String> expanded) {
        for (TreeItem<Item> c : it.getChildren()) {
            Item v = c.getValue();
            if (v == null || !v.folder()) continue;
            if (v.top() ? c.isExpanded() : expanded.contains(v.absolute().toString())) {
                c.setExpanded(true);
                restoreExpanded(c, expanded);
            }
        }
    }

    private void select(Path absolute) {
        TreeItem<Item> it = find(tree.getRoot(), absolute);
        if (it == null) return;
        for (TreeItem<Item> p = it.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
        tree.getSelectionModel().select(it);
        tree.scrollTo(tree.getRow(it));
    }

    private TreeItem<Item> find(TreeItem<Item> it, Path absolute) {
        if (it == null) return null;
        for (TreeItem<Item> c : it.getChildren()) {
            Item v = c.getValue();
            if (v == null) continue;
            if (v.absolute().equals(absolute)) return c;
            if (v.folder() && absolute.startsWith(v.absolute())) {
                TreeItem<Item> r = find(c, absolute);
                if (r != null) return r;
            }
        }
        return null;
    }

    private Item selectedItem() {
        TreeItem<Item> it = tree.getSelectionModel().getSelectedItem();
        return it == null ? null : it.getValue();
    }

    /** Where "New file" goes: the selected folder, a selected file's folder, or the first listed folder's top. */
    private Item selectedFolder() {
        Item e = selectedItem();
        if (e == null) return trees.isEmpty() ? null : new Item(trees.get(0), "", label(trees.get(0)), true);
        return folderOf(e);
    }

    private static Item folderOf(Item v) {
        if (v.folder()) return v;
        String parent = v.path().contains("/") ? v.path().substring(0, v.path().lastIndexOf('/')) : "";
        return new Item(v.tree(), parent, parent, true);
    }

    // ---------------------------------------------------------------- the list of folders

    private static String normal(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    private void addFolder() {
        Optional<java.io.File> f = Ui.chooseFolder(window(), "Add a folder", "");
        if (f.isEmpty()) return;
        String path = normal(f.get().getAbsolutePath());
        if (ctx.settings.folders.stream().anyMatch(p -> normal(p).equals(path))) {
            Ui.error(window(), "Folders", path + " is already in the list.");
            return;
        }
        ctx.settings.folders.add(path);
        ctx.save();
    }

    private void removeFolder(FolderTree t) {
        if (!Ui.confirm(window(), "Remove folder", "Remove " + t.root() + " from the list? Nothing on disk is deleted.",
                "Remove")) return;
        flush();
        updating = true;
        for (FileTab f : fileTabs()) if (f.tree().root().equals(t.root())) tabs.getTabs().remove(f);
        updating = false;
        ctx.settings.folders.removeIf(p -> normal(p).equals(t.root().toString()));
        state.collapsed.remove(t.root().toString());
        saveState();
        ctx.save();
    }

    private void moveSelectedRoot(int by) {
        Item v = selectedItem();
        if (v != null) moveRoot(v.tree(), by);
    }

    private void moveRoot(FolderTree t, int by) {
        int i = indexOf(t.root().toString());
        int j = i + by;
        if (i < 0 || j < 0 || j >= ctx.settings.folders.size()) return;
        String p = ctx.settings.folders.remove(i);
        ctx.settings.folders.add(j, p);
        ctx.save();
        Platform.runLater(() -> select(t.root()));
    }

    private int indexOf(String normalPath) {
        for (int i = 0; i < ctx.settings.folders.size(); i++) if (normal(ctx.settings.folders.get(i)).equals(normalPath)) return i;
        return -1;
    }

    /** Puts {@code moving} where {@code before} is in the list (drag and drop). */
    private void moveRootBefore(String moving, String before) {
        int i = indexOf(moving);
        int j = indexOf(before);
        if (i < 0 || j < 0 || i == j) return;
        String p = ctx.settings.folders.remove(i);
        ctx.settings.folders.add(i < j ? j - 1 : j, p);
        ctx.save();
    }

    // ---------------------------------------------------------------- tabs

    private List<FileTab> fileTabs() {
        List<FileTab> out = new ArrayList<>();
        for (Tab t : tabs.getTabs()) if (t instanceof FileTab n) out.add(n);
        return out;
    }

    /** The listed folder that holds {@code absolute}; the deepest one when listed folders contain each other. */
    private FolderTree treeFor(Path absolute) {
        FolderTree best = null;
        for (FolderTree t : trees) {
            if (absolute.startsWith(t.root()) && (best == null || t.root().getNameCount() > best.root().getNameCount())) best = t;
        }
        return best;
    }

    private static String relative(FolderTree t, Path absolute) {
        return t.root().relativize(absolute).toString().replace('\\', '/');
    }

    private void restoreTabs() {
        if (!tabs.getTabs().isEmpty()) return;
        updating = true;
        for (String p : new ArrayList<>(state.open)) {
            Path abs = Path.of(p);
            FolderTree t = treeFor(abs);
            if (t != null && Files.isRegularFile(abs)) addTab(t, relative(t, abs), false);
        }
        FileTab active = fileTabs().stream().filter(t -> t.absolute().toString().equals(state.active)).findFirst().orElse(null);
        if (active != null) tabs.getSelectionModel().select(active);
        updating = false;
        saveState();
    }

    private void openSelected() {
        Item e = selectedItem();
        if (e == null) return;
        if (e.folder()) {
            TreeItem<Item> it = tree.getSelectionModel().getSelectedItem();
            if (it != null) it.setExpanded(!it.isExpanded());
            return;
        }
        openFile(e);
    }

    private void openFile(Item e) {
        for (FileTab t : fileTabs()) {
            if (t.absolute().equals(e.absolute())) {
                tabs.getSelectionModel().select(t);
                t.checkDisk();
                return;
            }
        }
        try {
            e.tree().read(e.path()); // refuse a file that isn't text before a tab is made
        } catch (FolderTree.FolderException ex) {
            Ui.error(window(), "Folders", ex.getMessage());
            return;
        }
        FileTab t = addTab(e.tree(), e.path(), true);
        Platform.runLater(t::focusEditor);
    }

    private FileTab addTab(FolderTree folder, String path, boolean select) {
        FileTab t = new FileTab(folder, path, () -> { });
        t.setOnCloseRequest(e -> t.save());
        t.size(state.size);
        tabs.getTabs().add(t);
        if (select) tabs.getSelectionModel().select(t);
        return t;
    }

    private void saveState() {
        if (updating) return;
        state.open = fileTabs().stream().map(t -> t.absolute().toString()).toList();
        Tab sel = tabs.getSelectionModel().getSelectedItem();
        state.active = sel instanceof FileTab n ? n.absolute().toString() : "";
        state.save(ctx.home);
    }

    // ---------------------------------------------------------------- text size

    private void zoomKey(KeyEvent e) {
        if (!e.isShortcutDown()) return;
        switch (e.getCode()) {
            case EQUALS, PLUS, ADD -> { zoom(1); e.consume(); }
            case MINUS, SUBTRACT -> { zoom(-1); e.consume(); }
            case DIGIT0, NUMPAD0 -> { setSize(FoldersState.DEFAULT_SIZE); e.consume(); }
            default -> { }
        }
    }

    private void zoom(int steps) {
        setSize(state.size + steps);
    }

    private void setSize(double points) {
        state.size = Math.max(FoldersState.MIN_SIZE, Math.min(FoldersState.MAX_SIZE, points));
        for (FileTab t : fileTabs()) t.size(state.size);
        saveState();
    }

    // ---------------------------------------------------------------- file operations

    private void create(boolean folder, Item parent) {
        if (parent == null) return;
        Optional<String> name = ask(folder ? "New folder" : "New file", "In " + parent.absolute() + ":", "");
        if (name.isEmpty()) return;
        try {
            String path = folder ? parent.tree().createFolder(parent.path(), name.get())
                    : parent.tree().createFile(parent.path(), name.get());
            refresh();
            select(parent.tree().root().resolve(path));
            if (!folder) openFile(new Item(parent.tree(), path, name.get(), false));
        } catch (FolderTree.FolderException e) {
            Ui.error(window(), "Folders", e.getMessage());
        }
    }

    private void rename(Item e) {
        if (e == null || e.top()) return;
        Optional<String> name = ask("Rename", "New name for " + e.name() + ":", e.name());
        if (name.isEmpty() || name.get().trim().equals(e.name())) return;
        flush();
        try {
            retarget(e, e.tree().rename(e.path(), name.get()));
        } catch (FolderTree.FolderException ex) {
            Ui.error(window(), "Folders", ex.getMessage());
        }
    }

    private void move(Item e, String toFolder) {
        flush();
        try {
            retarget(e, e.tree().move(e.path(), toFolder));
        } catch (FolderTree.FolderException ex) {
            Ui.error(window(), "Folders", ex.getMessage());
        }
    }

    private void moveTo(Item e) {
        if (e == null || e.top()) return;
        String topLabel = "(top of " + label(e.tree()) + ")";
        List<String> folders = new ArrayList<>();
        folders.add(topLabel);
        collectFolders(findRow(e.tree()), e, folders);
        ChoiceDialog<String> d = new ChoiceDialog<>(topLabel, folders);
        d.setTitle("Move");
        d.setHeaderText("Move " + e.name() + " into:");
        style(d);
        d.initOwner(window());
        d.showAndWait().ifPresent(choice -> move(e, choice.equals(topLabel) ? "" : choice));
    }

    private TreeItem<Item> findRow(FolderTree t) {
        for (TreeItem<Item> c : tree.getRoot().getChildren()) if (c.getValue().tree() == t) return c;
        return tree.getRoot();
    }

    private void collectFolders(TreeItem<Item> it, Item moving, List<String> out) {
        for (TreeItem<Item> c : it.getChildren()) {
            Item v = c.getValue();
            if (!v.folder()) continue;
            if (v.path().equals(moving.path()) || v.path().startsWith(moving.path() + "/")) continue;
            out.add(v.path());
            collectFolders(c, moving, out);
        }
    }

    private void delete(Item e) {
        if (e == null || e.top()) return;
        String what = e.folder()
                ? "the folder " + e.name() + " and the " + e.tree().countBelow(e.path()) + " item(s) in it"
                : "the file " + e.name();
        if (!Ui.confirm(window(), "Delete", "Delete " + what + "? This can't be undone.", "Delete")) return;
        flush();
        try {
            e.tree().delete(e.path());
        } catch (FolderTree.FolderException ex) {
            Ui.error(window(), "Folders", ex.getMessage());
            return;
        }
        updating = true;
        for (FileTab t : fileTabs()) {
            if (t.tree() == e.tree() && (t.path().equals(e.path()) || t.path().startsWith(e.path() + "/"))) tabs.getTabs().remove(t);
        }
        updating = false;
        saveState();
        refresh();
    }

    /** Open tabs follow a file that was renamed or moved (or a folder that held them). */
    private void retarget(Item e, String to) {
        for (FileTab t : fileTabs()) {
            if (t.tree() != e.tree()) continue;
            if (t.path().equals(e.path())) t.retarget(to);
            else if (t.path().startsWith(e.path() + "/")) t.retarget(to + t.path().substring(e.path().length()));
        }
        saveState();
        refresh();
        select(e.tree().root().resolve(to));
    }

    private Optional<String> ask(String title, String header, String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.setTitle(title);
        d.setHeaderText(header);
        style(d);
        d.initOwner(window());
        return d.showAndWait().map(String::trim).filter(s -> !s.isEmpty());
    }

    private void style(javafx.scene.control.Dialog<?> d) {
        d.getDialogPane().getStylesheets().setAll(Theme.class.getResource("/dev/juno/ui/juno.css").toExternalForm());
    }

    private javafx.stage.Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    // ---------------------------------------------------------------- one agent beside the files

    private void fillAgents() {
        updating = true;
        List<Agent> all = new ArrayList<>();
        all.add(NONE);
        all.addAll(ctx.runtime.all());
        beside.getItems().setAll(all);
        beside.setValue(lent == null ? NONE : lent.agent());
        updating = false;
    }

    private void agentsChanged() {
        Agent current = lent == null ? null : lent.agent();
        if (current != null && ctx.runtime.all().stream().noneMatch(a -> a.id.equals(current.id))) {
            split.getItems().remove(lent.root());
            lent = null;
            state.agent = "";
            saveState();
        }
        fillAgents();
    }

    private void chooseBeside(Agent a) {
        returnLent();
        layoutDividers();
        state.agent = a == null ? "" : a.id;
        saveState();
        if (a != null) lend(a);
    }

    private void lend(Agent a) {
        returnLent();
        AgentPane p = board.borrow(a);
        if (p == null) return;
        lent = p;
        p.root().setMinWidth(AgentPane.MIN_WIDTH);
        split.getItems().add(p.root());
        SplitPane.setResizableWithParent(p.root(), false);
        layoutDividers();
        p.focusInput();
        updating = true;
        beside.setValue(a);
        updating = false;
    }

    /** The tree keeps a fixed width; an agent beside the files gets its minimum width, the editor the rest. */
    private void layoutDividers() {
        double w = Math.max(900, split.getWidth());
        double list = 260 / w;
        if (split.getItems().size() == 2) split.setDividerPositions(list);
        else split.setDividerPositions(list, Math.max(list + 0.2, 1 - (AgentPane.MIN_WIDTH + 40) / w));
    }

    private void returnLent() {
        if (lent == null) return;
        split.getItems().remove(lent.root());
        lent = null;
        board.release();
        updating = true;
        beside.setValue(NONE);
        updating = false;
    }

    // ---------------------------------------------------------------- tree cells

    private final class ItemCell extends TreeCell<Item> {
        ItemCell() {
            MenuItem open = new MenuItem("Open");
            open.setOnAction(e -> openSelected());
            MenuItem newFile = new MenuItem("New file here");
            newFile.setOnAction(e -> { if (getItem() != null) create(false, folderOf(getItem())); });
            MenuItem newFolder = new MenuItem("New folder here");
            newFolder.setOnAction(e -> { if (getItem() != null) create(true, folderOf(getItem())); });
            MenuItem rename = new MenuItem("Rename");
            rename.setOnAction(e -> rename(getItem()));
            MenuItem move = new MenuItem("Move to…");
            move.setOnAction(e -> moveTo(getItem()));
            MenuItem delete = new MenuItem("Delete…");
            delete.setOnAction(e -> delete(getItem()));
            MenuItem up = new MenuItem("Move up the list");
            up.setOnAction(e -> { if (getItem() != null) moveRoot(getItem().tree(), -1); });
            MenuItem down = new MenuItem("Move down the list");
            down.setOnAction(e -> { if (getItem() != null) moveRoot(getItem().tree(), 1); });
            MenuItem remove = new MenuItem("Remove from the list…");
            remove.setOnAction(e -> { if (getItem() != null) removeFolder(getItem().tree()); });
            ContextMenu menu = new ContextMenu(open, newFile, newFolder, rename, move, delete, new SeparatorMenuItem(),
                    up, down, remove);
            menu.setOnShowing(e -> {
                Item v = getItem();
                boolean top = v == null || v.top();
                open.setVisible(v != null && !v.folder());
                rename.setVisible(!top);
                move.setVisible(!top);
                delete.setVisible(!top);
                up.setVisible(top);
                down.setVisible(top);
                remove.setVisible(top);
            });
            setContextMenu(menu);

            setOnDragDetected(e -> {
                Item v = getItem();
                if (v == null) return;
                var db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString((v.top() ? DRAG_FOLDER : DRAG_FILE) + v.absolute());
                db.setContent(cc);
                e.consume();
            });
            setOnDragOver(e -> {
                Item target = getItem();
                if (target == null) return;
                String file = dragged(e, DRAG_FILE);
                String folder = dragged(e, DRAG_FOLDER);
                boolean ok = false;
                if (file != null && target.folder()) {
                    Path from = Path.of(file);
                    ok = !target.absolute().startsWith(from) && treeFor(from) == target.tree();
                } else if (folder != null && target.top()) {
                    ok = !target.absolute().toString().equals(folder);
                }
                if (ok) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    e.consume();
                }
            });
            setOnDragDropped(e -> {
                Item target = getItem();
                String file = dragged(e, DRAG_FILE);
                String folder = dragged(e, DRAG_FOLDER);
                if (target == null) return;
                e.setDropCompleted(true);
                e.consume();
                if (file != null && target.folder()) {
                    FolderTree t = treeFor(Path.of(file));
                    if (t != target.tree()) return;
                    String rel = relative(t, Path.of(file));
                    Item moved = new Item(t, rel, rel.substring(rel.lastIndexOf('/') + 1), t.isFolder(rel));
                    Platform.runLater(() -> move(moved, target.path()));
                } else if (folder != null && target.top()) {
                    Platform.runLater(() -> moveRootBefore(folder, target.absolute().toString()));
                }
            });
        }

        private String dragged(DragEvent e, String prefix) {
            if (!e.getDragboard().hasString()) return null;
            String s = e.getDragboard().getString();
            return s.startsWith(prefix) ? s.substring(prefix.length()) : null;
        }

        @Override
        protected void updateItem(Item v, boolean empty) {
            super.updateItem(v, empty);
            getStyleClass().removeAll("folders-folder", "folders-top");
            if (empty || v == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            boolean missing = v.top() && !v.tree().exists();
            setText(v.folder() ? v.name() + "/" + (missing ? " (missing)" : "") : v.name());
            setTooltip(new Tooltip(v.absolute().toString()));
            if (v.folder()) getStyleClass().add("folders-folder");
            if (v.top()) getStyleClass().add("folders-top");
        }
    }
}
