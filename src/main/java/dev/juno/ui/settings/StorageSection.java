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
 * Settings → System settings → Storage (design §5.2): space per agent and in total (journals and logs of the current
 * and of earlier conversations) and Clean up old data. It never touches branches, project folders or worktrees.
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
        getChildren().addAll(Ui.dim("Space used by each agent's conversations (journals and logs). Project folders, "
                + "branches and worktrees are never touched."), table, total, Ui.row(refresh, clean), message);
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
        String[] heads = {"Agent", "Current conversation", "Earlier conversations", "Total"};
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
            table.add(new Label(bytes(s.current())), 1, r);
            table.add(new Label(bytes(s.earlier())), 2, r);
            table.add(new Label(bytes(s.total())), 3, r);
            sum += s.total();
            r++;
        }
        if (rows.isEmpty()) table.add(Ui.dim("No agents yet."), 0, 1);
        total.setText("Total: " + bytes(sum));
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
