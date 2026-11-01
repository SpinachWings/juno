package dev.juno.ui.board;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.LiveAgent;
import dev.juno.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

/**
 * The inbox (design §7.2): everything waiting on you, each with the agent, the reason, a one-line summary and its answer
 * buttons or "go to pane", plus a quiet Waiting section. Items come from the agents' state, so they vanish when
 * answered anywhere.
 * Juno's proposals (assignments, fresh starts, link suggestions) are listed with their answers too.
 * TODO(Milestone 5): review-comment approvals, pipelines.
 */
public final class Inbox {
    /** One thing waiting on the user. */
    record Item(Agent agent, String reason, String summary, List<Button> buttons) {}

    private final AppContext ctx;
    private final BoardView board;
    private final Button button = new Button("Inbox");
    private final Popup popup = new Popup();
    private final VBox content = new VBox(6);
    private boolean refreshQueued;

    public Inbox(AppContext ctx, BoardView board) {
        this.ctx = ctx;
        this.board = board;
        button.setOnAction(e -> toggle());
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setPrefViewportWidth(520);
        sp.setMaxHeight(460);
        sp.setPrefViewportHeight(300);
        VBox frame = new VBox(sp);
        frame.getStyleClass().add("inbox-popup");
        frame.getStylesheets().setAll(dev.juno.ui.Theme.class.getResource("/dev/juno/ui/juno.css").toExternalForm());
        popup.getContent().add(frame);
        popup.setAutoHide(true);
        content.setPadding(new Insets(10));
        ctx.runtime.addListener(new AgentRuntime.Listener() {
            @Override public void changed(Agent a) { later(); }
            @Override public void agentsChanged() { later(); }
            @Override public void conversationChanged(Agent a) { later(); }
            @Override public void event(Agent a, dev.juno.provider.AgentEvent e) { /* permissions arrive as status changes */ }
            @Override public void proposalsChanged() { later(); }
        });
        refresh();
    }

    public Button button() {
        return button;
    }

    /** The items waiting now (used by tests and the screenshot tools). */
    List<Item> items() {
        List<Item> out = new ArrayList<>();
        Agent juno = ctx.runtime.juno();
        dev.juno.runtime.Orchestration o = ctx.runtime.orchestration();
        for (dev.juno.comms.Proposal p : o.proposals()) {
            if (p.kind == dev.juno.comms.Proposal.Kind.LINK) {
                Button link = Ui.primary("Link", () -> said(o.approveLink(p.id)));
                Button no = Ui.button("Don't link", () -> said(o.decline(p.id)));
                out.add(new Item(juno, "🔗 link", name(p.a) + " ↔ " + name(p.b) + "? Why: " + p.reason, List.of(link, no, go(juno))));
            } else {
                Button yes = Ui.primary(p.fresh ? "Start fresh" : "Approve", () -> said(o.approveAssign(p.id, null, null, p.fresh)));
                Button cancel = Ui.button("Cancel", () -> said(o.decline(p.id)));
                cancel.getStyleClass().add("danger");
                String what = p.askAssign ? "→ assign" : "↺ fresh start";
                out.add(new Item(juno, what, name(p.agentId) + (p.fresh ? " (starts fresh)" : "") + ": "
                        + oneLine(p.task), List.of(yes, cancel, go(juno))));
            }
        }
        for (Agent a : ctx.runtime.all()) {
            if (a.status != Agent.Status.AWAITING || a.reason == null) continue;
            LiveAgent l = ctx.runtime.live(a.id);
            switch (a.reason) {
                case PERMISSION -> {
                    List<LiveAgent.PendingPermission> perms = l.pendingPermissions();
                    if (perms.isEmpty()) continue;
                    LiveAgent.PendingPermission pp = perms.get(0);
                    Button allow = Ui.primary("Allow once", () -> answer(a, pp, AgentRuntime.Answer.ALLOW_ONCE));
                    Button deny = Ui.button("Deny", () -> answer(a, pp, AgentRuntime.Answer.DENY));
                    deny.getStyleClass().add("danger");
                    out.add(new Item(a, a.reason.icon + " permission", "wants " + pp.tool + ": " + oneLine(pp), List.of(allow, deny)));
                }
                case QUESTION -> out.add(new Item(a, a.reason.icon + " question", lastParagraph(l.lastReply), List.of(go(a))));
                case FOLDER -> out.add(new Item(a, a.reason.icon + " folder", l.folderReason() == null
                        ? "needs a project" : l.folderReason(), List.of(go(a))));
                case FAILED, INTERRUPTED -> {
                    Button resume = Ui.primary("Resume", () -> {
                        String err = ctx.runtime.resume(a);
                        if (err != null) ctx.runtime.say(a, err, "warn");
                    });
                    out.add(new Item(a, a.reason.icon + " " + a.reason.label, a.statusDetail, List.of(resume, go(a))));
                }
                case KEY -> out.add(new Item(a, a.reason.icon + " key", a.statusDetail, List.of(go(a))));
            }
        }
        return out;
    }

