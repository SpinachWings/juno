package dev.juno.ui.prs;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.model.PullRequestEntry;
import dev.juno.runtime.AgentRuntime;
import dev.juno.ui.Ui;
import java.awt.Desktop;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The PRs tab (design §7.11): the PRs/MRs agents opened through Juno, newest first, each with a link to the host.
 * An entry stays until you close it here; closing never touches the PR/MR itself.
 */
public final class PrsView {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm");

    private final AppContext ctx;
    private final ScrollPane root = new ScrollPane();
    private final VBox list = new VBox(8);
    private final Label message = Ui.dim("");

    public PrsView(AppContext ctx) {
        this.ctx = ctx;
        list.setPadding(new Insets(12));
        root.setContent(new VBox(8, list, message));
        root.setFitToWidth(true);
        root.getStyleClass().add("options-panel");
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void prsChanged() {
                Platform.runLater(PrsView.this::refresh);
            }

            @Override public void agentsChanged() {
                Platform.runLater(PrsView.this::refresh);
            }
        });
        refresh();
    }

    public Node root() {
        return root;
    }

    /** How many entries are open (for the toolbar). */
    public int count() {
        return ctx.runtime.pullRequests().size();
    }

    public void refresh() {
        list.getChildren().clear();
        list.getChildren().add(Ui.heading("Pull requests and merge requests"));
        list.getChildren().add(Ui.dim("What your agents opened. An entry stays until you close it here; closing it "
                + "doesn't touch the PR/MR on GitHub or GitLab."));
        List<PullRequestEntry> all = ctx.runtime.pullRequests();
        if (all.isEmpty()) list.getChildren().add(Ui.dim("None yet. A PR/MR an agent opens shows up here."));
        for (PullRequestEntry e : all) list.getChildren().add(card(e));
    }

    private Node card(PullRequestEntry e) {
        Agent a = ctx.runtime.agents().stream().filter(x -> x.id.equals(e.agentId)).findFirst().orElse(null);
        Label who = new Label(e.agentName);
        who.setStyle("-fx-text-fill: " + (a == null ? "#8b8b96" : a.colour) + ";");
        String id = (e.repo.isBlank() ? "" : e.repo) + (e.number.isBlank() ? "" : (e.repo.isBlank() ? "" : " ") + e.number);
        Label what = new Label(id.isBlank() ? "PR/MR" : id);
        what.getStyleClass().add("subheading");
        Label when = Ui.dim(WHEN.format(Instant.ofEpochMilli(e.openedAt).atZone(ZoneId.systemDefault())));
        HBox head = new HBox(10, what, who, Ui.grow(), when);
        head.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(e.title.isBlank() ? "(no title)" : e.title);
        title.setWrapText(true);
        Label branch = Ui.dim(e.branch.isBlank() ? "" : "from " + e.branch);
        Label url = Ui.dim(e.url);
        url.setWrapText(false);
        Ui.withTip(url, e.url);
        Button open = Ui.primary("Open", () -> open(e));
        open.setDisable(e.url.isBlank());
        Ui.withTip(open, e.url.isBlank() ? "The host's answer didn't include a link." : e.url);
        Button copy = Ui.button("Copy link", () -> Ui.copy(e.url));
        copy.setDisable(e.url.isBlank());
        Button close = Ui.button("Close", () -> ctx.runtime.closePullRequest(e.id));
        close.getStyleClass().add("danger");
        Ui.withTip(close, "Remove this entry from the list. The PR/MR itself isn't touched.");
        HBox buttons = new HBox(8, open, copy, Ui.grow(), close);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(4, head, title, branch, url, buttons);
        card.getStyleClass().add("inbox-item");
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void open(PullRequestEntry e) {
        Thread t = new Thread(() -> {
            try {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    throw new UnsupportedOperationException();
                }
                Desktop.getDesktop().browse(URI.create(e.url));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    Ui.copy(e.url);
                    message.setText("Couldn't open a browser; the link is copied.");
                });
            }
        }, "juno-open-link");
        t.setDaemon(true);
        t.start();
    }
}
