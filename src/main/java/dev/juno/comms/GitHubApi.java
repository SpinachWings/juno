package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.store.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GitHub's REST and GraphQL APIs (design §6.6, findings in docs/progress.md, Milestone 5a). Check runs, legacy statuses
 * and workflow runs together make up what's on a commit: {@code get_status} alone stays "pending" even when Actions
 * checks passed, and one workflow can have several runs and check runs of the same name.
 */
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

    @Override public String label(int number) { return "#" + number; }

    @Override public String headRef(int number) { return "refs/pull/" + number + "/head"; }

    @Override
    public PullRequest pullRequest(String repo, int number) throws IOException {
        return parse(rest.get("/repos/" + repo + "/pulls/" + number));
    }

    private static PullRequest parse(JsonNode p) {
        String state = p.path("merged").asBoolean(false) ? "merged" : p.path("state").asText("open");
        return new PullRequest(p.path("number").asInt(), state, p.path("head").path("sha").asText(),
                p.path("base").path("sha").asText(), p.path("base").path("ref").asText(),
                p.path("head").path("ref").asText());
    }

    @Override
    public Optional<PullRequest> openFor(String repo, String branch) throws IOException {
        String owner = repo.substring(0, repo.indexOf('/'));
        JsonNode list = rest.get("/repos/" + repo + "/pulls?state=open&head=" + enc(owner + ":" + branch));
        return list.isEmpty() ? Optional.empty() : Optional.of(parse(list.get(0)));
    }

    @Override
    public Checks checks(String repo, PullRequest pr) throws IOException {
        Set<String> required = requiredNames(repo, pr);
        List<Check> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        IOException problem = null;
        int answered = 0;
        try {
            for (JsonNode c : rest.get("/repos/" + repo + "/commits/" + pr.headSha() + "/check-runs?per_page=100")
                    .path("check_runs")) {
                String name = c.path("name").asText();
                seen.add(name);
                out.add(new Check(name, "", runState(c.path("status").asText(), c.path("conclusion").asText("")),
                        required.contains(name), false));
            }
            answered++;
        } catch (HostException e) {
            problem = e; // a fine-grained token may not read them on a private repository: runs still tell
        }
        try {
            for (JsonNode s : rest.get("/repos/" + repo + "/commits/" + pr.headSha() + "/status").path("statuses")) {
                String name = s.path("context").asText();
                seen.add(name);
                out.add(new Check(name, "", statusState(s.path("state").asText()), required.contains(name), false));
            }
            answered++;
        } catch (HostException e) {
            problem = e;
        }
        boolean workflows = false;
        try {
            for (JsonNode r : rest.get("/repos/" + repo + "/actions/runs?per_page=100&head_sha=" + pr.headSha())
                    .path("workflow_runs")) {
                out.add(new Check(r.path("name").asText(), r.path("event").asText(""),
                        runState(r.path("status").asText(), r.path("conclusion").asText("")), false, true));
            }
            answered++;
            workflows = rest.get("/repos/" + repo + "/actions/workflows?per_page=1").path("total_count").asInt() > 0;
        } catch (HostException e) {
            problem = e;
        }
        if (answered == 0) throw problem;
        List<String> missing = required.stream().filter(n -> !seen.contains(n)).sorted().toList();
        return new Checks(out, missing, workflows);
    }

    /** Required checks: GraphQL on what has reported, rulesets for what hasn't yet. Neither readable = none known. */
    private Set<String> requiredNames(String repo, PullRequest pr) {
        Set<String> out = new HashSet<>();
        try {
            ObjectNode body = Json.MAPPER.createObjectNode();
            body.put("query", """
                    query($o:String!,$n:String!,$pr:Int!){repository(owner:$o,name:$n){pullRequest(number:$pr){
                    commits(last:1){nodes{commit{statusCheckRollup{contexts(first:100){nodes{__typename
                    ... on CheckRun{name isRequired(pullRequestNumber:$pr)}
                    ... on StatusContext{context isRequired(pullRequestNumber:$pr)}}}}}}}}}}""");
            ObjectNode vars = body.putObject("variables");
            vars.put("o", repo.substring(0, repo.indexOf('/')));
            vars.put("n", repo.substring(repo.indexOf('/') + 1));
            vars.put("pr", pr.number());
            JsonNode nodes = rest.post("/graphql", body.toString()).at("/data/repository/pullRequest/commits/nodes/0"
                    + "/commit/statusCheckRollup/contexts/nodes");
            for (JsonNode n : nodes) {
                if (n.path("isRequired").asBoolean(false)) out.add(n.has("name") ? n.path("name").asText()
                        : n.path("context").asText());
            }
        } catch (IOException e) {
            // unknown: every check counts
        }
        try {
            for (JsonNode rule : rest.get("/repos/" + repo + "/rules/branches/" + enc(pr.baseRef()))) {
                if (!rule.path("type").asText().equals("required_status_checks")) continue;
                for (JsonNode c : rule.at("/parameters/required_status_checks")) out.add(c.path("context").asText());
            }
        } catch (IOException e) {
            // unknown
        }
        return out;
    }

    static CheckState runState(String status, String conclusion) {
        if (!status.equals("completed")) return status.equals("waiting") ? CheckState.NEEDS_PERSON : CheckState.PENDING;
        return conclusionState(conclusion);
    }

    private static CheckState conclusionState(String conclusion) {
        return switch (conclusion.toLowerCase(Locale.ROOT)) {
            case "success", "skipped", "neutral" -> CheckState.PASSED;
            case "action_required" -> CheckState.NEEDS_PERSON;
            default -> CheckState.FAILED;
        };
    }

    private static CheckState statusState(String state) {
        return switch (state) {
            case "success" -> CheckState.PASSED;
            case "pending" -> CheckState.PENDING;
            default -> CheckState.FAILED;
        };
    }

    @Override
    public String threadStarter(String repo, int number, String threadId) throws IOException {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("query", "query($id:ID!){node(id:$id){... on PullRequestReviewThread{comments(first:1){nodes{body}}}}}");
        body.putObject("variables").put("id", threadId);
        return rest.post("/graphql", body.toString()).at("/data/node/comments/nodes/0/body").asText("");
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
        String sha = "";
        try {
            sha = rest.get("/repos/" + repo + "/commits/" + enc(meta.path("default_branch").asText("main"))).path("sha").asText();
        } catch (IOException e) {
            out.add(failed("Read commits (Contents: read)", e));
        }
        out.add(read("Read pull requests (Pull requests: read)", "/repos/" + repo + "/pulls?per_page=1"));
        out.add(read("Read workflow runs (Actions: read)", "/repos/" + repo + "/actions/runs?per_page=1"));
        if (!sha.isEmpty()) {
            out.add(read("Read check runs", "/repos/" + repo + "/commits/" + sha + "/check-runs?per_page=1"));
            out.add(read("Read statuses (Commit statuses: read)", "/repos/" + repo + "/commits/" + sha + "/status"));
        }
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
