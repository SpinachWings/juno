package dev.juno.ui.pane;

import dev.juno.app.AppContext;
import dev.juno.model.Agent;
import dev.juno.model.Key;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.ui.Ui;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.StringConverter;

/**
 * The setup form shown inside the new cell of a split (design §7.1, §7.10): a new agent (name, profile, project,
 * colour, GitHub and GitLab keys) or a hidden one to show. A split cell can't stay empty: Create / Show fill it, Cancel
 * removes it.
 */
public final class SetupForm {
    /** Pre-filled colours, cycled through as agents are created; any colour can be picked. */
    static final String[] COLOURS = {"#6FD6FF", "#FF4FD8", "#7CFFB2", "#FFD166", "#B69CFF", "#FF8A5C", "#5CE1E6"};

    private final StackPane root = new StackPane();

    /**
     * @param hidden  the agents that are hidden now (to offer "show a hidden agent")
     * @param created called with the new agent
     * @param shown   called with the hidden agent chosen
     * @param cancel  removes the cell
     */
    public SetupForm(AppContext ctx, Supplier<List<Agent>> hidden, Consumer<Agent> created, Consumer<Agent> shown,
                     Runnable cancel) {
        TextField name = new TextField();
        name.setPromptText("e.g. bob");
        ComboBox<Profile> profile = new ComboBox<>();
        profile.getItems().setAll(ctx.settings.profiles.stream()
                .filter(p -> !p.name.equals(dev.juno.model.BuiltInProfiles.ORCHESTRATOR)).toList());
        profile.setConverter(new StringConverter<>() {
            @Override public String toString(Profile p) { return p == null ? "" : p.name; }
            @Override public Profile fromString(String s) { return null; }
        });
        if (!profile.getItems().isEmpty()) profile.setValue(profile.getItems().get(0));
        ComboBox<Project> project = new ComboBox<>();
        project.getItems().add(null);
        project.getItems().addAll(ctx.settings.projects);
        project.setConverter(new StringConverter<>() {
            @Override public String toString(Project p) { return p == null ? "No project" : p.name; }
            @Override public Project fromString(String s) { return null; }
        });
        project.setValue(null);
        ColorPicker colour = new ColorPicker(Color.web(COLOURS[ctx.runtime.agents().size() % COLOURS.length]));
        ComboBox<Key> github = keys(ctx, Key.Kind.GITHUB);
        ComboBox<Key> gitlab = keys(ctx, Key.Kind.GITLAB);
        Label problem = Ui.label("", "bad");
        Label hint = Ui.dim("");
        Runnable updateHint = () -> {
            Profile p = profile.getValue();
            Project pr = project.getValue();
            StringBuilder sb = new StringBuilder();
            if (p != null && p.needsProjectFolder && pr == null) {
                sb.append("The ").append(p.name).append(" profile needs a project: you'll be asked for one before its "
                        + "first message runs.\n");
            }
            if (pr != null && !pr.remote.isBlank() && github.getValue() == null && gitlab.getValue() == null) {
                sb.append("⚠ ").append(pr.name).append(" has a remote (").append(pr.remote).append(") but no key is "
                        + "picked: this agent can't reach GitHub/GitLab, only local git.");
            }
            hint.setText(sb.toString().strip());
        };
        profile.valueProperty().addListener((o, a, b) -> updateHint.run());
        project.valueProperty().addListener((o, a, b) -> updateHint.run());
        github.valueProperty().addListener((o, a, b) -> updateHint.run());
        gitlab.valueProperty().addListener((o, a, b) -> updateHint.run());
        updateHint.run();

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.addRow(0, new Label("Name"), name);
        g.addRow(1, new Label("Profile"), profile);
        g.addRow(2, new Label("Project"), project);
        g.addRow(3, new Label("Colour"), colour);
        g.addRow(4, new Label("GitHub key"), github);
        g.addRow(5, new Label("GitLab key"), gitlab);
        var create = Ui.primary("Create", () -> {
            try {
                Project p = project.getValue();
                Agent a = ctx.runtime.create(name.getText(), profile.getValue() == null ? "" : profile.getValue().name,
                        p == null ? null : p.id, hex(colour.getValue()),
                        github.getValue() == null ? null : github.getValue().id,
                        gitlab.getValue() == null ? null : gitlab.getValue().id);
                created.accept(a);
            } catch (IllegalArgumentException ex) {
                problem.setText(ex.getMessage());
            }
        });
        create.setDefaultButton(true);
        var newButtons = Ui.row(create, Ui.button("Cancel", cancel));
        VBox newForm = new VBox(12, g, hint, problem, newButtons);

        // Show a hidden agent instead.
        ComboBox<Agent> hiddenPick = new ComboBox<>();
        hiddenPick.setMaxWidth(Double.MAX_VALUE);
        hiddenPick.setConverter(new StringConverter<>() {
            @Override public String toString(Agent a) { return a == null ? "" : a.name + " · " + a.profile + " · " + a.statusText(); }
            @Override public Agent fromString(String s) { return null; }
        });
        var show = Ui.primary("Show", () -> {
            if (hiddenPick.getValue() != null) shown.accept(hiddenPick.getValue());
        });
        show.disableProperty().bind(hiddenPick.valueProperty().isNull());
        VBox showForm = new VBox(12, Ui.dim("Hidden agents keep running; showing one puts it in this cell."),
                hiddenPick, Ui.row(show, Ui.button("Cancel", cancel)));
        showForm.setVisible(false);
        showForm.managedProperty().bind(showForm.visibleProperty());

        ToggleGroup mode = new ToggleGroup();
        ToggleButton newMode = new ToggleButton("New agent");
        ToggleButton hiddenMode = new ToggleButton("Show a hidden agent");
        newMode.setToggleGroup(mode);
        hiddenMode.setToggleGroup(mode);
        newMode.setSelected(true);
        mode.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) a.setSelected(true);
        });
        newMode.selectedProperty().addListener((o, a, b) -> {
            newForm.setVisible(b);
            newForm.setManaged(b);
            showForm.setVisible(!b);
            if (!b) {
                hiddenPick.getItems().setAll(hidden.get());
                if (!hiddenPick.getItems().isEmpty()) hiddenPick.setValue(hiddenPick.getItems().get(0));
            }
        });
        boolean anyHidden = !hidden.get().isEmpty();
        hiddenMode.setDisable(!anyHidden);
        if (!anyHidden) Ui.withTip(hiddenMode, "No agent is hidden.");

        javafx.scene.layout.FlowPane modes = new javafx.scene.layout.FlowPane(8, 6, newMode, hiddenMode);
        VBox form = new VBox(12, Ui.heading("New pane"), modes, newForm, showForm);
        form.setPadding(new Insets(18));
        form.setMaxWidth(560);
        form.setAlignment(Pos.TOP_LEFT);
        form.getStyleClass().add("setup-form");
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        root.getChildren().add(scroll);
        root.getStyleClass().add("setup-cell");
        root.setMinSize(AgentPane.MIN_WIDTH, AgentPane.MIN_HEIGHT);
        javafx.application.Platform.runLater(name::requestFocus);
    }

    private static ComboBox<Key> keys(AppContext ctx, Key.Kind kind) {
        ComboBox<Key> c = new ComboBox<>();
        c.getItems().add(null);
        for (Key k : ctx.settings.keys) if (k.kind == kind) c.getItems().add(k);
        c.setConverter(new StringConverter<>() {
            @Override public String toString(Key k) { return k == null ? "No key" : k.label + " · " + k.host; }
            @Override public Key fromString(String s) { return null; }
        });
        c.setValue(null);
        return c;
    }

    public StackPane root() {
        return root;
    }

    static String hex(Color c) {
        return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
