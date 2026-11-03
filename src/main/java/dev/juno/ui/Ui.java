package dev.juno.ui;

import java.io.File;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

/** Small helpers shared by the Settings tabs. */
public final class Ui {
    private Ui() {}

    public static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        return l;
    }

    public static Label subheading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("subheading");
        return l;
    }

    public static Label dim(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("dim");
        l.setWrapText(true);
        return l;
    }

    public static Label label(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setWrapText(true);
        return l;
    }

    public static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static Button primary(String text, Runnable action) {
        Button b = button(text, action);
        b.getStyleClass().add("primary");
        return b;
    }

    public static HBox row(Node... nodes) {
        HBox h = new HBox(8, nodes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static VBox column(double gap, Node... nodes) {
        VBox v = new VBox(gap, nodes);
        v.setPadding(new Insets(12));
        return v;
    }

    public static Node withTip(Node node, String tip) {
        if (tip != null && !tip.isBlank()) {
            Tooltip t = new Tooltip(tip);
            t.setWrapText(true);
            t.setMaxWidth(380);
            Tooltip.install(node, t);
        }
        return node;
    }

    /**
     * Wherever a label, button, tab, list or tree row shows its text cut short with an ellipsis, hovering it shows the
     * whole text in a tooltip (unless it has a tooltip of its own). Install once on a scene's root, or a popup's content.
     */
    public static void truncationTips(Node root) {
        root.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_ENTERED_TARGET, e -> {
            for (Node n = e.getTarget() instanceof Node t ? t : null; n != null; n = n.getParent()) {
                if (n instanceof javafx.scene.control.Labeled l) {
                    showWholeTextWhenCut(l);
                    return;
                }
            }
        });
    }

    private static final String TRUNCATION_TIP = "juno-truncation-tip";

    private static void showWholeTextWhenCut(javafx.scene.control.Labeled l) {
        Object mine = l.getProperties().get(TRUNCATION_TIP);
        if (l.getTooltip() != null && l.getTooltip() != mine) return; // an explicit tooltip says it better
        String full = l.getText();
        boolean cut = false;
        if (full != null && !full.isBlank()) {
            javafx.scene.Node text = l.lookup(".text");
            cut = text instanceof javafx.scene.text.Text t && !t.getText().equals(full);
        }
        if (cut) {
            Tooltip tip = mine instanceof Tooltip t ? t : new Tooltip();
            tip.setText(full);
            tip.setWrapText(true);
            tip.setMaxWidth(560);
            if (mine == null) {
                l.getProperties().put(TRUNCATION_TIP, tip);
                l.setTooltip(tip);
            }
        } else if (mine != null) {
            l.setTooltip(null);
            l.getProperties().remove(TRUNCATION_TIP);
        }
    }

    public static void info(Window owner, String title, String text) {
        alert(Alert.AlertType.INFORMATION, owner, title, text);
    }

    public static void error(Window owner, String title, String text) {
        alert(Alert.AlertType.ERROR, owner, title, text);
    }

    private static void alert(Alert.AlertType type, Window owner, String title, String text) {
        Alert a = new Alert(type, text, ButtonType.OK);
        a.setHeaderText(title);
        if (owner != null) a.initOwner(owner);
        a.getDialogPane().getStylesheets().setAll(Theme.class.getResource("/dev/juno/ui/juno.css").toExternalForm());
        truncationTips(a.getDialogPane());
        a.showAndWait();
    }

    public static boolean confirm(Window owner, String title, String text, String confirmLabel) {
        ButtonType yes = new ButtonType(confirmLabel, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, yes, ButtonType.CANCEL);
        a.setHeaderText(title);
        if (owner != null) a.initOwner(owner);
        a.getDialogPane().getStylesheets().setAll(Theme.class.getResource("/dev/juno/ui/juno.css").toExternalForm());
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == yes;
    }

    /** Several problems in one dialog. */
    public static void problems(Window owner, List<String> problems) {
        error(owner, "Can't save yet", String.join("\n", problems.stream().map(p -> "• " + p).toList()));
    }

    public static Optional<File> chooseFolder(Window owner, String title, String current) {
        DirectoryChooser c = new DirectoryChooser();
        c.setTitle(title);
        if (current != null && !current.isBlank()) {
            File f = new File(current.trim());
            if (f.isDirectory()) c.setInitialDirectory(f);
        }
        return Optional.ofNullable(c.showDialog(owner));
    }

    public static void copy(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /** Runs {@code work} off the UI thread and delivers its result on it. */
    public static <T> void background(java.util.function.Supplier<T> work, java.util.function.Consumer<T> done) {
        Thread t = new Thread(() -> {
            T result = work.get();
            Platform.runLater(() -> done.accept(result));
        }, "juno-background");
        t.setDaemon(true);
        t.start();
    }
}
