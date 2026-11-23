package dev.juno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.juno.model.BoardLayout;
import dev.juno.store.JunoHome;
import dev.juno.store.LayoutStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardLayoutTest {
    @TempDir Path dir;

    @Test
    void startsWithJunoAlone() {
        assertEquals(List.of("juno"), new BoardLayout().leaves());
    }

    @Test
    void splittingHalvesAPaneAndNeverLeavesAnEmptyOne() {
        BoardLayout b = new BoardLayout();
        b.split("juno", true, "a");
        b.split("a", false, "b");
        assertEquals(List.of("juno", "a", "b"), b.leaves());
        var root = (BoardLayout.Split) b.root();
        assertTrue(root.sideBySide);
        var right = (BoardLayout.Split) root.second;
        assertFalse(right.sideBySide, "b went below a");
    }

    @Test
    void hidingGivesTheSpaceToTheNeighbourAndShowingPutsItBack() {
        BoardLayout b = new BoardLayout();
        b.split("juno", true, "a");
        b.split("a", false, "b");
        String before = b.toJson().toString();
        b.hide("a");
        assertEquals(List.of("juno", "b"), b.leaves());
        assertTrue(b.isHidden("a"));
        b.show("a");
        assertEquals(List.of("juno", "a", "b"), b.leaves());
        assertEquals(before.replace("\"hidden\":{}", ""), b.toJson().toString().replace("\"hidden\":{}", ""),
                "showing an agent restores the layout exactly");
    }

    @Test
    void hidingAndShowingWhenTheNeighbourMovedFallsBackNextToJuno() {
        BoardLayout b = new BoardLayout();
        b.split("juno", true, "a");
        b.split("a", true, "b");
        b.hide("b");
        b.hide("a");
        b.show("b"); // its neighbour a is hidden
        assertEquals(List.of("juno", "b"), b.leaves());
    }

    @Test
    void junoCannotBeHiddenOrRemoved() {
        BoardLayout b = new BoardLayout();
        b.split("juno", true, "a");
        b.hide("juno");
        b.remove("juno");
        assertTrue(b.shown("juno"));
    }

    @Test
    void swapExchangesTwoPanes() {
        BoardLayout b = new BoardLayout();
        b.split("juno", true, "a");
        b.split("a", false, "b");
        b.swap("juno", "b");
        assertEquals(List.of("b", "a", "juno"), b.leaves());
        b.swap("a", "missing");
        assertEquals(List.of("b", "a", "juno"), b.leaves());
    }

    @Test
    void reconcileDropsDeletedAgentsAndPlacesNewOnes() {
        BoardLayout b = new BoardLayout();
        b.split("juno", true, "a");
        b.hide("a");
        b.reconcile(List.of("c"));
        assertFalse(b.isHidden("a"), "a was deleted");
        assertEquals(List.of("juno", "c"), b.leaves());
        b.hide("c");
        b.reconcile(List.of("c"));
        assertEquals(List.of("juno"), b.leaves(), "a hidden agent stays hidden");
    }

    @Test
    void theLayoutIsSavedAndReadBack() {
        JunoHome home = new JunoHome(dir);
        BoardLayout b = new BoardLayout();
        b.split("juno", true, "a");
        b.split("a", false, "b");
        b.split("b", true, "c");
        b.hide("b");
        new LayoutStore(home).save(b);
        BoardLayout back = new LayoutStore(home).load();
        assertEquals(b.leaves(), back.leaves());
        assertEquals(b.toJson(), back.toJson());
        assertTrue(back.isHidden("b"));
        back.show("b");
        assertEquals(List.of("juno", "a", "b", "c"), back.leaves());
    }

    @Test
    void anUnreadableLayoutGivesTheDefault() throws Exception {
        JunoHome home = new JunoHome(dir);
        java.nio.file.Files.writeString(home.layoutFile(), "{ not json");
        assertEquals(List.of("juno"), new LayoutStore(home).load().leaves());
        java.nio.file.Files.writeString(home.layoutFile(), "{\"root\":{\"pane\":\"a\"}}");
        assertEquals(List.of("juno", "a"), new LayoutStore(home).load().leaves(), "Juno is always put back");
    }
}
