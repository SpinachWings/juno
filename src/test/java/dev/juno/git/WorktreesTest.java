package dev.juno.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real git in a scratch repository: worktrees, the protected-branch hooks and chaining the repository's own. */
class WorktreesTest {
    @TempDir Path dir;
    Path repo;

    @BeforeEach
    void repository() throws Exception {
        repo = dir.resolve("repo");
        Files.createDirectories(repo);
        g(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        commit(repo, "init");
    }

    private static String g(Path where, String... args) throws IOException {
        List<String> cmd = new java.util.ArrayList<>(List.of("-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        return Git.run(where, cmd.toArray(String[]::new));
    }

    private static void commit(Path where, String msg) throws IOException {
        g(where, "add", "-A");
        g(where, "commit", "-q", "-m", msg);
    }

    /** Runs git expecting a refusal; returns what git printed. */
    private static String refused(Path where, String... args) {
        return assertThrows(IOException.class, () -> g(where, args)).getMessage();
    }

    @Test
    void twoAgentsGetSeparateWorktreesOnTheirOwnBranches() throws Exception {
        Path w1 = dir.resolve("wt/one"), w2 = dir.resolve("wt/two");
        var c1 = Worktrees.create(repo, w1, Worktrees.branchFor("Bob"));
        var c2 = Worktrees.create(repo, w2, Worktrees.branchFor("Alice B"));
        assertEquals("juno/bob", c1.branch());
        assertEquals("juno/alice-b", c2.branch());
        assertEquals("main", c1.start());
        Files.writeString(w1.resolve("a.txt"), "changed in one\n");
        assertEquals("one\n", Files.readString(w2.resolve("a.txt")).replace("\r\n", "\n"));
        assertEquals("one\n", Files.readString(repo.resolve("a.txt")).replace("\r\n", "\n"));
        assertEquals("juno/bob", Worktrees.currentBranch(w1));
    }

    @Test
    void aTakenBranchNameGetsANumber() throws Exception {
        Worktrees.create(repo, dir.resolve("wt/one"), "juno/bob");
        var again = Worktrees.create(repo, dir.resolve("wt/two"), "juno/bob");
        assertEquals("juno/bob-2", again.branch());
    }

    @Test
    void aProjectWithoutCommitsGivesAClearError() throws Exception {
        Path empty = dir.resolve("empty");
        Files.createDirectories(empty);
        g(empty, "init", "-q");
        IOException e = assertThrows(IOException.class, () -> Worktrees.create(empty, dir.resolve("wt/x"), "juno/x"));
        assertTrue(e.getMessage().contains("no commits"), e.getMessage());
    }

    @Test
    void aBranchCheckedOutElsewhereIsReportedWithWhere() throws Exception {
        Path w = dir.resolve("wt/one");
        Worktrees.create(repo, w, "juno/bob");
        assertEquals(w.toRealPath(), Worktrees.checkedOutAt(repo, "juno/bob").orElseThrow().toRealPath());
        assertEquals(repo.toRealPath(), Worktrees.checkedOutAt(repo, "main").orElseThrow().toRealPath(),
                "your own checkout counts too");
        assertTrue(Worktrees.checkedOutAt(repo, "nowhere").isEmpty());
        // git itself refuses to check it out a second time, naming the place
        assertTrue(refused(repo, "switch", "juno/bob").contains("already"), "git refuses, never forced");
    }

    @Test
    void theGuardHooksRefuseTheBaseBranchesInTheWorktreeOnly() throws Exception {
        Path w = dir.resolve("wt/one");
        Worktrees.create(repo, w, "juno/bob");
        g(repo, "switch", "-q", "-c", "elsewhere"); // frees main in your checkout
        Path hooks = dir.resolve("wt/one.hooks");
        Worktrees.installHooks(repo, w, hooks, Worktrees.baseBranches(repo, "main"));

        // On its own branch everything works, including bringing main up to date.
        Files.writeString(w.resolve("b.txt"), "new\n");
        commit(w, "on my branch");
        g(w, "merge", "-q", "--no-edit", "main");

        // Onto main: refused with the clear message, whatever way it is tried.
        g(w, "switch", "-q", "main");
        Files.writeString(w.resolve("c.txt"), "x\n");
        g(w, "add", "-A");
        String commit = refused(w, "commit", "-q", "-m", "to main");
        assertTrue(commit.contains("committing to `main` isn't allowed"), commit);
        g(w, "reset", "-q", "--hard", "HEAD");
        g(w, "clean", "-fdq");
        String merge = refused(w, "merge", "--no-ff", "--no-edit", "juno/bob");
        assertTrue(merge.contains("main"), merge);
        try {
            g(w, "merge", "--abort");
        } catch (IOException e) {
            // nothing was started
        }
        String ff = refused(w, "merge", "--ff-only", "juno/bob");
        assertTrue(ff.contains("isn't allowed"), "even a fast-forward: " + ff);
        g(w, "switch", "-q", "juno/bob");
        String force = refused(w, "branch", "-f", "main", "juno/bob");
        assertTrue(force.contains("isn't allowed"), force);

        // Pushing to the base branch of a remote is refused too; pushing the own branch is not.
        Path remote = dir.resolve("remote.git");
        Files.createDirectories(remote);
        g(remote, "init", "-q", "--bare", "-b", "main");
        g(repo, "remote", "add", "origin", remote.toString());
        String push = refused(w, "push", "-q", "origin", "juno/bob:main");
        assertTrue(push.contains("pushing to `main` isn't allowed"), push);
        g(w, "push", "-q", "origin", "juno/bob");

        // Your checkout is not affected: it can commit to main.
        g(repo, "switch", "-q", "main");
        Files.writeString(repo.resolve("mine.txt"), "mine\n");
        commit(repo, "mine");
        assertEquals("main", Worktrees.currentBranch(repo));
    }

    @Test
    void theRepositorysOwnHooksStillRunAndCanRefuse() throws Exception {
        Path hooksDir = repo.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path pre = hooksDir.resolve("pre-commit");
        Files.writeString(pre, "#!/bin/sh\necho \"repo hook ran\" >&2\ntest ! -f \"$PWD/forbidden.txt\"\n");
        pre.toFile().setExecutable(true);
        Path w = dir.resolve("wt/one");
        Worktrees.create(repo, w, "juno/bob");
        Worktrees.installHooks(repo, w, dir.resolve("wt/one.hooks"), List.of("main"));
        Files.writeString(w.resolve("ok.txt"), "ok\n");
        commit(w, "fine");
        Files.writeString(w.resolve("forbidden.txt"), "no\n");
        g(w, "add", "-A");
        refused(w, "commit", "-q", "-m", "nope");
    }

    @Test
    void installingAgainKeepsTheOriginalHooksPath() throws Exception {
        Path w = dir.resolve("wt/one");
        Worktrees.create(repo, w, "juno/bob");
        Path hooks = dir.resolve("wt/one.hooks");
        Worktrees.installHooks(repo, w, hooks, List.of("main"));
        String first = Files.readString(hooks.resolve("original-hooks-path"));
        Worktrees.installHooks(repo, w, hooks, List.of("main", "release"));
        assertEquals(first, Files.readString(hooks.resolve("original-hooks-path")), "not Juno's own folder");
        assertTrue(Files.readString(hooks.resolve("protected-branches")).contains("release"));
    }

    @Test
    void removalIsRefusedWhileWorkWouldBeLost() throws Exception {
        Path w = dir.resolve("wt/one");
        Worktrees.create(repo, w, "juno/bob");
        assertNull(Worktrees.unsafeToRemove(w), "a fresh worktree has nothing to lose");
        Files.writeString(w.resolve("new.txt"), "x\n");
        assertTrue(Worktrees.unsafeToRemove(w).contains("uncommitted"));
        commit(w, "work");
        String commits = Worktrees.unsafeToRemove(w);
        assertNotNull(commits);
        assertTrue(commits.contains("1 commit only on juno/bob"), commits);
        g(repo, "merge", "-q", "juno/bob"); // now it is on main too
        assertNull(Worktrees.unsafeToRemove(w));
    }

    @Test
    void aRemovedWorktreeComesBackOnItsBranch() throws Exception {
        Path w = dir.resolve("wt/one");
        Worktrees.create(repo, w, "juno/bob");
        Worktrees.remove(repo, w);
        assertFalse(Files.exists(w));
        assertTrue(Worktrees.branchExists(repo, "juno/bob"), "the branch is kept");
        Worktrees.attach(repo, w, "juno/bob");
        assertEquals("juno/bob", Worktrees.currentBranch(w));
    }

    @Test
    void baseBranchesAreTheRemotesDefaultAndTheBranchItWasMadeFrom() throws Exception {
        assertEquals(List.of("main"), Worktrees.baseBranches(repo, "main"));
        assertEquals(List.of("develop", "main"), Worktrees.baseBranches(repo, "develop"));
        assertEquals(List.of("main"), Worktrees.baseBranches(repo, "juno/other"), "never an agent's own branch");
    }
}
