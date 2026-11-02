package dev.juno.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The git environment (design §5.1.1) and review snapshots (§6.5), with real git and only local repositories. */
class GitAccessTest {
    @TempDir Path dir;
    private Path origin, repo;
    private String head;

    private static String git(Path where, String... args) throws IOException {
        return Git.run(where, args).strip();
    }

    @BeforeEach
    void repositories() throws Exception {
        origin = dir.resolve("owner/repo.git");
        Files.createDirectories(origin);
        git(origin, "init", "--bare", "-b", "main");
        repo = dir.resolve("checkout");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.name", "t");
        git(repo, "config", "user.email", "t@example.com");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "one");
        git(repo, "remote", "add", "origin", origin.toString());
        git(repo, "push", "-q", "origin", "main");
        head = git(repo, "rev-parse", "HEAD");
    }

    @Test
    void withoutAKeyOnlyLocalRepositoriesWork() throws Exception {
        Map<String, String> env = GitAccess.forAgent(List.of(), "");
        assertEquals("file", env.get("GIT_ALLOW_PROTOCOL"));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertTrue(env.containsKey("SSH_AUTH_SOCK") && env.get("SSH_AUTH_SOCK") == null, "the SSH agent isn't passed in");
        assertTrue(env.containsKey("GH_TOKEN") && env.get("GH_TOKEN") == null);
        Git.run(repo, env, 30, "fetch", "-q", "origin");
        IOException https = assertThrows(IOException.class,
                () -> Git.run(repo, env, 30, "ls-remote", "https://github.com/octocat/Hello-World.git"));
        assertTrue(https.getMessage().contains("transport 'https' not allowed"), https.getMessage());
        IOException ssh = assertThrows(IOException.class,
                () -> Git.run(repo, env, 30, "ls-remote", "git@github.com:octocat/Hello-World.git"));
        assertTrue(ssh.getMessage().contains("transport 'ssh' not allowed"), ssh.getMessage());
    }

    @Test
    void theUsersCredentialHelpersAreCleared() throws Exception {
        git(repo, "config", "credential.helper", "!echo mine");
        git(repo, "config", "credential.https://github.com.helper", "!echo mine-too");
        Map<String, String> none = GitAccess.forAgent(List.of(), "");
        // git applies an empty value as "forget the helpers so far", and this one is read last.
        assertEquals("", none.get("GIT_CONFIG_VALUE_0"));
        assertEquals("credential.helper", none.get("GIT_CONFIG_KEY_0"));
        assertTrue(Git.run(repo, none, 30, "config", "--get-all", "credential.helper").strip().endsWith("mine"),
                "both are visible; the last, empty one is what counts");
        String last = Git.run(repo, none, 30, "config", "--get-all", "credential.helper");
        assertTrue(last.endsWith("\n\n"), "the empty value comes last: " + last);
    }

    @Test
    void sshRemotesAreUsedOverHttpsWithAKey() throws Exception {
        Map<String, String> env = GitAccess.forAgent(List.of("github.com"), "helper.sh");
        assertEquals("file:https", env.get("GIT_ALLOW_PROTOCOL"));
        assertEquals("https://github.com/acme/api.git",
                Git.run(repo, env, 30, "ls-remote", "--get-url", "git@github.com:acme/api.git").strip());
        assertEquals("https://github.com/acme/api.git",
                Git.run(repo, env, 30, "ls-remote", "--get-url", "ssh://git@github.com/acme/api.git").strip());
        assertEquals("git@gitlab.com:acme/api.git",
                Git.run(repo, env, 30, "ls-remote", "--get-url", "git@gitlab.com:acme/api.git").strip(),
                "only the key's own host");
    }

    @Test
    void nothingIsWrittenIntoGitConfigOrRemotes() throws Exception {
        String before = git(repo, "config", "--list", "--local");
        Git.run(repo, GitAccess.forJuno("github.com", false, "secret-token"), 30, "fetch", "-q", "origin");
        assertEquals(before, git(repo, "config", "--list", "--local"));
        assertFalse(git(repo, "remote", "-v").contains("secret"));
    }

    @Test
    void aCredentialRequestNamesItsHostOnlyOverHttps() {
        assertEquals("github.com", GitAccess.parseHost("protocol=https\nhost=github.com\n"));
        assertEquals("", GitAccess.parseHost("protocol=http\nhost=github.com\n"));
        assertEquals("", GitAccess.parseHost("host=github.com\n"));
    }

    private Snapshots.Request request(Path folder, String source, String sha) {
        return new Snapshots.Request(repo, folder, source, "rev1", 7, sha, git1("HEAD"), "refs/pull/7/head", "main",
                GitAccess.forJuno("github.com", false, "t"));
    }

    private String git1(String rev) {
        try {
            return git(repo, "rev-parse", rev);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aLocalCommitNeedsNoFetchAndTheSnapshotIsDetached() throws Exception {
        Path folder = Snapshots.folder(dir.resolve("review"), "rev1", 7, head);
        Snapshots.create(request(folder, "no-such-remote", head));
        assertEquals("HEAD", git(folder, "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals(head, git(folder, "rev-parse", "HEAD"));
        assertEquals("main", git(repo, "rev-parse", "--abbrev-ref", "HEAD"), "the checkout is untouched");
        Snapshots.remove(folder, "rev1", 7);
        assertFalse(Files.exists(folder));
        assertFalse(git(repo, "worktree", "list").contains(folder.getFileName().toString()));
    }

    @Test
    void aPrHeadFromElsewhereIsFetchedUnderItsOwnRefAndGoesWithTheSnapshot() throws Exception {
        // Someone else's commit: only on the remote, under the PR's ref.
        Path other = dir.resolve("other");
        git(dir, "clone", "-q", origin.toString(), other.toString());
        git(other, "config", "user.name", "o");
        git(other, "config", "user.email", "o@example.com");
        Files.writeString(other.resolve("b.txt"), "two\n");
        git(other, "add", ".");
        git(other, "commit", "-m", "two");
        String sha = git(other, "rev-parse", "HEAD");
        git(other, "push", "-q", "origin", "HEAD:refs/pull/7/head");
        assertFalse(Snapshots.hasCommit(repo, sha));

        String source = Snapshots.source(repo, "github.com", "owner/repo");
        assertEquals("origin", source);
        Path folder = Snapshots.folder(dir.resolve("review"), "rev1", 7, sha);
        String branchesBefore = git(repo, "branch", "--list");
        Snapshots.create(request(folder, source, sha));
        assertEquals(sha, git(folder, "rev-parse", "HEAD"));
        assertEquals(sha, git(repo, "rev-parse", "refs/juno/review/rev1/7"));
        assertEquals(branchesBefore, git(repo, "branch", "--list"), "never the user's branches");

        Snapshots.remove(folder, "rev1", 7);
        assertThrows(IOException.class, () -> git(repo, "rev-parse", "--verify", "--quiet", "refs/juno/review/rev1/7"));
    }

    @Test
    void aRemoteThatIsntTheRepositoryIsntUsed() {
        assertEquals("https://github.com/acme/other.git", Snapshots.source(repo, "github.com", "acme/other"));
    }

    @Test
    void aSnapshotsNameComesBackApart() {
        assertEquals("rev1", Snapshots.parseName("rev1-7-abcdef0123")[0]);
        assertEquals("7", Snapshots.parseName("rev1-7-abcdef0123")[1]);
        assertEquals(null, Snapshots.parseName("something-else"));
    }
}
