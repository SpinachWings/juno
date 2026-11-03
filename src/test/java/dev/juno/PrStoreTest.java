package dev.juno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.juno.model.PullRequestEntry;
import dev.juno.store.PrStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The PRs tab's list (design §7.11): newest first, kept across restarts, removed only when closed. */
class PrStoreTest {
    @TempDir Path dir;

    private static PullRequestEntry entry(String title, long at) {
        PullRequestEntry e = new PullRequestEntry();
        e.title = title;
        e.openedAt = at;
        e.url = "https://example.com/" + title;
        return e;
    }

    @Test
    void entriesStayUntilClosedAndSurviveARestart() {
        Path file = dir.resolve("prs.json");
        PrStore s = new PrStore(file);
        PullRequestEntry a = entry("a", 1000);
        PullRequestEntry b = entry("b", 2000);
        s.add(a);
        s.add(b);
        assertEquals("b", s.list().get(0).title, "newest first");

        PrStore again = new PrStore(file);
        assertEquals(2, again.list().size());
        again.remove(b.id);
        assertEquals(1, new PrStore(file).list().size());
        assertEquals("a", new PrStore(file).list().get(0).title);
        again.remove("no-such-entry");
        assertEquals(1, again.list().size());
    }

    @Test
    void aMissingOrBrokenFileStartsEmpty() throws Exception {
        assertTrue(new PrStore(dir.resolve("none.json")).list().isEmpty());
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{ not json");
        assertTrue(new PrStore(bad).list().isEmpty());
    }
}
