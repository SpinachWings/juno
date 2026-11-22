package dev.juno.ui.board;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.model.BoardLayout;
import dev.juno.runtime.AgentRuntime;
import dev.juno.store.LayoutStore;
import dev.juno.ui.pane.AgentPane;
import dev.juno.ui.pane.PaneHost;
import dev.juno.ui.pane.SetupForm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * The board (design §7.1): the panes in a {@link BoardLayout}, split right or down with a setup form in the new cell,
 * swapped by dragging a top bar, hidden by closing, maximised to fill the board, and saved after every change.
 */
public final class BoardView implements PaneHost {
    private static final String SETUP = "setup:";
    private static final String DRAG = "juno-pane:";

    private final AppContext ctx;
    private final LayoutStore store;
    private final BoardLayout layout;
    private final StackPane root = new StackPane();
    private final BoardCanvas canvas = new BoardCanvas();
    private final ScrollPane scroll = new ScrollPane(canvas);
    private final Map<String, AgentPane> panes = new HashMap<>();
    private final Map<String, SetupForm> setups = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private String maximised;
    /** The agent whose pane is shown beside the files right now; its board cell says so instead. */
    private String borrowed;
    private Runnable showRequested = () -> {};
    private String lastUsed = BoardLayout.JUNO;
    private int setupCounter;
    private AgentPane current;

