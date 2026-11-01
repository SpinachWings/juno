package dev.juno.ui.pane;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.model.Key;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.Conversations;
import dev.juno.runtime.LiveAgent;
import dev.juno.runtime.Meters;
import dev.juno.runtime.PaneCommands;
import dev.juno.ui.Ui;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;

/**
 * The ⋯ options menu of a pane (design §7.10): editable any time, even while the agent works; what changes how it runs
 * applies from its next message. Covers the pane while open.
 */
final class OptionsPanel extends ScrollPane {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm");
    private static final List<String> EFFORTS = List.of("default", "low", "medium", "high", "xhigh", "max");

    private final AppContext ctx;
    private final AgentRuntime runtime;
    private final Agent agent;
    private final PaneHost host;
    private final Runnable close;
    private final VBox box = new VBox(10);

    OptionsPanel(AppContext ctx, Agent agent, PaneHost host, Runnable close) {
        this.ctx = ctx;
        this.runtime = ctx.runtime;
        this.agent = agent;
        this.host = host;
        this.close = close;
        setFitToWidth(true);
        box.setPadding(new Insets(12));
        setContent(box);
        getStyleClass().add("options-panel");
        rebuild();
    }

    /** Rebuilds from the agent's current state (called when the menu opens and after its own actions). */
    void rebuild() {
        Profile profile = runtime.profile(agent.profile);
        box.getChildren().clear();
        Button done = Ui.button("✕ Close", close);
        HBox head = new HBox(8, Ui.heading("Options · " + agent.name), Ui.grow(), done);
        head.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(head);
        box.getChildren().add(Ui.dim("Changes to how " + agent.name + " runs apply from its next message; it is never "
                + "interrupted."));

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        int r = 0;

        // Juno keeps her name, colour and private folder, needs no keys and can't be deleted (design §3).
        boolean juno = dev.juno.runtime.Orchestration.isJuno(agent);
        if (!juno) {
            // Name and colour: at once.
            TextField name = new TextField(agent.name);
            Label nameProblem = Ui.label("", "bad");
            Runnable commitName = () -> {
                if (name.getText().strip().equals(agent.name)) return;
                String err = runtime.rename(agent, name.getText());
                nameProblem.setText(err == null ? "" : err);
                if (err != null) name.setText(agent.name);
            };
            name.setOnAction(e -> commitName.run());
            name.focusedProperty().addListener((o, a, b) -> { if (!b) commitName.run(); });
            g.addRow(r++, new Label("Name"), new VBox(2, name, nameProblem));
            ColorPicker colour = new ColorPicker(Color.web(agent.colour));
            colour.setOnAction(e -> runtime.setColour(agent, SetupForm.hex(colour.getValue())));
            g.addRow(r++, new Label("Colour"), colour);

            // Project: waits until the agent isn't working.
            ComboBox<Project> project = new ComboBox<>();
            project.getItems().setAll(ctx.settings.projects);
            project.setConverter(new StringConverter<>() {
                @Override public String toString(Project p) { return p == null ? "No project" : p.name; }
                @Override public Project fromString(String s) { return null; }
            });
            project.setValue(runtime.project(agent));
            Label projectNote = Ui.dim("");
            String pending = runtime.pendingProject(agent);
            if (pending != null) projectNote.setText("Changes when " + agent.name + " isn't working any more.");
            else if (runtime.usesWorktree(agent)) projectNote.setText("Works in its own worktree"
                    + (agent.branch.isBlank() ? "" : ", branch " + agent.branch) + ".");
            Button setProject = Ui.button("Change project", () -> {
                if (project.getValue() == null) return;
                String msg = runtime.requestProject(agent, project.getValue().id);
                projectNote.setText(msg == null ? "Project set." : msg);
                if (msg == null) rebuildLater();
            });
            setProject.disableProperty().bind(project.valueProperty().isNull());
            g.addRow(r++, new Label("Project"), new VBox(4, Ui.row(project, setProject), projectNote));
        }

        // Model and effort.
        ComboBox<String> model = new ComboBox<>();
        model.setEditable(true);
        model.getItems().setAll(PaneCommands.MODELS);
        model.setValue(agent.modelOverride.isBlank() ? "default" : agent.modelOverride);
        model.setOnAction(e -> {
            String v = model.getValue() == null ? "" : model.getValue().strip();
            runtime.setModel(agent, v.equals("default") ? "" : v);
            noteLater();
        });
        ComboBox<String> effort = new ComboBox<>();
        effort.getItems().setAll(EFFORTS);
        effort.setValue(agent.effortOverride.isBlank() ? "default" : agent.effortOverride);
        effort.setOnAction(e -> {
            String v = effort.getValue();
            runtime.setEffort(agent, v == null || v.equals("default") ? "" : v);
            noteLater();
        });
        String profileModel = profile == null || profile.model.isBlank() ? "CLI default" : profile.model;
        String profileEffort = profile == null || profile.effort.isBlank() ? "CLI default" : profile.effort;
        g.addRow(r++, new Label("Model"), new VBox(2, model, Ui.dim("default = the profile's: " + profileModel)));
        g.addRow(r++, new Label("Effort"), new VBox(2, effort, Ui.dim("default = the profile's: " + profileEffort)));
        Label applies = Ui.label(runtime.pendingRestart(agent) ? "applies from the next message" : "", "warn");
        g.add(applies, 1, r++);

        // Keys.
        if (!juno) {
            g.addRow(r++, new Label("GitHub key"), keyPicker(Key.Kind.GITHUB, agent.githubKeyId));
            g.addRow(r++, new Label("GitLab key"), keyPicker(Key.Kind.GITLAB, agent.gitlabKeyId));
            Project p = runtime.project(agent);
            if (p != null && !p.remote.isBlank() && agent.githubKeyId == null && agent.gitlabKeyId == null) {
                Label warn = Ui.label("⚠ " + p.name + " has a remote but " + agent.name + " has no key: no GitHub/GitLab "
                        + "access.", "warn");
                g.add(warn, 1, r++);
            }
        }
        box.getChildren().add(g);

        box.getChildren().add(Ui.subheading("Allowed for " + agent.name));
        if (agent.rules.isEmpty()) {
            box.getChildren().add(Ui.dim("Nothing yet. “Always allow for " + agent.name + "” on a permission request "
                    + "adds a rule here."));
        }
        for (Agent.AgentRule rule : List.copyOf(agent.rules)) {
            Label text = new Label(rule.rule);
            text.getStyleClass().add("mono");
            Label meaning = Ui.dim(rule.description + " · added " + WHEN.format(
                    Instant.ofEpochMilli(rule.added).atZone(ZoneId.systemDefault()))
                    + (rule.why.isBlank() ? "" : " for: " + rule.why));
            Button remove = Ui.button("Remove", () -> {
                runtime.removeRule(agent, rule);
                rebuild();
            });
            HBox.setHgrow(meaning, Priority.ALWAYS);
            box.getChildren().add(new VBox(2, Ui.row(text, Ui.grow(), remove), meaning));
        }

        box.getChildren().add(Ui.subheading("Conversations"));
        box.getChildren().add(conversations());

        box.getChildren().add(Ui.subheading("Usage"));
        Label usage = new Label(usageText());
        usage.setWrapText(true);
        box.getChildren().add(usage);
        box.getChildren().add(Ui.dim("Dollar figures are API-price estimates."));

        if (juno) return;
        box.getChildren().add(Ui.subheading("Delete"));
        Button delete = Ui.button("Delete " + agent.name + "…", this::delete);
        delete.getStyleClass().add("danger");
        box.getChildren().addAll(Ui.dim("Removes " + agent.name + ", its worktree (the branch stays), journals, logs, "
                + "conversations list and rules."), delete);
    }

