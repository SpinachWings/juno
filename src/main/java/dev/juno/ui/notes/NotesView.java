package dev.juno.ui.notes;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.model.Project;
import dev.juno.model.Validation;
import dev.juno.runtime.AgentRuntime;
import dev.juno.store.NotesFolder;
import dev.juno.store.NotesState;
import dev.juno.ui.Theme;
import dev.juno.ui.Ui;
import dev.juno.ui.board.BoardView;
import dev.juno.ui.pane.AgentPane;
import dev.juno.ui.settings.SettingsWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * The notepad (design §9): the notes folder's file tree, tabs with a Markdown editor and live preview, and — if you
 * like — one agent's own pane beside them. That pane is the board's pane lent for the time, not a copy.
 */
public final class NotesView {
    private static final String DRAG = "juno-note:";
    /** "No agent" entry of the picker. */
    private static final Agent NONE = new Agent();

    private final AppContext ctx;
    private final BoardView board;
    private final BorderPane root = new BorderPane();
    private final TreeView<NotesFolder.Entry> tree = new TreeView<>();
    private final TabPane tabs = new TabPane();
    private final SplitPane split = new SplitPane();
    private final VBox treeBox = new VBox(6);
    private final StackPane notesArea = new StackPane();
    private final ComboBox<Agent> beside = new ComboBox<>();
    private final ToggleGroup modes = new ToggleGroup();
    private final Label message = new Label();
    private final VBox problem = new VBox(10);
    private final NotesState state;
    private NotesFolder notes;
    private AgentPane lent;
    private boolean showing;
    private boolean updating;

