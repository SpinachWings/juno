package dev.juno.ui.board;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.juno.model.BoardLayout;
import dev.juno.ui.pane.AgentPane;
import org.junit.jupiter.api.Test;

class BoardCanvasTest {
    @Test
    void minimumSizesAddUpAlongTheSplitAndTakeTheMaxAcross() {
        BoardLayout b = new BoardLayout();
        b.split("juno", true, "a");
        b.split("a", false, "b");
        // juno | (a over b)
        assertEquals(2 * AgentPane.MIN_WIDTH, BoardCanvas.minWidth(b.root()));
        assertEquals(2 * AgentPane.MIN_HEIGHT, BoardCanvas.minHeight(b.root()));
        b.split("juno", false, "c"); // (juno over c) | (a over b)
        assertEquals(2 * AgentPane.MIN_WIDTH, BoardCanvas.minWidth(b.root()));
        assertEquals(2 * AgentPane.MIN_HEIGHT, BoardCanvas.minHeight(b.root()));
        b.split("c", true, "d");
        assertEquals(3 * AgentPane.MIN_WIDTH, BoardCanvas.minWidth(b.root()), "the board grows scrollbars");
    }
}