    private void rebuildLater() {
        javafx.application.Platform.runLater(this::rebuild);
    }

    private void noteLater() {
        javafx.application.Platform.runLater(() -> {
            // re-read the "applies from the next message" note without rebuilding the combos being used
            for (javafx.scene.Node n : box.lookupAll(".warn")) {
                if (n instanceof Label l && (l.getText().isEmpty() || l.getText().startsWith("applies"))) {
                    l.setText(runtime.pendingRestart(agent) ? "applies from the next message" : "");
                }
            }
        });
    }

    private ComboBox<Key> keyPicker(Key.Kind kind, String current) {
        ComboBox<Key> c = new ComboBox<>();
        c.getItems().add(null);
        for (Key k : ctx.settings.keys) if (k.kind == kind) c.getItems().add(k);
        c.setConverter(new StringConverter<>() {
            @Override public String toString(Key k) { return k == null ? "No key" : k.label + " · " + k.host; }
            @Override public Key fromString(String s) { return null; }
        });
        c.setValue(ctx.settings.keys.stream().filter(k -> k.id.equals(current)).findFirst().orElse(null));
        c.setOnAction(e -> runtime.setKey(agent, kind, c.getValue() == null ? null : c.getValue().id));
        return c;
    }

    private VBox conversations() {
        VBox list = new VBox(6);
        List<Agent.Conversation> earlier = Conversations.earlier(agent);
        Agent.Conversation current = agent.conversations.stream()
                .filter(c -> c.id.equals(agent.conversationId)).findFirst().orElse(null);
        LiveAgent l = runtime.live(agent.id);
        list.getChildren().add(new Label("Current: " + (current == null ? "not started" : WHEN.format(
                Instant.ofEpochMilli(current.started).atZone(ZoneId.systemDefault())) + " · " + current.firstTask
                + " · " + Meters.tokens(l.conversationTokens) + " tokens")));
        if (earlier.isEmpty()) {
            list.getChildren().add(Ui.dim("No earlier conversations. /new keeps this one here and starts fresh."));
        }
        Path claude = ctx.provider.configFolder();
        boolean busy = agent.turnOpen;
        for (Agent.Conversation c : earlier) {
            boolean there = Conversations.available(claude, c);
            Label t = new Label(WHEN.format(Instant.ofEpochMilli(c.started).atZone(ZoneId.systemDefault()))
                    + (c.ended > 0 ? " → " + WHEN.format(Instant.ofEpochMilli(c.ended).atZone(ZoneId.systemDefault())) : "")
                    + " · " + Meters.tokens(c.tokens) + " tokens · " + c.firstTask);
            t.setWrapText(true);
            t.setMinWidth(0);
            Label summary = Ui.dim(c.summary.isBlank() ? "" : "Last: " + c.summary);
            Button sw = Ui.button("Switch to this", () -> {
                String err = runtime.switchConversation(agent, c.id);
                if (err != null) runtime.say(agent, err, "warn");
                close.run();
            });
            sw.setDisable(!there || busy);
            if (!there) {
                t.getStyleClass().add("dim");
                Ui.withTip(sw, "no longer available: Claude Code has deleted this conversation");
                summary.setText("no longer available");
            } else if (busy) {
                Ui.withTip(sw, "Not while " + agent.name + " is working.");
            }
            HBox.setHgrow(t, Priority.ALWAYS);
            list.getChildren().add(new VBox(2, Ui.row(t, Ui.grow(), sw), summary));
        }
        list.getChildren().add(Ui.dim("The last " + Conversations.KEEP + " earlier conversations are kept, none older than "
                + Conversations.MAX_DAYS + " days."));
        return list;
    }

