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

/** The git environment (design §5.1.1), with real git and only local repositories. */
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
        Git.run(repo, GitAccess.forAgent(List.of("github.com"), "helper.sh"), 30, "ls-remote", "--get-url", "origin");
        assertEquals(before, git(repo, "config", "--list", "--local"));
        assertFalse(git(repo, "remote", "-v").contains("secret"));
    }

    @Test
    void aCredentialRequestNamesItsHostOnlyOverHttps() {
        assertEquals("github.com", GitAccess.parseHost("protocol=https\nhost=github.com\n"));
        assertEquals("", GitAccess.parseHost("protocol=http\nhost=github.com\n"));
        assertEquals("", GitAccess.parseHost("host=github.com\n"));
    }
}
