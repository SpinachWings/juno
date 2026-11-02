package dev.juno.ui.pane;

import dev.juno.ui.Ui;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Markdown rendered the way agent messages are (design §7.3), for the notepad's live preview: one read-only rich text
 * area, selectable and copyable, with the copy buttons of code blocks.
 */
public final class MarkdownPreview {
    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final VirtualizedScrollPane<StyleClassedTextArea> scroll = new VirtualizedScrollPane<>(area);
    private List<StyledDoc.Action> actions = List.of();

    public MarkdownPreview() {
        area.setEditable(false);
        area.setWrapText(true);
        area.getStyleClass().add("output");
        scroll.getStyleClass().add("output-scroll");
        area.setOnMouseClicked(e -> {
            if (!e.isStillSincePress() || e.getClickCount() != 1) return;
            StyledDoc.Action a = actionAt(e.getX(), e.getY());
            if (a != null) {
                area.deselect();
                a.run().run();
            }
        });
        area.setOnMouseMoved(e -> area.setCursor(actionAt(e.getX(), e.getY()) != null ? Cursor.HAND : Cursor.TEXT));
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> area.copy());
        MenuItem copyAll = new MenuItem("Copy all");
        copyAll.setOnAction(e -> Ui.copy(area.getText()));
        MenuItem selectAll = new MenuItem("Select all");
        selectAll.setOnAction(e -> area.selectAll());
        area.setContextMenu(new ContextMenu(copy, copyAll, selectAll));
    }

    public Node node() {
        return scroll;
    }

    /** The rendered text, as it would be copied. */
    public String text() {
        return area.getText();
    }

    /** Re-renders from the Markdown, keeping the scroll position. */
    public void show(String markdown) {
        double y = scroll.estimatedScrollYProperty().getValue();
        StyledDoc doc = new StyledDoc();
        MarkdownText.write(doc, markdown == null ? "" : markdown, List.of());
        area.replaceText(doc.text.toString());
        if (doc.length() > 0) area.setStyleSpans(0, spans(doc));
        for (int i = 0; i < doc.lineStyles.size() && i < area.getParagraphs().size(); i++)
            area.setParagraphStyle(i, doc.lineStyles.get(i));
        actions = List.copyOf(doc.actions);
        if (y > 0) scroll.scrollYToPixel(y);
    }

    private StyledDoc.Action actionAt(double x, double y) {
        var idx = area.hit(x, y).getCharacterIndex();
        if (idx.isEmpty()) return null;
        int i = idx.getAsInt();
        for (StyledDoc.Action a : actions) if (i >= a.start() && i < a.end()) return a;
        return null;
    }

    private static StyleSpans<Collection<String>> spans(StyledDoc doc) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int pos = 0;
        for (StyledDoc.Span s : doc.spans) {
            if (s.start() > pos) b.add(Collections.emptyList(), s.start() - pos);
            b.add(s.styles(), s.end() - s.start());
            pos = s.end();
        }
        if (pos < doc.length()) b.add(Collections.emptyList(), doc.length() - pos);
        return b.create();
    }
}
