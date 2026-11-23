package dev.juno;

import static org.junit.jupiter.api.Assertions.*;

import dev.juno.model.Settings;
import dev.juno.model.SkillPack;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.JunoHome;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsTest {
    @TempDir Path tmp;

    private static void skill(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: d\n---\nbody\n");
    }

    /** Zips {@code folder} (entries under its own name) next to it, as a skill pack archive. */
    private static Path zipOf(Path folder) throws Exception {
        Path zip = folder.resolveSibling(folder.getFileName() + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip)); var files = Files.walk(folder)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                z.putNextEntry(new ZipEntry(folder.getFileName() + "/" + folder.relativize(f).toString().replace('\\', '/')));
                z.write(Files.readAllBytes(f));
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void bundledSuperpowersIsRefreshedWithLockedAssignments() throws Exception {
        JunoHome home = new JunoHome(tmp);
        SkillLibrary lib = new SkillLibrary(home);
        Settings s = new Settings();
        lib.refreshBundled(s);
        SkillPack sp = s.skillPacks.get(0);
        assertTrue(sp.bundled);
        assertEquals("using-superpowers", sp.entrySkill);
        List<String> all = lib.skillsOf(sp);
        assertTrue(all.containsAll(List.of("brainstorming", "writing-plans", "using-git-worktrees", "test-driven-development")));

        // A stale file is replaced on the next start-up.
        Files.writeString(home.bundledSkillsDir().resolve("superpowers/stale.txt"), "x");
        lib.refreshBundled(s);
        assertEquals(1, s.skillPacks.size());
        assertFalse(Files.exists(home.bundledSkillsDir().resolve("superpowers/stale.txt")));

        SkillResolver r = new SkillResolver(lib);
        List<String> coder = r.enabledIn(sp, "coder");
        assertTrue(coder.contains("using-git-worktrees"), "coders make their own worktrees");
        assertEquals(all.size(), coder.size());
        assertEquals(List.of("brainstorming", "using-superpowers", "writing-plans"),
                r.enabledIn(sp, "planner").stream().sorted().toList());
        assertTrue(r.enabledIn(sp, "searcher").isEmpty());

        // Other built-ins never get a bundled pack, even if old data says so.
        sp.profiles.add("searcher");
        assertTrue(r.enabledIn(sp, "searcher").isEmpty());
        assertFalse(r.assigned(sp, "searcher"));
        dev.juno.model.Profile mine = new dev.juno.model.Profile();
        mine.name = "my coder";
        assertTrue(r.assignable(sp, mine));
        assertFalse(r.assignable(sp, dev.juno.model.BuiltInProfiles.create().get(3)));
        assertTrue(r.assignable(sp, dev.juno.model.BuiltInProfiles.create().get(1)));
        sp.profiles.remove("searcher");

        // Own profiles may get the pack with any selection; locked ones can't be changed by the user's data.
        sp.profiles.add("my coder");
        sp.disabledPerProfile.put("my coder", List.of("brainstorming"));
        sp.disabledPerProfile.put("coder", List.of("brainstorming"));
        assertFalse(r.enabledIn(sp, "my coder").contains("brainstorming"));
        assertTrue(r.enabledIn(sp, "my coder").contains("using-git-worktrees"));
        assertTrue(r.enabledIn(sp, "coder").contains("brainstorming"));
    }

    @Test
    void importsSingleFileAndZips() throws Exception {
        SkillLibrary lib = new SkillLibrary(new JunoHome(tmp.resolve("home")));
        Settings s = new Settings();

        Path single = tmp.resolve("SKILL.md");
        Files.writeString(single, "---\nname: tidy\n---\nbody");
        SkillPack a = lib.importPack(single, s);
        assertEquals(List.of("tidy"), lib.skillsOf(a));
        assertTrue(a.wholeOrchestration);

        Path folder = tmp.resolve("my-pack");
        skill(folder.resolve("one"), "one");
        skill(folder.resolve("two"), "two");
        SkillPack b = lib.importPack(zipOf(folder), s);
        assertEquals(List.of("one", "two"), lib.skillsOf(b));
        assertEquals("my-pack", b.name);

        Path zip = tmp.resolve("bundle.skill");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("bundle/skills/alpha/SKILL.md"));
            z.write("---\nname: alpha\n---\n".getBytes());
            z.closeEntry();
        }
        SkillPack c = lib.importPack(zip, s);
        assertEquals(List.of("alpha"), lib.skillsOf(c));

        // Same name again gets its own id.
        SkillPack b2 = lib.importPack(zipOf(folder), s);
        assertNotEquals(b.id, b2.id);

        lib.remove(b2, s);
        assertFalse(Files.exists(lib.packDir(b2)));
        assertThrows(java.io.IOException.class, () -> lib.importPack(tmp.resolve("a-folder"), s), "folders are not imported");
    }

    @Test
    void rejectsUnsafeZipAndEmptyFolders() throws Exception {
        SkillLibrary lib = new SkillLibrary(new JunoHome(tmp.resolve("home")));
        Settings s = new Settings();
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("../escape.txt"));
            z.write(1);
            z.closeEntry();
        }
        assertThrows(java.io.IOException.class, () -> lib.importPack(zip, s));
        assertFalse(Files.exists(tmp.resolve("escape.txt")));

        Path empty = Files.createDirectories(tmp.resolve("empty"));
        assertThrows(java.io.IOException.class, () -> lib.importPack(zipOf(empty), s));
        assertTrue(s.skillPacks.isEmpty());
    }

    @Test
    void perProfileAssignmentAndSwitches() throws Exception {
        SkillLibrary lib = new SkillLibrary(new JunoHome(tmp.resolve("home")));
        Settings s = new Settings();
        Path folder = tmp.resolve("p");
        skill(folder.resolve("one"), "one");
        skill(folder.resolve("two"), "two");
        SkillPack p = lib.importPack(zipOf(folder), s);
        SkillResolver r = new SkillResolver(lib);

        assertEquals(List.of("one", "two"), r.enabledIn(p, "anyone"));
        p.wholeOrchestration = false;
        assertTrue(r.enabledIn(p, "coder").isEmpty());
        p.profiles.add("coder");
        p.disabledSkills.add("one");
        assertEquals(List.of("two"), r.enabledIn(p, "coder"));
        p.disabledPerProfile.put("coder", List.of("two"));
        assertTrue(r.enabledFor(s, "coder").isEmpty());
    }
}