    private String name(String id) {
        LiveAgent l = ctx.runtime.live(id);
        return l == null ? "(deleted)" : l.agent.name;
    }

    private void said(String err) {
        if (err != null) ctx.runtime.say(ctx.runtime.juno(), err, "warn");
    }

    private static String oneLine(String s) {
        String t = s.strip().replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static String oneLine(LiveAgent.PendingPermission pp) {
        String s = switch (pp.tool) {
            case "Bash" -> pp.input.path("command").asText();
            case "Edit", "Write", "MultiEdit", "Read" -> pp.input.path("file_path").asText();
            default -> pp.input.toString();
        };
        return s.length() > 140 ? s.substring(0, 140) + "…" : s;
    }

    private static String lastParagraph(String reply) {
        if (reply == null || reply.isBlank()) return "";
        String[] paras = reply.strip().split("\n\\s*\n");
        String s = paras[paras.length - 1].strip().replace('\n', ' ');
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private Button go(Agent a) {
        return Ui.button("Go to pane", () -> {
            popup.hide();
            board.showAgent(a);
        });
    }

    private void answer(Agent a, LiveAgent.PendingPermission pp, AgentRuntime.Answer answer) {
        String err = ctx.runtime.answer(a, pp.id, answer, null);
        if (err != null) ctx.runtime.say(a, err, "warn");
    }

    private void toggle() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        refresh();
        var b = button.localToScreen(button.getBoundsInLocal());
        popup.show(button, Math.max(0, b.getMaxX() - 540), b.getMaxY() + 2);
    }

    public void open() {
        if (!popup.isShowing()) toggle();
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
        List<Item> items = items();
        List<Agent> waiting = ctx.runtime.agents().stream().filter(a -> a.status == Agent.Status.WAITING).toList();
        button.setText("Inbox (" + items.size() + ")");
        button.getStyleClass().remove("inbox-busy");
        if (!items.isEmpty()) button.getStyleClass().add("inbox-busy");
        content.getChildren().clear();
        content.getChildren().add(Ui.subheading("Waiting on you"));
        if (items.isEmpty()) content.getChildren().add(Ui.dim("Nothing is waiting for you."));
        for (Item it : items) {
            Label who = new Label(it.agent().name);
            who.setStyle("-fx-text-fill: " + it.agent().colour + ";");
            Label why = new Label(it.reason());
            why.getStyleClass().add("warn");
            HBox head = new HBox(8, who, why, Ui.grow());
            head.getChildren().addAll(it.buttons());
            head.setAlignment(Pos.CENTER_LEFT);
            Label summary = Ui.dim(it.summary());
            VBox card = new VBox(3, head, summary);
            card.getStyleClass().add("inbox-item");
            HBox.setHgrow(card, Priority.ALWAYS);
            content.getChildren().add(card);
        }
        if (!waiting.isEmpty()) {
            content.getChildren().add(Ui.subheading("Waiting ⏳"));
            for (Agent a : waiting) {
                long mins = Math.max(0, (System.currentTimeMillis() - a.statusSince) / 60_000);
                content.getChildren().add(Ui.dim(a.name + " · " + a.statusDetail + " · " + mins + " min"));
            }
        }
    }
}
