package dev.juno.ui.settings;

import dev.juno.app.AppContext;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.Conversations;
import dev.juno.ui.Ui;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Settings → System settings → Storage (design §5.2): space per agent and in total (worktree, review snapshots,
 * journals and logs, earlier conversations), Remove worktree for idle agents and Clean up old data. It never deletes
 * branches, project folders or worktrees with uncommitted or unpushed work.
 */
final class StorageSection extends VBox {
    private final AppContext ctx;
    private final GridPane table = new GridPane();
    private final Label total = new Label();
    private final Label message = Ui.dim("");
    private final Button refresh = Ui.button("Refresh", this::refresh);

    StorageSection(AppContext ctx) {
        super(8);
        this.ctx = ctx;
        table.setHgap(14);
        table.setVgap(6);
        table.setPadding(new Insets(4, 0, 4, 0));
        Button clean = Ui.button("Clean up old data", this::cleanUp);
        Ui.withTip(clean, "Applies the conversation limits now: each agent keeps its last " + Conversations.KEEP
                + " earlier conversations, none older than " + Conversations.MAX_DAYS + " days. Claude Code's own "
                + "transcripts are not touched.");
        getChildren().addAll(Ui.dim("Space used by each agent. Remove worktree is for idle agents with nothing "
                + "uncommitted or unpushed; the worktree comes back on its branch with the agent's next message. "
                + "Branches and project folders are never deleted."), table, total, Ui.row(refresh, clean), message);
        refresh();
    }

    void refresh() {
        refresh.setDisable(true);
        total.setText("Measuring…");
        AgentRuntime rt = ctx.runtime;
        Ui.background(rt::storage, rows -> {
            refresh.setDisable(false);
            show(rows);
        });
    }

    private void show(List<AgentRuntime.AgentStorage> rows) {
        table.getChildren().clear();
        String[] heads = {"Agent", "Worktree", "Review snapshots", "Current conversation", "Earlier conversations", "Total", ""};
        for (int c = 0; c < heads.length; c++) {
            Label h = new Label(heads[c]);
            h.getStyleClass().add("dim");
            table.add(h, c, 0);
        }
        long sum = 0;
        int r = 1;
        for (AgentRuntime.AgentStorage s : rows) {
            Label name = new Label(s.agent().name);
            name.setStyle("-fx-text-fill: " + s.agent().colour + ";");
            table.add(name, 0, r);
            table.add(new Label(s.hasWorktree() ? bytes(s.worktree()) : "—"), 1, r);
            table.add(new Label(bytes(s.snapshots())), 2, r);
            table.add(new Label(bytes(s.current())), 3, r);
            table.add(new Label(bytes(s.earlier())), 4, r);
            table.add(new Label(bytes(s.total())), 5, r);
            if (s.hasWorktree()) {
                Button remove = Ui.button("Remove worktree", () -> removeWorktree(s));
                remove.setDisable(!s.canRemove());
                if (!s.canRemove()) Ui.withTip(remove, s.why());
                Label note = s.stale() ? Ui.label("⚠ untouched for 14+ days", "warn") : new Label();
                table.add(new javafx.scene.layout.HBox(8, remove, note), 6, r);
            }
            sum += s.total();
            r++;
        }
        if (rows.isEmpty()) table.add(Ui.dim("No agents yet."), 0, 1);
        total.setText("Total: " + bytes(sum));
    }

    private void removeWorktree(AgentRuntime.AgentStorage s) {
        String err = ctx.runtime.removeWorktree(s.agent());
        message.setText(err == null ? "Removed " + s.agent().name + "'s worktree." : err);
        refresh();
    }

    private void cleanUp() {
        int n = ctx.runtime.cleanUp();
        message.setText(n == 0 ? "Nothing to clean up: every agent is within the limits."
                : "Dropped " + n + " old conversation" + (n == 1 ? "" : "s") + " (their journals and logs).");
        refresh();
    }

    static String bytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, v < 10 ? "%.1f %s" : "%.0f %s", v, units[u]);
    }
}
