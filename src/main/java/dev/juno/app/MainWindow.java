package dev.juno.app;

import dev.juno.model.Agent;
import dev.juno.provider.AgentEvent;
import dev.juno.runtime.AgentRuntime;
import dev.juno.ui.Icons;
import dev.juno.ui.Ui;
import dev.juno.ui.board.BoardView;
import dev.juno.ui.board.FilterList;
import dev.juno.ui.board.Inbox;
import dev.juno.ui.folders.FoldersView;
import dev.juno.ui.prs.PrsView;
import dev.juno.ui.pane.AgentPane;
import java.util.List;
import javafx.application.Platform;
import dev.juno.ui.settings.SettingsWindow;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** The main window: toolbar, the board with its agent list, and the Board / Folders / PRs toggle. */
public final class MainWindow {
    private final AppContext ctx;
    private final BorderPane root = new BorderPane();
    private final VBox banners = new VBox();
    private final Stage stage;
    private final Label usage = new Label("usage —");
    private final BoardView board;
    private final FilterList filter;
    private final Inbox inbox;
    private final FoldersView foldersView;
    private final PrsView prsView;
    private final BorderPane boardArea;

    public MainWindow(AppContext ctx, Stage stage) {
        this.ctx = ctx;
        this.stage = stage;
        this.board = new BoardView(ctx);
        this.filter = new FilterList(ctx, board);
        this.inbox = new Inbox(ctx, board);
        this.foldersView = new FoldersView(ctx, board);
        this.prsView = new PrsView(ctx);

        Label logo = new Label("JUNO");
        logo.getStyleClass().add("logo");
        logo.setGraphic(Icons.logo(26));
        logo.setGraphicTextGap(8);

        ToggleGroup view = new ToggleGroup();
        ToggleButton boardToggle = new ToggleButton("Board");
        ToggleButton folders = new ToggleButton("Folders");
        ToggleButton prs = new ToggleButton("PRs");
        Button add = Ui.button("+ New agent", () -> {
            boardToggle.setSelected(true);
            board.addAgent();
        });
        Ui.withTip(add, "Splits the pane you used last and asks for the new agent's setup in the new cell.");

        boardToggle.setToggleGroup(view);
        folders.setToggleGroup(view);
        prs.setToggleGroup(view);
        boardToggle.setSelected(true);
        Ui.withTip(folders, "Folders you chose (notes, a project, your dev directory): a file tree, a text editor, and one agent beside them.");
        view.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) a.setSelected(true);
            else show(b == folders ? View.FOLDERS : b == prs ? View.PRS : View.BOARD);
        });
        Ui.withTip(prs, "The PRs and MRs your agents opened. Entries stay until you close them.");
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void prsChanged() {
                Platform.runLater(() -> prs.setText(prsLabel()));
            }
        });
        prs.setText(prsLabel());
        board.onShowRequested(() -> boardToggle.setSelected(true));
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDING, e -> foldersView.flush());

        ToggleButton agentsToggle = new ToggleButton("Agents");
        agentsToggle.setSelected(true);
        Ui.withTip(agentsToggle, "The list of all agents: search, filter, show or hide their panes.");
        agentsToggle.visibleProperty().bind(boardToggle.selectedProperty());
        agentsToggle.managedProperty().bind(boardToggle.selectedProperty());

        usage.getStyleClass().add("meter");
        usage.setTooltip(new javafx.scene.control.Tooltip("Your Claude plan's limits, from Claude Code's rate-limit "
                + "events. Shown once an agent has run."));

        Button settings = Ui.button("Settings", () -> SettingsWindow.show(ctx, stage, SettingsWindow.Page.KEYS));

        HBox bar = new HBox(10, logo, add, boardToggle, folders, prs, agentsToggle, Ui.grow(), inbox.button(), usage, settings);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("toolbar-bar");

        boardArea = new BorderPane(board.root());
        boardArea.setLeft(filter.root());
        filter.root().visibleProperty().bind(agentsToggle.selectedProperty());
        filter.root().managedProperty().bind(agentsToggle.selectedProperty());
        boardArea.getStyleClass().add("board-placeholder");
        VBox top = new VBox(bar, banners);
        root.setTop(top);
        root.setCenter(boardArea);
        ctx.onSaved(this::checkClaude);
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void rateLimit(AgentEvent.RateLimit r) { Platform.runLater(() -> showUsage(r)); }
        });
        if (ctx.runtime.agents().isEmpty()) board.addAgent();
    }

    private enum View { BOARD, FOLDERS, PRS }

    private View showing = View.BOARD;

    private String prsLabel() {
        return prsView.count() == 0 ? "PRs" : "PRs (" + prsView.count() + ")";
    }

    /** Switches between the board, the Folders view and the PRs tab; leaving Folders saves its files and returns the lent pane. */
    private void show(View v) {
        if (showing == View.FOLDERS && v != View.FOLDERS) foldersView.leave();
        showing = v;
        switch (v) {
            case FOLDERS -> {
                root.setCenter(foldersView.root());
                foldersView.show();
            }
            case PRS -> {
                prsView.refresh();
                root.setCenter(prsView.root());
            }
            default -> root.setCenter(boardArea);
        }
    }

    /** The pane last shown or used, if any (used by the screenshot tools). */
    public AgentPane currentPane() {
        return board.currentPane();
    }

    public BoardView board() {
        return board;
    }

    public Inbox inbox() {
        return inbox;
    }

    public void showAgent(Agent a) {
        board.showAgent(a);
    }

    /** {@code 5h 60% · week 68%}, reset times on hover; amber above 80 %, red above 95 % (design §7.2). */
    private void showUsage(AgentEvent.RateLimit r) {
        List<String> parts = new java.util.ArrayList<>();
        List<String> resets = new java.util.ArrayList<>();
        double worst = 0;
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm");
        if (r.fiveHour() >= 0) {
            parts.add("5h " + Math.round(r.fiveHour() * 100) + "%");
            worst = Math.max(worst, r.fiveHour());
            if (r.fiveHourReset() > 0) resets.add("5-hour limit resets " + f.format(
                    java.time.Instant.ofEpochSecond(r.fiveHourReset()).atZone(java.time.ZoneId.systemDefault())));
        }
        if (r.week() >= 0) {
            parts.add("week " + Math.round(r.week() * 100) + "%");
            worst = Math.max(worst, r.week());
            if (r.weekReset() > 0) resets.add("weekly limit resets " + f.format(
                    java.time.Instant.ofEpochSecond(r.weekReset()).atZone(java.time.ZoneId.systemDefault())));
        }
        if (parts.isEmpty()) return;
        usage.setText(String.join(" · ", parts));
        usage.getStyleClass().removeAll("warn", "bad");
        if (worst > 0.95) usage.getStyleClass().add("bad");
        else if (worst > 0.80) usage.getStyleClass().add("warn");
        usage.getTooltip().setText(String.join("\n", resets));
    }

    public BorderPane root() {
        return root;
    }

    /** Checks the Claude Code version and shows a banner when it is missing or too old (design §10). */
    public void checkClaude() {
        String exe = ctx.settings.system.effectiveClaudePath();
        Ui.background(() -> ctx.provider.checkVersion(exe), v -> {
            banners.getChildren().clear();
            if (!v.ok()) {
                Label l = new Label("⚠ " + v.message());
                l.setWrapText(true);
                l.getStyleClass().addAll("banner-bad", "bad");
                l.setMaxWidth(Double.MAX_VALUE);
                banners.getChildren().add(l);
                banners.setPadding(new Insets(0));
            }
        });
    }
}
