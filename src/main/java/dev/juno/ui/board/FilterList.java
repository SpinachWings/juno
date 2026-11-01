package dev.juno.ui.board;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.LiveAgent;
import dev.juno.runtime.Meters;
import dev.juno.ui.Ui;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The side list of all agents (design §7.6): colour, unread dot, name, profile, status with reason, context %, a
 * show/hide checkbox, a text search and a status filter (including "unread"). Hidden agents keep running and show here
 * when they need you; showing one puts it back where it was. Juno can't be hidden.
 */
public final class FilterList {
    private static final List<String> FILTERS = List.of("All", "Unread", "Idle", "Queued", "Working", "Waiting",
            "Awaiting decision", "Done");

    private final AppContext ctx;
    private final BoardView board;
    private final VBox root = new VBox(8);
    private final VBox rows = new VBox(2);
    private final TextField search = new TextField();
    private final ComboBox<String> status = new ComboBox<>();
    private final Label count = Ui.dim("");
    private boolean refreshQueued;

    public FilterList(AppContext ctx, BoardView board) {
        this.ctx = ctx;
        this.board = board;
        search.setPromptText("Search agents");
        status.getItems().setAll(FILTERS);
        status.setValue("All");
        status.setMaxWidth(Double.MAX_VALUE);
        search.textProperty().addListener((o, a, b) -> refresh());
        status.valueProperty().addListener((o, a, b) -> refresh());
        ScrollPane sp = new ScrollPane(rows);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(sp, Priority.ALWAYS);
        root.getChildren().addAll(Ui.subheading("Agents"), search, status, sp, count);
        root.setPadding(new Insets(10));
        root.setPrefWidth(290);
        root.setMinWidth(250);
        root.getStyleClass().add("side-list");
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void changed(Agent a) { later(); }
            @Override public void agentsChanged() { later(); }
            @Override public void conversationChanged(Agent a) { later(); }
        });
        board.onChanged(this::later);
        refresh();
    }

    public VBox root() {
        return root;
    }

    private void later() {
        if (refreshQueued) return;
        refreshQueued = true;
        Platform.runLater(() -> {
            refreshQueued = false;
            refresh();
        });
    }

    private void refresh() {
        String q = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).strip();
        String f = status.getValue() == null ? "All" : status.getValue();
        rows.getChildren().clear();
        rows.getChildren().add(junoRow());
        int shown = 0, total = 0;
        for (Agent a : ctx.runtime.agents()) {
            total++;
            if (!matches(a, q, f)) continue;
            shown++;
            rows.getChildren().add(row(a));
        }
        count.setText(shown == total ? total + " agent" + (total == 1 ? "" : "s")
                : shown + " of " + total + " agents");
    }

    private boolean matches(Agent a, String q, String f) {
        if (!q.isEmpty()) {
            var p = ctx.runtime.project(a);
            String hay = (a.name + " " + a.profile + " " + (p == null ? "" : p.name) + " " + a.statusText())
                    .toLowerCase(Locale.ROOT);
            if (!hay.contains(q)) return false;
        }
        return switch (f) {
            case "Unread" -> a.unread;
            case "Idle" -> a.status == Agent.Status.IDLE;
            case "Queued" -> a.status == Agent.Status.QUEUED;
            case "Working" -> a.status == Agent.Status.WORKING;
            case "Waiting" -> a.status == Agent.Status.WAITING;
            case "Awaiting decision" -> a.status == Agent.Status.AWAITING;
            case "Done" -> a.status == Agent.Status.DONE;
            default -> true;
        };
    }

    /** Juno's row: like any agent's, but she can't be hidden (design §3). */
    private HBox junoRow() {
        HBox row = row(ctx.runtime.juno());
        CheckBox shown = (CheckBox) row.getChildren().get(0);
        shown.setSelected(true);
        shown.setDisable(true);
        Ui.withTip(shown, "Juno can't be hidden.");
        return row;
    }

    private HBox row(Agent a) {
        CheckBox shown = new CheckBox();
        shown.setSelected(board.isShown(a.id));
        shown.setOnAction(e -> {
            if (shown.isSelected()) board.showAgent(a);
            else board.hide(a.id);
        });
        Ui.withTip(shown, "Show or hide this pane. Hidden agents keep running.");
        Label unread = new Label("●");
        unread.getStyleClass().add("unread-dot");
        unread.setVisible(a.unread);
        unread.setManaged(a.unread);
        Label name = new Label(a.name);
        name.getStyleClass().add("pane-name");
        Label profile = Ui.dim(a.profile);
        profile.setWrapText(false);
        HBox line1 = new HBox(6, unread, name, profile);
        line1.setAlignment(Pos.CENTER_LEFT);
        Label st = new Label(a.statusText());
        st.getStyleClass().add(switch (a.status) {
            case WORKING -> "good";
            case AWAITING -> a.reason == Agent.Reason.FAILED || a.reason == Agent.Reason.INTERRUPTED ? "bad" : "warn";
            default -> "dim";
        });
        LiveAgent l = ctx.runtime.live(a.id);
        Label ctxLabel = Ui.dim(l == null || l.contextWindow <= 0 ? "" : "ctx " + Meters.percent(l.contextTokens, l.contextWindow) + "%");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox line2 = new HBox(6, st, gap, ctxLabel);
        line2.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(0, line1, line2);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(8, shown, swatch(a.colour), text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("agent-row");
        if (!board.isShown(a.id)) row.getStyleClass().add("agent-row-hidden");
        row.setOnMouseClicked(e -> {
            if (e.getTarget() instanceof CheckBox || shown.isHover()) return;
            board.showAgent(a);
        });
        return row;
    }

    private static Region swatch(String colour) {
        Region r = new Region();
        r.setMinSize(6, 30);
        r.setPrefSize(6, 30);
        r.setStyle("-fx-background-color: " + colour + ";");
        return r;
    }
}
