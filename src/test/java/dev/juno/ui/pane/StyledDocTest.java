package dev.juno.ui.pane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Text the output view cuts short always keeps its whole text for a tooltip. */
class StyledDocTest {
    @Test
    void aCutTextKeepsItsWholeForATooltip() {
        StyledDoc d = new StyledDoc();
        String full = "x".repeat(250);
        d.shortened(full, 200, "dim");
        assertEquals("x".repeat(200) + "…", d.text.toString());
        assertEquals(1, d.tips.size());
        assertEquals(full, d.tips.get(0).text());
        assertEquals(0, d.tips.get(0).start());
        assertEquals(201, d.tips.get(0).end());
    }

    @Test
    void aTextWithMoreLinesIsCutAtTheFirstAndKeepsAllOfThem() {
        StyledDoc d = new StyledDoc();
        d.shortened("first line\nsecond line", 200);
        assertEquals("first line…", d.text.toString());
        assertEquals("first line\nsecond line", d.tips.get(0).text());
    }

    @Test
    void aShortTextIsShownAsItIsWithNoTooltip() {
        StyledDoc d = new StyledDoc();
        d.shortened("short", 200);
        assertEquals("short", d.text.toString());
        assertTrue(d.tips.isEmpty());
    }

    @Test
    void tipsFollowTheirTextWhenDocumentsAreJoined() {
        StyledDoc a = new StyledDoc();
        a.add("0123456789");
        StyledDoc b = new StyledDoc();
        b.add("ab");
        b.tip("the whole of it", () -> b.add(" …", "dim"));
        a.append(b);
        assertEquals(1, a.tips.size());
        assertEquals(12, a.tips.get(0).start());
        assertEquals(14, a.tips.get(0).end());
        assertEquals("the whole of it", a.tips.get(0).text());
    }
}
