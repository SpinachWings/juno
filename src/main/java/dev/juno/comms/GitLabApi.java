package dev.juno.comms;

import dev.juno.git.GitAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the Test button can check of a GitLab key's git token (design §5.1). The token only needs {@code read_repository}
 * and {@code write_repository}, which reach the repository over git, not the REST API, so the check is a {@code git
 * ls-remote}: the token is in that one git child's environment and nowhere else. Not run against a real GitLab yet.
 */
public final class GitLabApi implements HostApi {
    private final String host;
    private final String token;

    public GitLabApi(String host, String token) {
        this.host = host;
        this.token = token;
    }

    @Override
    public List<TestLine> test(String repo) {
        List<TestLine> out = new ArrayList<>();
        if (repo == null || repo.isBlank()) {
            out.add(new TestLine("Git token", null, "name a project to check it (read_repository, write_repository)"));
            return out;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("juno-key-test");
            String url = "https://" + host + "/" + repo.strip() + ".git";
            Map<String, String> env = GitAccess.forToken(host, true, token);
            dev.juno.git.Git.run(dir, env, 30, "ls-remote", "--heads", url);
            out.add(new TestLine("Read the project over git (read_repository)", true, ""));
            out.add(new TestLine("Push (write_repository)", null, "can't be tried without pushing; checked at the first push"));
        } catch (IOException e) {
            String why = e.getMessage() == null ? "" : e.getMessage().replace(token, "***");
            out.add(new TestLine("Read the project over git (read_repository)", false, why));
        } finally {
            if (dir != null) {
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // a temporary folder
                }
            }
        }
        return out;
    }
}
