package dev.juno.ui.board;

import dev.juno.ui.Ui;
import dev.juno.ui.pane.AgentPane;
import dev.juno.ui.pane.PaneHost;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/**
 * Juno's reserved pane (design §7.1): always on the board, movable, never closed or hidden. Juno herself arrives in
 * Milestone 4; until then it says so.
 * TODO(Milestone 4): the orchestrator's conversation, in the same pane as any agent.
 */
final class JunoPane {
    private final BorderPane root = new BorderPane();
    private final HBox top;
    private final Button maximise;
    private final PaneHost host;

    JunoPane(PaneHost host, boolean hasAgents) {
        this.host = host;
        Label chip = new Label("Idle");
        chip.getStyleClass().add("status-chip");
        Label name = new Label("Juno");
        name.getStyleClass().add("pane-name");
        Label what = Ui.dim("orchestrator");
        Button right = Ui.button("⇥", () -> host.split(dev.juno.model.BoardLayout.JUNO, true));
        Ui.withTip(right, "Split: a new pane to the right");
        Button down = Ui.button("⤓", () -> host.split(dev.juno.model.BoardLayout.JUNO, false));
        Ui.withTip(down, "Split: a new pane below");
        maximise = Ui.button("⤢", () -> host.toggleMaximise(dev.juno.model.BoardLayout.JUNO));
        top = new HBox(6, chip, name, what, Ui.grow(), right, down, maximise);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("pane-top");
        top.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !(e.getTarget() instanceof Button)) host.toggleMaximise(dev.juno.model.BoardLayout.JUNO);
        });

        Label body = Ui.dim("Juno, the orchestrator, arrives in Milestone 4. This pane is hers: always on the board, "
                + "movable, never hidden.\n\nCreate agents with + New agent; each gets a pane here.");
        body.setMaxWidth(380);
        StackPane center = new StackPane(body);
        center.setAlignment(Pos.CENTER);
        root.setTop(top);
        root.setCenter(center);
        root.getStyleClass().addAll("agent-pane", "juno-pane");
        root.setMinSize(AgentPane.MIN_WIDTH, AgentPane.MIN_HEIGHT);
        root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> host.used(dev.juno.model.BoardLayout.JUNO));
    }

    BorderPane root() {
        return root;
    }

    HBox topBar() {
        return top;
    }

    void refresh() {
        maximise.setText(host.maximised(dev.juno.model.BoardLayout.JUNO) ? "⤡" : "⤢");
    }
}