    public NotesView(AppContext ctx, BoardView board) {
        this.ctx = ctx;
        this.board = board;
        this.state = NotesState.load(ctx.home);

        ToggleButton edit = mode("Edit", NotesState.Mode.EDIT, "Only the editor.");
        ToggleButton both = mode("Split", NotesState.Mode.SPLIT, "Editor and live preview side by side.");
        ToggleButton view = mode("Preview", NotesState.Mode.PREVIEW, "Only the rendered note.");
        modes.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) a.setSelected(true);
            else if (!updating) {
                state.mode = (NotesState.Mode) b.getUserData();
                applyMode();
                saveState();
            }
        });
        beside.setConverter(new StringConverter<>() {
            @Override public String toString(Agent a) { return a == null || a == NONE ? "none" : a.name; }
            @Override public Agent fromString(String s) { return null; }
        });
        beside.valueProperty().addListener((o, a, b) -> {
            if (!updating && b != null) chooseBeside(b == NONE ? null : b);
        });
        Label besideLabel = new Label("Agent beside the notes");
        Ui.withTip(besideLabel, "Shows that agent's pane here, the same pane that stays on the board: same output, same "
                + "input. Back on the board it returns to its cell.");
        HBox bar = new HBox(10, edit, both, view, Ui.grow(), besideLabel, beside);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 10, 6, 10));
        bar.getStyleClass().add("notes-bar");

        Button newNote = Ui.button("+ Note", () -> create(false, selectedFolder()));
        Button newFolder = Ui.button("+ Folder", () -> create(true, selectedFolder()));
        Ui.withTip(newNote, "A new note in the selected folder");
        Ui.withTip(newFolder, "A new folder in the selected folder");
        Button refresh = Ui.button("⟳", this::refresh);
        Ui.withTip(refresh, "Reload the file tree and any note changed on disk");
        HBox treeBar = new HBox(6, newNote, newFolder, refresh);
        tree.setShowRoot(true);
        tree.getStyleClass().add("notes-tree");
        tree.setCellFactory(tv -> new EntryCell());
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openSelected();
            else if (e.getCode() == KeyCode.DELETE) delete(selectedEntry());
            else if (e.getCode() == KeyCode.F2) rename(selectedEntry());
        });
        tree.setOnMouseClicked(e -> { // a folder opens and closes by itself on a double-click
            NotesFolder.Entry v = selectedEntry();
            if (e.getClickCount() == 2 && v != null && !v.folder()) openNote(v.path());
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        treeBox.getChildren().addAll(treeBar, tree);
        treeBox.setPadding(new Insets(8));
        treeBox.getStyleClass().add("side-list");
        treeBox.setMinWidth(180);
        treeBox.setPrefWidth(230);

        Label none = Ui.dim("Open a note from the tree, or make a new one.");
        none.setPadding(new Insets(16));
        notesArea.getChildren().addAll(none, tabs);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) c -> {
            tabs.setVisible(!tabs.getTabs().isEmpty());
            saveState();
        });
        tabs.setVisible(false);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!updating) saveState();
        });

        split.getItems().addAll(treeBox, notesArea);
        SplitPane.setResizableWithParent(treeBox, false);

        message.setWrapText(true);
        problem.setAlignment(Pos.CENTER);
        problem.setPadding(new Insets(40));
        problem.setMaxWidth(560);
        StackPane centred = new StackPane(problem);
        centred.getStyleClass().add("board-placeholder");

        root.setTop(bar);
        root.setCenter(split);
        root.getStyleClass().add("notes-view");
        this.centred = centred;

        ctx.onSaved(() -> { if (showing) Platform.runLater(this::open); });
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void agentsChanged() {
                if (showing) Platform.runLater(NotesView.this::agentsChanged);
            }
        });
    }

    private final StackPane centred;

    private ToggleButton mode(String text, NotesState.Mode m, String tip) {
        ToggleButton b = new ToggleButton(text);
        b.setUserData(m);
        b.setToggleGroup(modes);
        Ui.withTip(b, tip);
        return b;
    }

    public Node root() {
        return root;
    }

    // ---------------------------------------------------------------- showing and leaving

    /** The notepad was switched to. */
    public void show() {
        showing = true;
        open();
    }

    /** The notepad was switched away from: edits are saved and the lent agent pane goes back to the board. */
    public void leave() {
        if (!showing) return;
        flush();
        returnLent();
        showing = false;
    }

    /** Saves every edited note now (also on closing the window). */
    public void flush() {
        for (NoteTab t : noteTabs()) t.save();
    }

    private void open() {
        String folder = ctx.settings.system.notesFolder == null ? "" : ctx.settings.system.notesFolder.trim();
        if (!folder.equals(state.folder)) {
            flush();
            tabs.getTabs().clear();
            state.folder = folder;
            state.open = new ArrayList<>();
            state.active = "";
            saveState();
        }
        String refusal = refusal(folder);
        if (refusal != null) {
            returnLent();
            showProblem(refusal, folder);
            return;
        }
        notes = new NotesFolder(Path.of(folder));
        root.setCenter(split);
        updating = true;
        for (var t : modes.getToggles()) t.setSelected(t.getUserData() == state.mode);
        updating = false;
        refresh();
        restoreTabs();
        fillAgents();
        applyMode();
        Platform.runLater(this::layoutDividers);
        if (!state.agent.isBlank()) {
            Agent a = ctx.runtime.all().stream().filter(x -> x.id.equals(state.agent)).findFirst().orElse(null);
            if (a != null) lend(a);
        }
    }

    /** Why the notepad can't be used with this folder, or null. */
    private String refusal(String folder) {
        if (folder.isBlank()) return "No notes folder is set yet.";
        try {
            Path p = Path.of(folder);
            if (Files.exists(p) && !Files.isDirectory(p)) return "The notes folder is a file, not a folder.";
            for (Project pr : ctx.settings.projects)
                if (pr.folder != null && !pr.folder.isBlank() && Validation.overlap(pr.folder, folder))
                    return "The notes folder overlaps the folder of project " + pr.name + " (" + pr.folder
                            + "). Agents work in project folders and never get your notes, so the two can't share files.";
        } catch (java.nio.file.InvalidPathException e) {
            return "The notes folder path isn't valid.";
        }
        return null;
    }

    private void showProblem(String text, String folder) {
        problem.getChildren().clear();
        Label l = Ui.label(text, "label");
        l.setMaxWidth(520);
        problem.getChildren().add(l);
        Button settings = Ui.primary("Open settings", () ->
                SettingsWindow.show(ctx, root.getScene() == null ? null : root.getScene().getWindow(),
                        SettingsWindow.Page.SYSTEM));
        problem.getChildren().add(settings);
        root.setCenter(centred);
    }

    private void refresh() {
        if (!Files.isDirectory(notes.root())) {
            problem.getChildren().clear();
            problem.getChildren().add(Ui.label("The notes folder doesn't exist yet:\n" + notes.root(), "label"));
            problem.getChildren().add(Ui.primary("Create it", () -> {
                try {
                    Files.createDirectories(notes.root());
                    open();
                } catch (java.io.IOException e) {
                    Ui.error(window(), "Notes folder", "Couldn't create it: " + e.getMessage());
                }
            }));
            root.setCenter(centred);
            return;
        }
        root.setCenter(split);
        Set<String> expanded = new HashSet<>();
        collectExpanded(tree.getRoot(), expanded);
        String selected = selectedEntry() == null ? null : selectedEntry().path();
        NotesFolder.Entry top = new NotesFolder.Entry("", notes.root().getFileName() == null
                ? notes.root().toString() : notes.root().getFileName().toString(), true, notes.tree());
        TreeItem<NotesFolder.Entry> rootItem = item(top, expanded, true);
        tree.setRoot(rootItem);
        if (selected != null) select(selected);
        for (NoteTab t : noteTabs()) t.checkDisk();
    }

    private TreeItem<NotesFolder.Entry> item(NotesFolder.Entry e, Set<String> expanded, boolean isRoot) {
        TreeItem<NotesFolder.Entry> it = new TreeItem<>(e);
        it.setExpanded(isRoot || expanded.contains(e.path()));
        for (NotesFolder.Entry c : e.children()) it.getChildren().add(item(c, expanded, false));
        return it;
    }

    private void collectExpanded(TreeItem<NotesFolder.Entry> it, Set<String> out) {
        if (it == null) return;
        if (it.isExpanded() && it.getValue() != null) out.add(it.getValue().path());
        for (var c : it.getChildren()) collectExpanded(c, out);
    }

    private void select(String path) {
        TreeItem<NotesFolder.Entry> it = find(tree.getRoot(), path);
        if (it == null) return;
        for (var p = it.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
        tree.getSelectionModel().select(it);
        tree.scrollTo(tree.getRow(it));
    }

    private TreeItem<NotesFolder.Entry> find(TreeItem<NotesFolder.Entry> it, String path) {
        if (it == null) return null;
        if (it.getValue() != null && it.getValue().path().equals(path)) return it;
        for (var c : it.getChildren()) {
            TreeItem<NotesFolder.Entry> r = find(c, path);
            if (r != null) return r;
        }
        return null;
    }

    private NotesFolder.Entry selectedEntry() {
        TreeItem<NotesFolder.Entry> it = tree.getSelectionModel().getSelectedItem();
        return it == null ? null : it.getValue();
    }

    /** Where "New note" goes: the selected folder, a selected note's folder, or the top. */
    private String selectedFolder() {
        NotesFolder.Entry e = selectedEntry();
        if (e == null) return "";
        if (e.folder()) return e.path();
        return e.path().contains("/") ? e.path().substring(0, e.path().lastIndexOf('/')) : "";
    }

    // ---------------------------------------------------------------- tabs

    private List<NoteTab> noteTabs() {
        List<NoteTab> out = new ArrayList<>();
        for (var t : tabs.getTabs()) if (t instanceof NoteTab n) out.add(n);
        return out;
    }

    private void restoreTabs() {
        if (!tabs.getTabs().isEmpty()) return;
        updating = true;
        for (String p : new ArrayList<>(state.open)) if (notes.isFile(p)) addTab(p, false);
        NoteTab active = noteTabs().stream().filter(t -> t.path().equals(state.active)).findFirst().orElse(null);
        if (active != null) tabs.getSelectionModel().select(active);
        updating = false;
        saveState();
    }

    private void openSelected() {
        NotesFolder.Entry e = selectedEntry();
        if (e == null) return;
        if (e.folder()) {
            var it = tree.getSelectionModel().getSelectedItem();
            if (it != null && !it.isLeaf()) it.setExpanded(!it.isExpanded());
            return;
        }
        openNote(e.path());
    }

    private void openNote(String path) {
        for (NoteTab t : noteTabs()) {
            if (t.path().equals(path)) {
                tabs.getSelectionModel().select(t);
                t.checkDisk();
                return;
            }
        }
        try {
            notes.read(path); // refuse a file that isn't text before a tab is made
        } catch (NotesFolder.NotesException e) {
            Ui.error(window(), "Notes", e.getMessage());
            return;
        }
        NoteTab t = addTab(path, true);
        Platform.runLater(t::focusEditor);
    }

    private NoteTab addTab(String path, boolean select) {
        NoteTab t = new NoteTab(notes, path, () -> { });
        t.setOnCloseRequest(e -> t.save());
        t.mode(state.mode);
        tabs.getTabs().add(t);
        if (select) tabs.getSelectionModel().select(t);
        return t;
    }

    private void applyMode() {
        for (NoteTab t : noteTabs()) t.mode(state.mode);
    }

    private void saveState() {
        if (updating) return;
        state.open = noteTabs().stream().map(NoteTab::path).toList();
        var sel = tabs.getSelectionModel().getSelectedItem();
        state.active = sel instanceof NoteTab n ? n.path() : "";
        state.save(ctx.home);
    }

    // ---------------------------------------------------------------- file operations

    private void create(boolean folder, String parent) {
        Optional<String> name = ask(folder ? "New folder" : "New note",
                (parent.isEmpty() ? "In the top folder" : "In " + parent) + ":", "");
        if (name.isEmpty()) return;
        try {
            String path = folder ? notes.createFolder(parent, name.get()) : notes.createNote(parent, name.get());
            refresh();
            select(path);
            if (!folder) openNote(path);
        } catch (NotesFolder.NotesException e) {
            Ui.error(window(), "Notes", e.getMessage());
        }
    }

    private void rename(NotesFolder.Entry e) {
        if (e == null || e.path().isEmpty()) return;
        Optional<String> name = ask("Rename", "New name for " + e.name() + ":", e.name());
        if (name.isEmpty() || name.get().trim().equals(e.name())) return;
        flush();
        try {
            retarget(e.path(), notes.rename(e.path(), name.get()));
        } catch (NotesFolder.NotesException ex) {
            Ui.error(window(), "Notes", ex.getMessage());
        }
    }

    private void move(NotesFolder.Entry e, String to) {
        flush();
        try {
            retarget(e.path(), notes.move(e.path(), to));
        } catch (NotesFolder.NotesException ex) {
            Ui.error(window(), "Notes", ex.getMessage());
        }
    }

    private void moveTo(NotesFolder.Entry e) {
        if (e == null || e.path().isEmpty()) return;
        List<String> folders = new ArrayList<>();
        folders.add("(top folder)");
        collectFolders(tree.getRoot(), e, folders);
        ChoiceDialog<String> d = new ChoiceDialog<>(folders.get(0), folders);
        d.setTitle("Move");
        d.setHeaderText("Move " + e.name() + " into:");
        style(d);
        d.initOwner(window());
        d.showAndWait().ifPresent(choice -> move(e, choice.equals("(top folder)") ? "" : choice));
    }

    private void collectFolders(TreeItem<NotesFolder.Entry> it, NotesFolder.Entry moving, List<String> out) {
        for (var c : it.getChildren()) {
            NotesFolder.Entry v = c.getValue();
            if (!v.folder()) continue;
            if (v.path().equals(moving.path()) || v.path().startsWith(moving.path() + "/")) continue;
            out.add(v.path());
            collectFolders(c, moving, out);
        }
    }

    private void delete(NotesFolder.Entry e) {
        if (e == null || e.path().isEmpty()) return;
        String what = e.folder()
                ? "the folder " + e.name() + " and the " + notes.countBelow(e.path()) + " item(s) in it"
                : "the note " + e.name();
        if (!Ui.confirm(window(), "Delete", "Delete " + what + "? This can't be undone.", "Delete")) return;
        flush();
        try {
            notes.delete(e.path());
        } catch (NotesFolder.NotesException ex) {
            Ui.error(window(), "Notes", ex.getMessage());
            return;
        }
        updating = true;
        for (NoteTab t : noteTabs()) {
            if (t.path().equals(e.path()) || t.path().startsWith(e.path() + "/")) tabs.getTabs().remove(t);
        }
        updating = false;
        saveState();
        refresh();
    }

    /** Open tabs follow a note that was renamed or moved (or a folder that held them). */
    private void retarget(String from, String to) {
        for (NoteTab t : noteTabs()) {
            if (t.path().equals(from)) t.retarget(to);
            else if (t.path().startsWith(from + "/")) t.retarget(to + t.path().substring(from.length()));
        }
        saveState();
        refresh();
        select(to);
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

    // ---------------------------------------------------------------- one agent beside the notes

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

    /** The tree keeps a fixed width; an agent beside the notes gets its minimum width, the notes the rest. */
    private void layoutDividers() {
        double w = Math.max(900, split.getWidth());
        double tree = 230 / w;
        if (split.getItems().size() == 2) split.setDividerPositions(tree);
        else split.setDividerPositions(tree, Math.max(tree + 0.2, 1 - (AgentPane.MIN_WIDTH + 40) / w));
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

    private final class EntryCell extends TreeCell<NotesFolder.Entry> {
        EntryCell() {
            MenuItem open = new MenuItem("Open");
            open.setOnAction(e -> openSelected());
            MenuItem newNote = new MenuItem("New note here");
            newNote.setOnAction(e -> { if (getItem() != null) create(false, folderOf(getItem())); });
            MenuItem newFolder = new MenuItem("New folder here");
            newFolder.setOnAction(e -> { if (getItem() != null) create(true, folderOf(getItem())); });
            MenuItem rename = new MenuItem("Rename");
            rename.setOnAction(e -> rename(getItem()));
            MenuItem move = new MenuItem("Move to…");
            move.setOnAction(e -> moveTo(getItem()));
            MenuItem delete = new MenuItem("Delete…");
            delete.setOnAction(e -> delete(getItem()));
            ContextMenu menu = new ContextMenu(open, newNote, newFolder, rename, move, delete);
            menu.setOnShowing(e -> {
                NotesFolder.Entry v = getItem();
                boolean top = v == null || v.path().isEmpty();
                open.setVisible(v != null && !v.folder());
                rename.setDisable(top);
                move.setDisable(top);
                delete.setDisable(top);
            });
            setContextMenu(menu);

            setOnDragDetected(e -> {
                NotesFolder.Entry v = getItem();
                if (v == null || v.path().isEmpty()) return;
                var db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(DRAG + v.path());
                db.setContent(cc);
                e.consume();
            });
            setOnDragOver(e -> {
                String from = dragged(e);
                NotesFolder.Entry target = getItem();
                if (from != null && target != null && target.folder() && !target.path().equals(from)
                        && !target.path().startsWith(from + "/")) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    e.consume();
                }
            });
            setOnDragDropped(e -> {
                String from = dragged(e);
                NotesFolder.Entry target = getItem();
                if (from == null || target == null || !target.folder()) return;
                NotesFolder.Entry dragged = new NotesFolder.Entry(from, from.substring(from.lastIndexOf('/') + 1),
                        notes.isFolder(from), List.of());
                e.setDropCompleted(true);
                e.consume();
                Platform.runLater(() -> move(dragged, target.path()));
            });
        }

        private String dragged(DragEvent e) {
            if (!e.getDragboard().hasString()) return null;
            String s = e.getDragboard().getString();
            return s.startsWith(DRAG) ? s.substring(DRAG.length()) : null;
        }

        private String folderOf(NotesFolder.Entry v) {
            if (v.folder()) return v.path();
            return v.path().contains("/") ? v.path().substring(0, v.path().lastIndexOf('/')) : "";
        }

        @Override
        protected void updateItem(NotesFolder.Entry v, boolean empty) {
            super.updateItem(v, empty);
            getStyleClass().remove("notes-folder");
            if (empty || v == null) {
                setText(null);
                return;
            }
            setText(v.folder() ? v.name() + "/" : v.name());
            if (v.folder()) getStyleClass().add("notes-folder");
        }
    }
}
