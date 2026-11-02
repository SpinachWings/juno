package dev.juno.ui.notes;

import dev.juno.store.NotesFolder;
import dev.juno.store.NotesState;
import dev.juno.ui.Ui;
import dev.juno.ui.pane.MarkdownPreview;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * One open note (design §9): a Markdown editor and its live preview. Edits are saved a moment after typing stops, and
 * when the tab is closed or the notepad is left. A note changed on disk meanwhile is never overwritten unasked.
 */
final class NoteTab extends Tab {
    private static final Pattern HIGHLIGHT = Pattern.compile(
            "(?<head>^#{1,6} .*$)|(?<fence>^(```|~~~).*$)|(?<quote>^> .*$)|(?<list>^\\s*([-*+]|\\d+\\.) )"
                    + "|(?<code>`[^`\\n]+`)|(?<bold>\\*\\*[^*\\n]+\\*\\*)",
            Pattern.MULTILINE);

    private final NotesFolder notes;
    private final Runnable onTitle;
    private String path;
    private final StyleClassedTextArea editor = new StyleClassedTextArea();
    private final MarkdownPreview preview = new MarkdownPreview();
    private final SplitPane panes = new SplitPane();
    private final VirtualizedScrollPane<StyleClassedTextArea> editorScroll = new VirtualizedScrollPane<>(editor);
    private final HBox banner = new HBox(10);
    private final Label bannerText = new Label();
    private final PauseTransition save = new PauseTransition(Duration.millis(700));
    private final PauseTransition render = new PauseTransition(Duration.millis(120));
    private long knownModified;
    private boolean dirty;
    private boolean conflict;
    private boolean loading;

    NoteTab(NotesFolder notes, String path, Runnable onTitle) {
        this.notes = notes;
        this.path = path;
        this.onTitle = onTitle;

        editor.setWrapText(true);
        editor.getStyleClass().add("editor");
        editorScroll.getStyleClass().add("output-scroll");
        editor.textProperty().addListener((o, a, b) -> {
            if (loading) return;
            dirty = true;
            retitle();
            save.playFromStart();
            render.playFromStart();
            highlight();
        });
        save.setOnFinished(e -> save());
        render.setOnFinished(e -> preview.show(editor.getText()));
        editor.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.S) {
                e.consume();
                save();
            }
        });

        bannerText.setWrapText(true);
        bannerText.getStyleClass().add("warn");
        HBox.setHgrow(bannerText, Priority.ALWAYS);
        banner.getChildren().addAll(bannerText,
                Ui.button("Load the disk version", () -> reload()),
                Ui.button("Keep mine", () -> keepMine()));
        banner.getStyleClass().add("banner-warn");
        banner.setPadding(new Insets(6, 10, 6, 10));
        banner.setVisible(false);
        banner.setManaged(false);

        panes.getItems().addAll(editorScroll, preview.node());
        VBox box = new VBox(banner, panes);
        VBox.setVgrow(panes, Priority.ALWAYS);
        setContent(box);
        setClosable(true);
        reload();
    }

    String path() {
        return path;
    }

    /** The note moved or was renamed. */
    void retarget(String newPath) {
        this.path = newPath;
        retitle();
    }

    boolean dirty() {
        return dirty;
    }

    String text() {
        return editor.getText();
    }

    void focusEditor() {
        editor.requestFocus();
    }

    void mode(NotesState.Mode mode) {
        panes.getItems().setAll(switch (mode) {
            case EDIT -> java.util.List.of(editorScroll);
            case PREVIEW -> java.util.List.of(preview.node());
            case SPLIT -> java.util.List.of(editorScroll, preview.node());
        });
        if (mode == NotesState.Mode.SPLIT) panes.setDividerPositions(0.5);
    }

    private void retitle() {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        setText(name + (dirty ? " ●" : ""));
        setTooltip(new javafx.scene.control.Tooltip(path));
        onTitle.run();
    }

    // ---------------------------------------------------------------- disk

    /** (Re)loads the file, dropping any unsaved edits. */
    void reload() {
        save.stop();
        String text;
        try {
            text = notes.read(path);
        } catch (NotesFolder.NotesException e) {
            text = "";
            showBanner(e.getMessage());
            conflict = true;
            return;
        }
        loading = true;
        editor.replaceText(text);
        loading = false;
        knownModified = notes.modified(path);
        dirty = false;
        conflict = false;
        banner.setVisible(false);
        banner.setManaged(false);
        preview.show(text);
        highlight();
        retitle();
    }

    /** Writes the edits now, unless the file changed on disk since this tab read it. */
    void save() {
        save.stop();
        if (!dirty || conflict) return;
        if (!notes.isFile(path)) {
            conflict = true;
            showBanner(path + " was deleted or moved outside Juno. Your edits are still here.");
            return;
        }
        if (notes.modified(path) != knownModified) {
            conflict = true;
            showBanner(path + " changed on disk while you were editing it.");
            return;
        }
        try {
            notes.write(path, editor.getText());
        } catch (NotesFolder.NotesException e) {
            conflict = true;
            showBanner(e.getMessage());
            return;
        }
        knownModified = notes.modified(path);
        dirty = false;
        retitle();
    }

    /** Looks at the file: a clean tab follows the disk, an edited one asks. */
    void checkDisk() {
        if (conflict) return;
        if (!notes.isFile(path)) {
            if (dirty) {
                conflict = true;
                showBanner(path + " was deleted or moved outside Juno. Your edits are still here.");
            }
            return;
        }
        if (notes.modified(path) == knownModified) return;
        if (!dirty) reload();
        else {
            conflict = true;
            showBanner(path + " changed on disk while you were editing it.");
        }
    }

    private void keepMine() {
        conflict = false;
        banner.setVisible(false);
        banner.setManaged(false);
        if (notes.isFile(path)) knownModified = notes.modified(path);
        else try {
            notes.write(path, editor.getText());
            knownModified = notes.modified(path);
            dirty = false;
            retitle();
            return;
        } catch (NotesFolder.NotesException e) {
            conflict = true;
            showBanner(e.getMessage());
            return;
        }
        save();
    }

    private void showBanner(String text) {
        bannerText.setText(text);
        banner.setVisible(true);
        banner.setManaged(true);
    }

    // ---------------------------------------------------------------- editor colours

    private void highlight() {
        String text = editor.getText();
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        Matcher m = HIGHLIGHT.matcher(text);
        int last = 0;
        while (m.find()) {
            String style = m.group("head") != null ? "ed-head" : m.group("fence") != null ? "ed-fence"
                    : m.group("quote") != null ? "ed-quote" : m.group("list") != null ? "ed-list"
                    : m.group("code") != null ? "ed-code" : "ed-bold";
            if (m.end() == m.start()) continue;
            b.add(Collections.emptyList(), m.start() - last);
            b.add(Collections.singleton(style), m.end() - m.start());
            last = m.end();
        }
        b.add(Collections.emptyList(), text.length() - last);
        StyleSpans<Collection<String>> spans = b.create();
        if (text.length() > 0) editor.setStyleSpans(0, spans);
    }
}
