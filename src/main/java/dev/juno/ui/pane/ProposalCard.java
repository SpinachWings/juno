package dev.juno.ui.pane;

import dev.juno.app.AppContext;
import dev.juno.comms.Proposal;
import dev.juno.model.Agent;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.Orchestration;
import dev.juno.ui.Ui;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * An approval card in Juno's pane (design §4.1, §4.2): an assignment with Approve / Edit / Choose another / Cancel, a
 * fresh start with Start fresh / Continue instead / Cancel (both when Juno chose the agent and starts it fresh), a task
 * for an agent you named counting down with Undo / Send now, or a link she suggests with Link / Don't link.
 */
public final class ProposalCard {
    private final AgentRuntime runtime;
    private final Proposal p;
    private final Runnable after;
    private final VBox card = new VBox(6);

    public ProposalCard(AppContext ctx, Proposal p, Runnable after) {
        this.runtime = ctx.runtime;
        this.p = p;
        this.after = after;
        card.getStyleClass().add("card-proposal");
        if (p.kind == Proposal.Kind.LINK) link();
        else if (p.sendAt > 0) countdown();
        else assign();
    }

    public VBox node() {
        return card;
    }

    private Orchestration o() {
        return runtime.orchestration();
    }

    private void link() {
        String a = name(p.a), b = name(p.b);
        Label title = title("🔗 Link " + a + " ↔ " + b + "?");
        Label why = Ui.dim("Why: " + p.reason);
        why.setWrapText(true);
        Button yes = Ui.primary("Link", () -> done(o().approveLink(p.id)));
        Button no = Ui.button("Don't link", () -> done(o().decline(p.id)));
        card.getChildren().addAll(title, why, new HBox(8, yes, no), Ui.dim("or /approve, /deny"));
    }

    /** The task goes by itself when the time is up (the runtime's timer); the numbers here are only for show. */
    private void countdown() {
        Label title = title("");
        Runnable tick = () -> title.setText("→ " + name(p.agentId) + " gets this task in "
                + Math.max(0, (p.sendAt - System.currentTimeMillis() + 999) / 1000) + " s");
        tick.run();
        javafx.animation.Timeline t = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(250), e -> tick.run()));
        t.setCycleCount((int) Math.max(1, (p.sendAt - System.currentTimeMillis()) / 250 + 1));
        t.play();
        Label because = Ui.dim("You named " + name(p.agentId) + " in your message, so Juno didn't ask. Not what you meant? Undo.");
        because.setWrapText(true);
        Label task = new Label(p.task);
        task.setWrapText(true);
        task.getStyleClass().add("proposal-task");
        Button undo = Ui.primary("Undo", () -> done(o().decline(p.id)));
        Button now = Ui.button("Send now", () -> done(o().approveAssign(p.id, null, null, false)));
        card.getChildren().addAll(title, because, task, new HBox(8, undo, now), Ui.dim("or /deny, /approve"));
    }

    private void assign() {
        Agent t = runtime.live(p.agentId) == null ? null : runtime.live(p.agentId).agent;
        String who = t == null ? "(deleted)" : t.name;
        Label title = title(p.askAssign ? "→ Give " + who + " this task?" : "↺ Start " + who + " fresh for this task?");
        card.getChildren().add(title);
        if (t != null) {
            Label about = Ui.dim(t.profile + " · " + t.statusText() + " · context " + o().contextPct(t)
                    + (p.askAssign ? " · held because " + p.why : ""));
            about.setWrapText(true);
            card.getChildren().add(about);
        }
        if (p.fresh && t != null) {
            Label clears = Ui.label("Starting fresh clears " + t.name + "'s conversation — " + o().contextPct(t)
                    + " context, last active " + ago(t.statusSince) + ". It stays in " + t.name + "'s conversations.", "warn");
            clears.setWrapText(true);
            card.getChildren().add(clears);
        }
        Label task = new Label(p.task);
        task.setWrapText(true);
        task.getStyleClass().add("proposal-task");
        TextArea edit = new TextArea(p.task);
        edit.setWrapText(true);
        edit.setPrefRowCount(4);
        edit.setVisible(false);
        edit.managedProperty().bind(edit.visibleProperty());
        task.managedProperty().bind(task.visibleProperty());
        card.getChildren().addAll(task, edit);

        javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(8, 6);
        buttons.setAlignment(Pos.CENTER_LEFT);
        java.util.function.Supplier<String> text = () -> edit.isVisible() ? edit.getText() : null;
        if (p.fresh) {
            buttons.getChildren().add(Ui.primary("Start fresh", () -> done(o().approveAssign(p.id, text.get(), null, true))));
            buttons.getChildren().add(Ui.button("Continue instead", () -> done(o().approveAssign(p.id, text.get(), null, false))));
        } else {
            buttons.getChildren().add(Ui.primary("Approve", () -> done(o().approveAssign(p.id, text.get(), null, false))));
        }
        if (p.askAssign) {
            Button editButton = new Button("Edit");
            editButton.setOnAction(e -> {
                boolean on = !edit.isVisible();
                edit.setVisible(on);
                task.setVisible(!on);
                editButton.setText(on ? "Editing…" : "Edit");
                if (on) edit.requestFocus();
            });
            buttons.getChildren().add(editButton);
            ComboBox<Agent> other = new ComboBox<>();
            List<Agent> others = runtime.agents().stream().filter(x -> !x.id.equals(p.agentId)).toList();
            other.getItems().setAll(others);
            other.setPromptText(others.isEmpty() ? "No other agents" : "Choose another…");
            other.setConverter(new StringConverter<>() {
                @Override public String toString(Agent a) { return a == null ? "" : a.name + " · " + a.profile + " · " + a.statusText(); }
                @Override public Agent fromString(String s) { return null; }
            });
            other.setOnAction(e -> {
                if (other.getValue() != null) done(o().approveAssign(p.id, text.get(), other.getValue().id, p.fresh));
            });
            other.setDisable(others.isEmpty());
            buttons.getChildren().add(other);
        }
        buttons.getChildren().add(cancel());
        for (var n : buttons.getChildren()) if (n instanceof javafx.scene.control.Control c) c.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        card.getChildren().addAll(buttons, Ui.dim("or /approve, /deny"));
    }

    private Button cancel() {
        Button b = Ui.button("Cancel", () -> done(o().decline(p.id)));
        b.getStyleClass().add("danger");
        return b;
    }

    private void done(String err) {
        if (err != null) runtime.say(runtime.juno(), err, "warn");
        after.run();
    }

    private Label title(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("card-title");
        return l;
    }

    private String name(String id) {
        return runtime.live(id) == null ? "(deleted)" : runtime.live(id).agent.name;
    }

    static String ago(long at) {
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        return (min / 60) + " h ago";
    }
}
