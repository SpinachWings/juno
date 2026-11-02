package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GitLab's REST API with the key's git and REST token (design §5.1, §6.6). From its documentation; not run against a
 * real GitLab yet (docs/progress.md). A job that may fail ({@code allow_failure}) is optional, every other job required.
 */
public final class GitLabApi implements HostApi {
    private final Rest rest;

    /** {@code base}: {@code https://<host>/api/v4}. */
    public GitLabApi(String base, String token) {
        this.rest = new Rest(base, Map.of("PRIVATE-TOKEN", token, "User-Agent", "juno"));
    }

    public static String apiBase(String host) {
        return "https://" + host + "/api/v4";
    }

    @Override public String label(int number) { return "!" + number; }

    @Override public String headRef(int number) { return "refs/merge-requests/" + number + "/head"; }

    private static String project(String repo) {
        return "/projects/" + enc(repo);
    }

    @Override
    public PullRequest pullRequest(String repo, int number) throws IOException {
        return parse(rest.get(project(repo) + "/merge_requests/" + number));
    }

    private static PullRequest parse(JsonNode m) {
        String state = switch (m.path("state").asText("opened")) {
            case "opened" -> "open";
            case "merged" -> "merged";
            default -> "closed";
        };
        return new PullRequest(m.path("iid").asInt(), state, m.path("sha").asText(),
                m.at("/diff_refs/base_sha").asText(""), m.path("target_branch").asText(), m.path("source_branch").asText());
    }

    @Override
    public Optional<PullRequest> openFor(String repo, String branch) throws IOException {
        JsonNode list = rest.get(project(repo) + "/merge_requests?state=opened&source_branch=" + enc(branch));
        return list.isEmpty() ? Optional.empty() : Optional.of(parse(list.get(0)));
    }

    @Override
    public Checks checks(String repo, PullRequest pr) throws IOException {
        List<Check> out = new ArrayList<>();
        JsonNode pipelines = rest.get(project(repo) + "/merge_requests/" + pr.number() + "/pipelines?per_page=20");
        for (JsonNode p : pipelines) {
            if (!p.path("sha").asText().equals(pr.headSha())) continue;
            // The newest pipeline on the head commit is the one that counts; retried jobs are left out by default.
            for (JsonNode j : rest.get(project(repo) + "/pipelines/" + p.path("id").asInt() + "/jobs?per_page=100")) {
                boolean optional = j.path("allow_failure").asBoolean(false);
                CheckState state = jobState(j.path("status").asText(), optional);
                if (state != null) out.add(new Check(j.path("name").asText(), "", state, !optional, false));
            }
            break;
        }
        return new Checks(out, List.of(), false);
    }

    /** Null: a manual job nobody has to run. */
    static CheckState jobState(String status, boolean optional) {
        return switch (status) {
            case "success", "skipped" -> CheckState.PASSED;
            case "failed", "canceled", "canceling" -> CheckState.FAILED;
            case "manual" -> optional ? null : CheckState.NEEDS_PERSON;
            default -> CheckState.PENDING;
        };
    }

    @Override
    public String threadStarter(String repo, int number, String threadId) throws IOException {
        return rest.get(project(repo) + "/merge_requests/" + number + "/discussions/" + enc(threadId))
                .at("/notes/0/body").asText("");
    }

    @Override
    public List<TestLine> test(String repo) {
        List<TestLine> out = new ArrayList<>();
        try {
            out.add(new TestLine("The git and REST token works", true, "signed in as "
                    + rest.get("/user").path("username").asText("?")));
        } catch (IOException e) {
            out.add(new TestLine("The git and REST token works", false, e.getMessage()));
            return out;
        }
        try {
            List<String> scopes = new ArrayList<>();
            rest.get("/personal_access_tokens/self").path("scopes").forEach(s -> scopes.add(s.asText()));
            if (!scopes.isEmpty()) {
                out.add(new TestLine("Scope api (read pipelines, MRs)", scopes.contains("api"), String.join(", ", scopes)));
                out.add(new TestLine("Scope read_repository (fetch)", scopes.contains("read_repository")
                        || scopes.contains("api"), ""));
                out.add(new TestLine("Scope write_repository (push; not needed by a reviewer)",
                        scopes.contains("write_repository"), ""));
            }
        } catch (IOException e) {
            out.add(new TestLine("Token scopes", null, "project and group tokens may not show them"));
        }
        if (repo == null || repo.isBlank()) {
            out.add(new TestLine("Project permissions", null, "name a project to check them"));
            return out;
        }
        JsonNode meta;
        try {
            meta = rest.get(project(repo));
            out.add(new TestLine("Read the project", true, ""));
        } catch (IOException e) {
            out.add(new TestLine("Read the project", false, e.getMessage()));
            return out;
        }
        out.add(read("Read merge requests", project(repo) + "/merge_requests?per_page=1"));
        out.add(read("Read pipelines", project(repo) + "/pipelines?per_page=1"));
        int access = Math.max(meta.at("/permissions/project_access/access_level").asInt(0),
                meta.at("/permissions/group_access/access_level").asInt(0));
        if (access > 0) out.add(new TestLine("Comment (Reporter or above)", access >= 20, "access level " + access));
        return out;
    }

    private TestLine read(String what, String path) {
        try {
            rest.get(path);
            return new TestLine(what, true, "");
        } catch (IOException e) {
            return new TestLine(what, false, e.getMessage());
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
