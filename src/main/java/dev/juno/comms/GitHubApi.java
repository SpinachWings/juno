package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** GitHub's REST API, for the key's Test button (design §5.1). */
public final class GitHubApi implements HostApi {
    private final Rest rest;

    /** {@code base}: {@code https://api.github.com}, or {@code https://api.<name>.ghe.com}. */
    public GitHubApi(String base, String token) {
        this.rest = new Rest(base, Map.of("Authorization", "Bearer " + token, "Accept", "application/vnd.github+json",
                "X-GitHub-Api-Version", "2022-11-28", "User-Agent", "juno"));
    }

    public static String apiBase(String host) {
        return host.equalsIgnoreCase("github.com") ? "https://api.github.com" : "https://api." + host;
    }

    @Override
    public List<TestLine> test(String repo) {
        List<TestLine> out = new ArrayList<>();
        try {
            out.add(new TestLine("The token works", true, "signed in as " + rest.get("/user").path("login").asText("?")));
        } catch (HostException e) {
            // A fine-grained token with no account permissions may still be refused /user with a 403; only 401 is fatal.
            if (e.status == 401) {
                out.add(new TestLine("The token works", false, e.getMessage()));
                return out;
            }
            out.add(new TestLine("The token works", null, "couldn't read the account: " + e.getMessage()));
        } catch (IOException e) {
            out.add(new TestLine("The token works", false, e.getMessage()));
            return out;
        }
        if (repo == null || repo.isBlank()) {
            out.add(new TestLine("Repository permissions", null, "name a repository to check them"));
            return out;
        }
        JsonNode meta;
        try {
            meta = rest.get("/repos/" + repo);
            out.add(new TestLine("Read the repository", true, meta.path("private").asBoolean() ? "private" : "public"));
        } catch (IOException e) {
            out.add(failed("Read the repository", e));
            return out;
        }
        try {
            rest.get("/repos/" + repo + "/commits/" + enc(meta.path("default_branch").asText("main")));
        } catch (IOException e) {
            out.add(failed("Read commits (Contents: read)", e));
        }
        out.add(read("Read pull requests (Pull requests: read)", "/repos/" + repo + "/pulls?per_page=1"));
        out.add(read("Read issues (Issues: read)", "/repos/" + repo + "/issues?per_page=1"));
        // Write checks that can't succeed: a refusal is about permission (403), anything else means the write was allowed.
        out.add(write("Push (Contents: write)", "/repos/" + repo + "/git/refs",
                "{\"ref\":\"refs/heads/juno-test-does-not-exist\",\"sha\":\"0000000000000000000000000000000000000000\"}"));
        out.add(write("Comment and open PRs (Pull requests: write)", "/repos/" + repo + "/pulls/0/reviews",
                "{\"event\":\"COMMENT\",\"body\":\"juno test\"}"));
        return out;
    }

    private TestLine read(String what, String path) {
        try {
            rest.get(path);
            return new TestLine(what, true, "");
        } catch (IOException e) {
            return failed(what, e);
        }
    }

    private TestLine write(String what, String path, String body) {
        try {
            rest.post(path, body);
            return new TestLine(what, true, "");
        } catch (HostException e) {
            return e.denied() ? failed(what, e) : new TestLine(what, true, "");
        } catch (IOException e) {
            return failed(what, e);
        }
    }

    private static TestLine failed(String what, IOException e) {
        String wanted = e instanceof HostException h && !h.wanted.isBlank() ? " (the host asks for " + h.wanted + ")" : "";
        return new TestLine(what, false, e.getMessage() + wanted);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
