package dev.juno.ui.board;

import dev.juno.model.BoardLayout;
import dev.juno.ui.pane.AgentPane;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Places the panes of a {@link BoardLayout}: every split halves its space, and no pane gets less than its minimum, so
 * the board (inside a scroll pane) grows scrollbars instead of squeezing panes (design §7.1). Nothing is resizable.
 */
final class BoardCanvas extends Pane {
    private static final double GAP = 3;
    private BoardLayout.Node tree;
    private Map<String, Node> cells = Map.of();

    void show(BoardLayout.Node tree, Map<String, Node> cells) {
        this.tree = tree;
        this.cells = cells;
        getChildren().setAll(cells.values());
        requestLayout();
    }

    static double minWidth(BoardLayout.Node n) {
        if (n instanceof BoardLayout.Split s) {
            return s.sideBySide ? minWidth(s.first) + minWidth(s.second) : Math.max(minWidth(s.first), minWidth(s.second));
        }
        return AgentPane.MIN_WIDTH;
    }

    static double minHeight(BoardLayout.Node n) {
        if (n instanceof BoardLayout.Split s) {
            return s.sideBySide ? Math.max(minHeight(s.first), minHeight(s.second)) : minHeight(s.first) + minHeight(s.second);
        }
        return AgentPane.MIN_HEIGHT;
    }

    @Override
    protected double computeMinWidth(double height) {
        return tree == null ? 0 : minWidth(tree);
    }

    @Override
    protected double computeMinHeight(double width) {
        return tree == null ? 0 : minHeight(tree);
    }

    @Override
    protected double computePrefWidth(double height) {
        return computeMinWidth(height);
    }

    @Override
    protected double computePrefHeight(double width) {
        return computeMinHeight(width);
    }

    @Override
    protected void layoutChildren() {
        if (tree != null) place(tree, 0, 0, Math.max(getWidth(), minWidth(tree)), Math.max(getHeight(), minHeight(tree)));
    }

    private void place(BoardLayout.Node n, double x, double y, double w, double h) {
        if (n instanceof BoardLayout.Leaf l) {
            Node cell = cells.get(l.id);
            if (cell != null) cell.resizeRelocate(x + GAP, y + GAP, Math.max(0, w - 2 * GAP), Math.max(0, h - 2 * GAP));
            return;
        }
        BoardLayout.Split s = (BoardLayout.Split) n;
        if (s.sideBySide) {
            double a = minWidth(s.first), b = minWidth(s.second);
            double first = Math.max(a, Math.min(w / 2, w - b));
            place(s.first, x, y, first, h);
            place(s.second, x + first, y, w - first, h);
        } else {
            double a = minHeight(s.first), b = minHeight(s.second);
            double first = Math.max(a, Math.min(h / 2, h - b));
            place(s.first, x, y, w, first);
            place(s.second, x, y + first, w, h - first);
        }
    }
}