    public BoardView(AppContext ctx) {
        this.ctx = ctx;
        this.store = new LayoutStore(ctx.home);
        this.layout = store.load();
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setPannable(false);
        scroll.getStyleClass().add("board-scroll");
        root.getChildren().add(scroll);
        root.getStyleClass().add("board");
        root.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && maximised != null && !e.isConsumed()) {
                e.consume();
                toggleMaximise(maximised);
            }
        });
        pane(ctx.runtime.juno()); // Juno's pane: always on the board, movable, never hidden (design §3)
        layout.reconcile(ids());
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void agentsChanged() {
                Platform.runLater(BoardView.this::agentsChanged);
            }
        });
        rebuild();
        persist();
    }

    public StackPane root() {
        return root;
    }

    public BoardLayout layout() {
        return layout;
    }

    /** The pane of the agent last shown or used (the screenshot tools type into it). */
    public AgentPane currentPane() {
        return current;
    }

    public AgentPane paneOf(Agent a) {
        return a == null ? null : panes.get(a.id);
    }

    /** What to do before the board is asked to show an agent (the main window switches back from the Folders view). */
    public void onShowRequested(Runnable r) {
        showRequested = r;
    }

    /**
     * Lends the agent's pane to the Folders view (design §9): the same pane, output and input, taken out of its cell, which
     * says where it went. Returns the pane, or null if that agent doesn't exist any more.
     */
    public AgentPane borrow(Agent a) {
        if (a == null) return null;
        String before = borrowed;
        borrowed = a.id;
        if (a.id.equals(maximised)) maximised = null;
        AgentPane p = pane(a);
        rebuild();
        if (before != null && !before.equals(a.id)) paneById(before).ifPresent(x -> x.setEmbedded(false));
        p.setEmbedded(true);
        return p;
    }

    /** Takes the lent pane back into its cell. */
    public void release() {
        if (borrowed == null) return;
        String id = borrowed;
        borrowed = null;
        rebuild();
        paneById(id).ifPresent(x -> x.setEmbedded(false));
    }

    private java.util.Optional<AgentPane> paneById(String id) {
        return java.util.Optional.ofNullable(panes.get(id));
    }

    /** Called after every change of what is shown (the agent list refreshes from it). */
    public void onChanged(Runnable r) {
        listeners.add(r);
    }

    public boolean isShown(String agentId) {
        return layout.shown(agentId);
    }

    /** The agents that exist plus the setup cells open now: what the layout may contain. */
    private List<String> ids() {
        List<String> ids = new ArrayList<>(ctx.runtime.agents().stream().map(a -> a.id).toList());
        ids.addAll(setups.keySet());
        return ids;
    }

    private void agentsChanged() {
        layout.reconcile(ids());
        panes.keySet().removeIf(id -> ctx.runtime.live(id) == null);
        rebuild();
        persist();
    }

    // ---------------------------------------------------------------- showing and hiding

    /** Shows the agent's pane (back where it was if hidden), brings it into view and focuses its input. */
    public void showAgent(Agent a) {
        showRequested.run();
        if (maximised != null && !maximised.equals(a.id)) maximised = null;
        if (!layout.shown(a.id)) layout.show(a.id);
        AgentPane p = pane(a);
        current = p;
        lastUsed = a.id;
        rebuild();
        persist();
        Platform.runLater(() -> {
            scrollTo(p.root());
            p.focusInput();
        });
    }

    @Override
    public void hide(String id) {
        if (id.equals(BoardLayout.JUNO)) return;
        if (id.startsWith(SETUP)) {
            cancelSetup(id);
            return;
        }
        if (id.equals(maximised)) maximised = null;
        layout.hide(id);
        if (id.equals(lastUsed)) lastUsed = BoardLayout.JUNO;
        rebuild();
        persist();
    }

    @Override
    public void deleted(Agent a) {
        layout.remove(a.id);
        panes.remove(a.id);
        if (a.id.equals(maximised)) maximised = null;
        if (a.id.equals(borrowed)) borrowed = null;
        rebuild();
        persist();
    }

    // ---------------------------------------------------------------- splitting, setup cells

    @Override
    public void split(String paneId, boolean sideBySide) {
        if (maximised != null) maximised = null;
        if (!layout.shown(paneId)) paneId = BoardLayout.JUNO;
        String id = SETUP + (++setupCounter);
        layout.split(paneId, sideBySide, id);
        setups.put(id, newSetup(id));
        lastUsed = id;
        rebuild();
        Platform.runLater(() -> scrollTo(setups.get(id).root()));
    }

    /** + New agent: splits the pane you used last. */
    public void addAgent() {
        for (Map.Entry<String, SetupForm> e : setups.entrySet()) { // one open form is enough
            scrollTo(e.getValue().root());
            return;
        }
        String target = layout.shown(lastUsed) && !lastUsed.startsWith(SETUP) ? lastUsed : BoardLayout.JUNO;
        Node cell = cell(target);
        boolean wide = cell.getLayoutBounds().getWidth() > cell.getLayoutBounds().getHeight() * 1.3
                && cell.getLayoutBounds().getWidth() >= 2 * AgentPane.MIN_WIDTH;
        split(target, wide);
    }

    private SetupForm newSetup(String id) {
        return new SetupForm(ctx, this::hiddenAgents, a -> {
            layout.rename(id, a.id);
            setups.remove(id);
            current = pane(a);
            lastUsed = a.id;
            rebuild();
            persist();
            Platform.runLater(() -> current.focusInput());
        }, a -> {
            layout.rename(id, a.id);
            layout.show(a.id); // no-op when already placed; clears its hidden hint
            setups.remove(id);
            current = pane(a);
            lastUsed = a.id;
            rebuild();
            persist();
            Platform.runLater(() -> current.focusInput());
        }, () -> cancelSetup(id));
    }

    private void cancelSetup(String id) {
        layout.remove(id);
        setups.remove(id);
        if (id.equals(lastUsed)) lastUsed = BoardLayout.JUNO;
        rebuild();
    }

    private List<Agent> hiddenAgents() {
        return ctx.runtime.agents().stream().filter(a -> layout.isHidden(a.id)).toList();
    }

    // ---------------------------------------------------------------- maximise, swap

    @Override
    public void toggleMaximise(String id) {
        maximised = id.equals(maximised) ? null : id;
        rebuild();
    }

    @Override
    public boolean maximised(String id) {
        return id.equals(maximised);
    }

    @Override
    public void used(String id) {
        lastUsed = id;
        AgentPane p = panes.get(id);
        if (p != null) current = p;
    }

    public void swap(String a, String b) {
        layout.swap(a, b);
        rebuild();
        persist();
    }

    private void wireDrag(String id, Node bar, Node cell) {
        bar.setOnDragDetected(e -> {
            var db = bar.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(DRAG + id);
            db.setContent(cc);
            try {
                db.setDragView(bar.snapshot(null, null));
            } catch (RuntimeException ex) {
                // no drag image is fine
            }
            e.consume();
        });
        cell.addEventFilter(DragEvent.DRAG_OVER, e -> {
            String from = dragged(e);
            if (from != null && !from.equals(id)) {
                e.acceptTransferModes(TransferMode.MOVE);
                if (!cell.getStyleClass().contains("drop-target")) cell.getStyleClass().add("drop-target");
                e.consume();
            }
        });
        cell.addEventFilter(DragEvent.DRAG_EXITED, e -> cell.getStyleClass().remove("drop-target"));
        cell.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            String from = dragged(e);
            cell.getStyleClass().remove("drop-target");
            if (from != null && !from.equals(id)) {
                swap(from, id);
                e.setDropCompleted(true);
                e.consume();
            }
        });
    }

    private static String dragged(DragEvent e) {
        if (!e.getDragboard().hasString()) return null;
        String s = e.getDragboard().getString();
        return s.startsWith(DRAG) ? s.substring(DRAG.length()) : null;
    }

    // ---------------------------------------------------------------- building

    private AgentPane pane(Agent a) {
        return panes.computeIfAbsent(a.id, id -> {
            AgentPane p = new AgentPane(ctx, a, this);
            wireDrag(id, p.topBar(), p.root());
            return p;
        });
    }

    private Node cell(String id) {
        if (id.equals(borrowed)) {
            String who = id.equals(BoardLayout.JUNO) ? "Juno" : ctx.runtime.agents().stream()
                    .filter(x -> x.id.equals(id)).map(x -> x.name).findFirst().orElse("This agent");
            javafx.scene.control.Label l = new javafx.scene.control.Label(who + " is beside your notes.\n"
                    + "Switch back to the board to see it here.");
            l.getStyleClass().add("dim");
            StackPane holder = new StackPane(l);
            holder.getStyleClass().add("setup-cell");
            return holder;
        }
        if (id.equals(BoardLayout.JUNO)) return pane(ctx.runtime.juno()).root();
        if (id.startsWith(SETUP)) return setups.get(id).root();
        Agent a = ctx.runtime.agents().stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
        return a == null ? new Region() : pane(a).root();
    }

    private void rebuild() {
        if (maximised != null && layout.shown(maximised)) {
            Node n = cell(maximised);
            StackPane full = new StackPane();
            canvas.show(new BoardLayout.Leaf(BoardLayout.JUNO), Map.of());
            full.getChildren().add(n);
            full.getStyleClass().add("board-maximised");
            root.getChildren().setAll(full);
            n.requestFocus();
        } else {
            maximised = null;
            Map<String, Node> cells = new LinkedHashMap<>();
            for (String id : layout.leaves()) cells.put(id, cell(id));
            canvas.show(layout.root(), cells);
            if (root.getChildren().size() != 1 || root.getChildren().get(0) != scroll) root.getChildren().setAll(scroll);
        }
        // every pane re-reads its state (maximise label etc.)
        for (AgentPane p : panes.values()) p.refreshNow();
        listeners.forEach(Runnable::run);
    }

    private void persist() {
        BoardLayout copy = BoardLayout.fromJson(layout.toJson());
        for (String id : copy.leaves()) if (id.startsWith(SETUP)) copy.remove(id);
        store.save(copy);
    }

    private void scrollTo(Node cell) {
        if (cell.getScene() == null || maximised != null) return;
        var b = cell.getBoundsInParent();
        double ch = canvas.getHeight() - scroll.getViewportBounds().getHeight();
        double cw = canvas.getWidth() - scroll.getViewportBounds().getWidth();
        if (ch > 0) scroll.setVvalue(Math.max(0, Math.min(1, b.getMinY() / ch)));
        if (cw > 0) scroll.setHvalue(Math.max(0, Math.min(1, b.getMinX() / cw)));
    }
}
