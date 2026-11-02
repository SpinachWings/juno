package dev.juno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.juno.model.Project;
import dev.juno.model.SystemSettings;
import dev.juno.model.Validation;
import dev.juno.store.NotesFolder;
import dev.juno.store.NotesFolder.NotesException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotesTest {
    @TempDir Path tmp;
    Path dir;
    NotesFolder notes;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createDirectories(tmp.resolve("notes"));
        notes = new NotesFolder(dir);
    }

    private List<String> names(List<NotesFolder.Entry> entries) {
        return entries.stream().map(NotesFolder.Entry::name).toList();
    }

    @Test
    void treeListsFoldersFirstAndSkipsHiddenAndOtherFiles() throws IOException {
        Files.writeString(dir.resolve("b.md"), "b");
        Files.writeString(dir.resolve("A.txt"), "a");
        Files.writeString(dir.resolve("photo.png"), "x");
        Files.writeString(dir.resolve("noext"), "x");
        Files.createDirectories(dir.resolve("zeta/inner"));
        Files.writeString(dir.resolve("zeta/inner/deep.markdown"), "d");
        Files.createDirectories(dir.resolve(".obsidian"));
        Files.writeString(dir.resolve(".hidden.md"), "h");
        Files.createDirectories(dir.resolve("alpha"));

        List<NotesFolder.Entry> tree = notes.tree();
        assertEquals(List.of("alpha", "zeta", "A.txt", "b.md"), names(tree));
        NotesFolder.Entry zeta = tree.get(1);
        assertEquals("zeta/inner", zeta.children().get(0).path());
        assertEquals("zeta/inner/deep.markdown", zeta.children().get(0).children().get(0).path());
    }

    @Test
    void createReadWriteAndRename() {
        String path = notes.createNote("", "ideas");
        assertEquals("ideas.md", path);
        assertEquals("", notes.read(path));
        notes.write(path, "# Ideas\n\nünïcode ✓\n");
        assertEquals("# Ideas\n\nünïcode ✓\n", notes.read(path));
        assertThrows(NotesException.class, () -> notes.createNote("", "ideas.md"));
    }

    @Test
    void createRefusesAnExistingNameAndBadNames() {
        notes.createNote("", "a");
        assertTrue(assertThrows(NotesException.class, () -> notes.createNote("", "a")).getMessage().contains("already"));
        assertThrows(NotesException.class, () -> notes.createNote("", "  "));
        assertThrows(NotesException.class, () -> notes.createNote("", "x/y"));
        assertThrows(NotesException.class, () -> notes.createNote("", "x\\y"));
        assertThrows(NotesException.class, () -> notes.createNote("", ".."));
        assertThrows(NotesException.class, () -> notes.createNote("", ".hidden"));
        assertThrows(NotesException.class, () -> notes.createFolder("", "what?"));
        assertThrows(NotesException.class, () -> notes.createNote("missing", "a"));
    }

    @Test
    void renameKeepsTheExtensionAndRefusesAClash() {
        String a = notes.createNote("", "a.txt");
        notes.createNote("", "b");
        assertEquals("c.txt", notes.rename(a, "c"));
        assertFalse(notes.isFile("a.txt"));
        assertThrows(NotesException.class, () -> notes.rename("c.txt", "b.md"));
        String f = notes.createFolder("", "dir");
        assertEquals("folder", notes.rename(f, "folder"));
        assertTrue(notes.isFolder("folder"));
    }

    @Test
    void moveIntoAFolderAndNotIntoItself() {
        notes.createFolder("", "one");
        notes.createFolder("one", "two");
        String n = notes.createNote("", "n");
        assertEquals("one/two/n.md", notes.move(n, "one/two"));
        assertEquals("n.md", notes.move("one/two/n.md", ""));
        assertThrows(NotesException.class, () -> notes.move("one", "one/two"));
        assertThrows(NotesException.class, () -> notes.move("one", "one"));
        notes.createNote("one", "n");
        assertThrows(NotesException.class, () -> notes.move("n.md", "one"));
    }

    @Test
    void deleteRemovesAFolderWithEverythingInIt() {
        notes.createFolder("", "f");
        notes.createFolder("f", "g");
        notes.createNote("f/g", "x");
        notes.createNote("f", "y");
        assertEquals(3, notes.countBelow("f")); // g, g/x.md, y.md
        notes.delete("f");
        assertFalse(Files.exists(dir.resolve("f")));
        assertThrows(NotesException.class, () -> notes.delete("f"));
        assertThrows(NotesException.class, () -> notes.delete(""));
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void nothingOutsideTheFolderIsReachable() throws IOException {
        Path outside = tmp.resolve("secret.md");
        Files.writeString(outside, "secret");
        assertThrows(NotesException.class, () -> notes.read("../secret.md"));
        assertThrows(NotesException.class, () -> notes.read("..\\secret.md"));
        assertThrows(NotesException.class, () -> notes.read(outside.toString()));
        assertThrows(NotesException.class, () -> notes.write("a/../../secret.md", "x"));
        assertThrows(NotesException.class, () -> notes.delete("../secret.md"));
        assertThrows(NotesException.class, () -> notes.move("../secret.md", ""));
        assertEquals("secret", Files.readString(outside));
    }

    @Test
    void writeOnlyTouchesNotesAndLeavesNoTempFiles() throws IOException {
        assertThrows(NotesException.class, () -> notes.write("a.exe", "x"));
        String p = notes.createNote("", "n");
        for (int i = 0; i < 5; i++) notes.write(p, "v" + i);
        try (var s = Files.list(dir)) {
            assertEquals(List.of("n.md"), s.map(x -> x.getFileName().toString()).toList());
        }
    }

    @Test
    void aBinaryFileIsRefusedNotShownGarbled() throws IOException {
        Files.write(dir.resolve("bad.md"), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd, 0x01});
        assertTrue(assertThrows(NotesException.class, () -> notes.read("bad.md")).getMessage().contains("UTF-8"));
    }

    // ---------------------------------------------------------------- overlap checks (design §9)

    @Test
    void overlapIsEitherDirectionAndEquality() throws IOException {
        Path a = Files.createDirectories(tmp.resolve("proj"));
        Path inner = Files.createDirectories(a.resolve("docs"));
        Path other = Files.createDirectories(tmp.resolve("project2"));
        assertTrue(Validation.overlap(a.toString(), a.toString()));
        assertTrue(Validation.overlap(a.toString(), inner.toString()));
        assertTrue(Validation.overlap(inner.toString(), a.toString()));
        assertTrue(Validation.overlap(a.toString(), a.resolve("not-yet-made/x").toString()));
        assertTrue(Validation.overlap(a + "/docs/../docs", a.toString()));
        assertFalse(Validation.overlap(a.toString(), other.toString()), "a shared name prefix is not overlap");
    }

    @Test
    void theNotesFolderIsRefusedInsideOrAroundAProject() throws IOException {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Project p = new Project();
        p.id = "p1";
        p.name = "api";
        p.folder = repo.toString();
        SystemSettings s = new SystemSettings();

        s.notesFolder = repo.resolve("notes").toString();
        assertTrue(Validation.system(s, List.of(p)).get(0).contains("project api"));
        s.notesFolder = tmp.toString();
        assertEquals(1, Validation.system(s, List.of(p)).size());
        s.notesFolder = dir.toString();
        assertTrue(Validation.system(s, List.of(p)).isEmpty());
        s.notesFolder = "";
        assertTrue(Validation.system(s, List.of(p)).isEmpty());
    }

    @Test
    void aProjectIsRefusedInsideOrAroundTheNotesFolder() throws IOException {
        Path repo = Files.createDirectories(dir.resolve("work/repo"));
        Project p = new Project();
        p.id = "p1";
        p.name = "api";
        p.folder = repo.toString();
        assertTrue(Validation.project(p, List.of(), dir.toString()).get(0).contains("notes folder"));
        p.folder = tmp.toString();
        assertEquals(1, Validation.project(p, List.of(), dir.toString()).size());
        p.folder = Files.createDirectories(tmp.resolve("elsewhere")).toString();
        assertTrue(Validation.project(p, List.of(), dir.toString()).isEmpty());
        assertTrue(Validation.project(p, List.of(), "").isEmpty());
    }
}