    private String usageText() {
        LiveAgent l = runtime.live(agent.id);
        return "Context " + Meters.context(l.contextTokens, l.contextWindow) + "\nLast turn "
                + Meters.tokens(l.lastTurnTokens) + " tokens · conversation " + Meters.tokens(l.conversationTokens)
                + " tokens · " + Meters.usd(l.conversationCost) + " API-price estimate\n"
                + "(/usage in the pane lists every turn.)";
    }

    private void delete() {
        var owner = getScene().getWindow();
        String warning = runtime.deleteWarning(agent);
        if (!Ui.confirm(owner, "Delete " + agent.name + "?", (warning == null ? "" : warning + "\n\n")
                + "This removes " + agent.name + " and what Juno made for it. It can't be undone.", "Delete")) return;
        boolean transcripts = false;
        List<Path> theirs = runtime.transcriptsOf(agent);
        if (!theirs.isEmpty()) {
            transcripts = Ui.confirm(owner, "Claude Code's transcripts", "Claude Code keeps " + theirs.size()
                    + " transcript file(s) of " + agent.name + "'s conversations in " + ctx.provider.configFolder()
                    .resolve("projects") + ".\n\nDelete them too? (They're Claude Code's own files; Juno doesn't need "
                    + "them.)", "Delete them too");
        }
        String err = runtime.delete(agent, transcripts);
        if (err != null) Ui.error(owner, "Deleted with a problem", err);
        host.deleted(agent);
    }
}
