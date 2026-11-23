package dev.juno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.juno.model.Project;
import dev.juno.model.Validation;
import dev.juno.store.FolderTree;
import dev.juno.store.FolderTree.FolderException;
import dev.juno.store.FoldersState;
import dev.juno.store.JunoHome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoldersTest {
    @TempDir Path tmp;
    Path dir;
    FolderTree folder;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createDirectories(tmp.resolve("notes"));
        folder = new FolderTree(dir);
    }

    private List<String> names(List<FolderTree.Entry> entries) {
        return entries.stream().map(FolderTree.Entry::name).toList();
    }

    @Test
    void listShowsOneLevelFoldersFirstAndSkipsOnlyHiddenEntries() throws IOException {
        Files.writeString(dir.resolve("b.md"), "b");
        Files.writeString(dir.resolve("A.txt"), "a");
        Files.writeString(dir.resolve("Main.java"), "x");
        Files.writeString(dir.resolve("noext"), "x");
        Files.createDirectories(dir.resolve("zeta/inner"));
        Files.writeString(dir.resolve("zeta/inner/deep.markdown"), "d");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".hidden.md"), "h");
        Files.createDirectories(dir.resolve("alpha"));

        assertEquals(List.of("alpha", "zeta", "A.txt", "b.md", "Main.java", "noext"), names(folder.list("")));
        assertEquals("zeta/inner", folder.list("zeta").get(0).path());
        assertEquals("zeta/inner/deep.markdown", folder.list("zeta/inner").get(0).path());
        assertThrows(FolderException.class, () -> folder.list("nope"));
    }

    @Test
    void createReadWriteAndRename() {
        String path = folder.createFile("", "ideas");
        assertEquals("ideas.md", path);
        assertEquals("", folder.read(path));
        folder.write(path, "# Ideas\n\nünïcode ✓\n");
        assertEquals("# Ideas\n\nünïcode ✓\n", folder.read(path));
        assertThrows(FolderException.class, () -> folder.createFile("", "ideas.md"));
        assertEquals("Main.java", folder.createFile("", "Main.java"), "an extension is kept, whatever it is");
        folder.write("Main.java", "class Main {}");
    }

    @Test
    void createRefusesAnExistingNameAndBadNames() {
        folder.createFile("", "a");
        assertTrue(assertThrows(FolderException.class, () -> folder.createFile("", "a")).getMessage().contains("already"));
        assertThrows(FolderException.class, () -> folder.createFile("", "  "));
        assertThrows(FolderException.class, () -> folder.createFile("", "x/y"));
        assertThrows(FolderException.class, () -> folder.createFile("", "x\\y"));
        assertThrows(FolderException.class, () -> folder.createFile("", ".."));
        assertThrows(FolderException.class, () -> folder.createFile("", ".hidden"));
        assertThrows(FolderException.class, () -> folder.createFolder("", "what?"));
        assertThrows(FolderException.class, () -> folder.createFile("missing", "a"));
    }

    @Test
    void renameKeepsTheExtensionAndRefusesAClash() {
        String a = folder.createFile("", "a.txt");
        folder.createFile("", "b");
        assertEquals("c.txt", folder.rename(a, "c"));
        assertFalse(folder.isFile("a.txt"));
        assertThrows(FolderException.class, () -> folder.rename("c.txt", "b.md"));
        String f = folder.createFolder("", "dir");
        assertEquals("folder", folder.rename(f, "folder"));
        assertTrue(folder.isFolder("folder"));
    }

    @Test
    void moveIntoAFolderAndNotIntoItself() {
        folder.createFolder("", "one");
        folder.createFolder("one", "two");
        String n = folder.createFile("", "n");
        assertEquals("one/two/n.md", folder.move(n, "one/two"));
        assertEquals("n.md", folder.move("one/two/n.md", ""));
        assertThrows(FolderException.class, () -> folder.move("one", "one/two"));
        assertThrows(FolderException.class, () -> folder.move("one", "one"));
        folder.createFile("one", "n");
        assertThrows(FolderException.class, () -> folder.move("n.md", "one"));
    }

    @Test
    void deleteRemovesAFolderWithEverythingInIt() {
        folder.createFolder("", "f");
        folder.createFolder("f", "g");
        folder.createFile("f/g", "x");
        folder.createFile("f", "y");
        assertEquals(3, folder.countBelow("f")); // g, g/x.md, y.md
        folder.delete("f");
        assertFalse(Files.exists(dir.resolve("f")));
        assertThrows(FolderException.class, () -> folder.delete("f"));
        assertThrows(FolderException.class, () -> folder.delete(""));
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void nothingOutsideTheFolderIsReachable() throws IOException {
        Path outside = tmp.resolve("secret.md");
        Files.writeString(outside, "secret");
        assertThrows(FolderException.class, () -> folder.read("../secret.md"));
        assertThrows(FolderException.class, () -> folder.read("..\\secret.md"));
        assertThrows(FolderException.class, () -> folder.read(outside.toString()));
        assertThrows(FolderException.class, () -> folder.write("a/../../secret.md", "x"));
        assertThrows(FolderException.class, () -> folder.delete("../secret.md"));
        assertThrows(FolderException.class, () -> folder.move("../secret.md", ""));
        assertEquals("secret", Files.readString(outside));
    }

    @Test
    void writesAreAtomicAndLeaveNoTempFiles() throws IOException {
        String p = folder.createFile("", "n");
        for (int i = 0; i < 5; i++) folder.write(p, "v" + i);
        try (var s = Files.list(dir)) {
            assertEquals(List.of("n.md"), s.map(x -> x.getFileName().toString()).toList());
        }
    }

    @Test
    void aBinaryOrHugeFileIsRefusedNotShownGarbled() throws IOException {
        Files.write(dir.resolve("bad.png"), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd, 0x01});
        assertTrue(assertThrows(FolderException.class, () -> folder.read("bad.png")).getMessage().contains("UTF-8"));
        Files.write(dir.resolve("big.txt"), new byte[(int) FolderTree.MAX_EDIT_BYTES + 1]);
        assertTrue(assertThrows(FolderException.class, () -> folder.read("big.txt")).getMessage().contains("too large"));
    }

    @Test
    void aProjectMayLiveInsideOrAroundAListedFolder() throws IOException {
        Path repo = Files.createDirectories(dir.resolve("work/repo"));
        Project p = new Project();
        p.id = "p1";
        p.name = "api";
        p.folder = repo.toString();
        assertTrue(Validation.project(p, List.of()).isEmpty());
        p.folder = tmp.toString();
        assertTrue(Validation.project(p, List.of()).isEmpty());
    }

    @Test
    void whatTheViewRemembersRoundTripsAndDefaultsWhenMissing(@TempDir Path home) {
        JunoHome h = new JunoHome(home);
        assertEquals(FoldersState.DEFAULT_SIZE, FoldersState.load(h).size);
        FoldersState s = new FoldersState();
        s.open = List.of(dir.resolve("a.md").toString());
        s.collapsed = List.of(dir.toString());
        s.size = 18;
        s.save(h);
        FoldersState back = FoldersState.load(h);
        assertEquals(s.open, back.open);
        assertEquals(s.collapsed, back.collapsed);
        assertEquals(18, back.size);
    }
}
