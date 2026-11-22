package dev.juno.ui.folders;

import dev.juno.store.FolderTree;
import dev.juno.ui.Ui;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
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
 * One open file (design §9): a text editor, with light colouring for Markdown. Edits are saved a moment after typing
 * stops, and when the tab is closed or the Folders view is left. A file changed on disk meanwhile is never overwritten
 * unasked.
 */
final class FileTab extends Tab {
    private static final Pattern HIGHLIGHT = Pattern.compile(
            "(?<head>^#{1,6} .*$)|(?<fence>^(```|~~~).*$)|(?<quote>^> .*$)|(?<list>^\\s*([-*+]|\\d+\\.) )"
                    + "|(?<code>`[^`\\n]+`)|(?<bold>\\*\\*[^*\\n]+\\*\\*)",
            Pattern.MULTILINE);

    private final FolderTree notes;
    private final Runnable onTitle;
    private String path;
    private final StyleClassedTextArea editor = new StyleClassedTextArea();
    private final VirtualizedScrollPane<StyleClassedTextArea> editorScroll = new VirtualizedScrollPane<>(editor);
    private final HBox banner = new HBox(10);
    private final Label bannerText = new Label();
    private final PauseTransition save = new PauseTransition(Duration.millis(700));
    private long knownModified;
    private boolean dirty;
    private boolean conflict;
    private boolean loading;

    FileTab(FolderTree notes, String path, Runnable onTitle) {
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
            highlight();
        });
        save.setOnFinished(e -> save());
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

        VBox box = new VBox(banner, editorScroll);
        VBox.setVgrow(editorScroll, Priority.ALWAYS);
        setContent(box);
        setClosable(true);
        reload();
    }

    String path() {
        return path;
    }

    FolderTree tree() {
        return notes;
    }

    java.nio.file.Path absolute() {
        return notes.root().resolve(path);
    }

    /** The file moved or was renamed. */
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

    /** Editor text size in points. */
    void size(double points) {
        editor.setStyle("-fx-font-size: " + points + "pt;");
    }

    private void retitle() {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        setText(name + (dirty ? " ●" : ""));
        setTooltip(new javafx.scene.control.Tooltip(notes.root().resolve(path).toString()));
        onTitle.run();
    }

    // ---------------------------------------------------------------- disk

    /** (Re)loads the file, dropping any unsaved edits. */
    void reload() {
        save.stop();
        String text;
        try {
            text = notes.read(path);
        } catch (FolderTree.FolderException e) {
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
        } catch (FolderTree.FolderException e) {
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
        } catch (FolderTree.FolderException e) {
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
        if (!path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(md|markdown)")) return;
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
