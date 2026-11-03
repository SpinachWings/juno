package dev.juno.comms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.juno.comms.HostActions.Action;
import dev.juno.git.GitAccess;
import dev.juno.model.Agent;
import dev.juno.model.Key;
import dev.juno.model.PullRequestEntry;
import dev.juno.store.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The host shorthands and the thin proxy (design §6.3), the PRs tab's entries (§7.11) and the key Test (§5.1). */
class HostLogicTest {
    private static JsonNode json(String s) throws IOException {
        return Json.MAPPER.readTree(s);
    }

    @Test
    void hostRulesBecomeClaudeCodesOwnRulesForTheHostsTools() {
        List<String> rules = List.of("Read", "host:read", "host:reply");
        List<String> gh = HostActions.expand("github", Key.Kind.GITHUB, rules);
        assertTrue(gh.contains("mcp__github__issue_read"));
        assertTrue(gh.contains("mcp__github__pull_request_read"));
        assertTrue(gh.contains("mcp__github__add_reply_to_pull_request_comment"));
        assertFalse(gh.contains("mcp__github__create_pull_request"), "not opened without host:open");
        assertTrue(gh.stream().noneMatch(r -> r.contains("merge") || r.contains("update_pull_request")));
        List<String> gl = HostActions.expand("gitlab", Key.Kind.GITLAB, List.of("host:open", "host:read"));
        assertTrue(gl.contains("mcp__gitlab__create_merge_request"));
        assertTrue(gl.contains("mcp__gitlab__get_issue"));
        assertEquals(List.of("mcp__github"), HostActions.expand("github", Key.Kind.GITHUB, List.of(HostActions.ANY)));
        assertTrue(HostActions.expand("github", Key.Kind.GITHUB, List.of("Read", "host:merge")).isEmpty(), "unknown host rules do nothing");
    }

    @Test
    void aProfileGetsTheHostServersOnlyWithAHostRule() {
        assertTrue(HostActions.anyAllowed(List.of("Read", "host:read")));
        assertFalse(HostActions.anyAllowed(List.of("Read", "mcp__juno__report")));
        assertTrue(HostActions.isRule("host:open"));
        assertEquals("host:reply", Action.REPLY.rule());
    }

    @Test
    void openingAPrOrMrIsRecognisedForThePrsTab() throws Exception {
        assertTrue(HostActions.isOpen(Key.Kind.GITHUB, "create_pull_request", json("{}")));
        assertFalse(HostActions.isOpen(Key.Kind.GITHUB, "update_pull_request", json("{}")));
        assertTrue(HostActions.isOpen(Key.Kind.GITLAB, "create_merge_request", json("{}")));
        assertTrue(HostActions.isOpen(Key.Kind.GITLAB, "save_merge_request", json("{\"title\":\"x\"}")));
        assertFalse(HostActions.isOpen(Key.Kind.GITLAB, "save_merge_request", json("{\"merge_request_iid\":3}")));
        assertFalse(HostActions.isOpen(Key.Kind.GITHUB, "pull_request_read", json("{}")));
    }

    @Test
    void onlyRepliesCarryTheMarker() throws Exception {
        assertEquals("body", HostActions.commentField(Key.Kind.GITHUB, "add_reply_to_pull_request_comment", json("{}")));
        assertEquals("body", HostActions.commentField(Key.Kind.GITLAB, "save_note", json("{}")));
        assertEquals("note", HostActions.commentField(Key.Kind.GITLAB, "save_merge_request_review",
                json("{\"method\":\"reply_discussion\"}")));
        assertNull(HostActions.commentField(Key.Kind.GITLAB, "save_merge_request_review", json("{\"method\":\"approve\"}")));
        assertNull(HostActions.commentField(Key.Kind.GITHUB, "create_pull_request", json("{}")));
    }

    // ---------------------------------------------------------------- the PRs tab's entries

    private static Agent bob() {
        Agent a = new Agent();
        a.id = "a1";
        a.name = "bob";
        return a;
    }

    @Test
    void aGithubAnswerMakesAnEntryWithItsLink() throws Exception {
        JsonNode args = json("{\"owner\":\"acme\",\"repo\":\"api\",\"title\":\"Add login\",\"head\":\"feature/login\",\"base\":\"main\"}");
        JsonNode result = json("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"number\\\":42,\\\"html_url\\\":"
                + "\\\"https://github.com/acme/api/pull/42\\\",\\\"title\\\":\\\"Add login\\\"}\"}]}");
        PullRequestEntry e = PullRequestEntry.fromOpen(Key.Kind.GITHUB, args, result, "", bob());
        assertEquals("#42", e.number);
        assertEquals("https://github.com/acme/api/pull/42", e.url);
        assertEquals("acme/api", e.repo);
        assertEquals("feature/login", e.branch);
        assertEquals("Add login", e.title);
        assertEquals("bob", e.agentName);
    }

    @Test
    void aGitlabAnswerMakesAnEntryAndANumericProjectUsesTheProjectsRemote() throws Exception {
        JsonNode args = json("{\"project_id\":\"123\",\"title\":\"Fix\",\"source_branch\":\"fix/it\",\"target_branch\":\"main\"}");
        JsonNode result = json("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"iid\\\":7,\\\"web_url\\\":"
                + "\\\"https://gitlab.com/g/p/-/merge_requests/7\\\"}\"}]}");
        PullRequestEntry e = PullRequestEntry.fromOpen(Key.Kind.GITLAB, args, result, "g/p", bob());
        assertEquals("!7", e.number);
        assertEquals("https://gitlab.com/g/p/-/merge_requests/7", e.url);
        assertEquals("g/p", e.repo);
        assertEquals("fix/it", e.branch);
        assertEquals("Fix", e.title);
    }

    @Test
    void anAnswerWithoutJsonStillMakesAnEntryFromWhatWasAsked() throws Exception {
        JsonNode args = json("{\"owner\":\"acme\",\"repo\":\"api\",\"title\":\"T\",\"head\":\"b\"}");
        PullRequestEntry e = PullRequestEntry.fromOpen(Key.Kind.GITHUB, args, json("{\"content\":[{\"type\":\"text\",\"text\":\"created\"}]}"),
                "acme/api", bob());
        assertEquals("", e.url);
        assertEquals("", e.number);
        assertEquals("T", e.title);
        assertEquals("b", e.branch);
    }

    // ---------------------------------------------------------------- the key Test

    private static HttpServer server(Map<String, String> answers, Map<String, Integer> statuses) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            String key = ex.getRequestURI().getPath();
            byte[] body = answers.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
            ex.getRequestBody().readAllBytes();
            int status = statuses.getOrDefault(key, answers.containsKey(key) ? 200 : 404);
            if (status == 403) ex.getResponseHeaders().add("x-accepted-github-permissions", "contents=write");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        http.start();
        return http;
    }

    private static String base(HttpServer h) {
        return "http://127.0.0.1:" + h.getAddress().getPort();
    }

    @Test
    void theGithubTestNamesWhatsMissing() throws Exception {
        Map<String, String> a = new HashMap<>();
        a.put("/user", "{\"login\":\"me\"}");
        a.put("/repos/o/r", "{\"private\":true,\"default_branch\":\"main\"}");
        a.put("/repos/o/r/commits/main", "{\"sha\":\"abc\"}");
        a.put("/repos/o/r/pulls", "[]");
        a.put("/repos/o/r/issues", "[]");
        a.put("/repos/o/r/git/refs", "{\"message\":\"Resource not accessible by personal access token\"}");
        a.put("/repos/o/r/pulls/0/reviews", "{\"message\":\"Not Found\"}");
        HttpServer h = server(a, Map.of("/repos/o/r/git/refs", 403, "/repos/o/r/pulls/0/reviews", 404));
        try {
            List<HostApi.TestLine> lines = new GitHubApi(base(h), "secret").test("o/r");
            HostApi.TestLine push = lines.stream().filter(l -> l.what().startsWith("Push")).findFirst().orElseThrow();
            assertFalse(push.ok());
            assertTrue(push.detail().contains("contents=write"), push.detail());
            HostApi.TestLine comment = lines.stream().filter(l -> l.what().startsWith("Open PRs")).findFirst().orElseThrow();
            assertTrue(comment.ok(), "a 404 means the write was allowed; nothing was there to write to");
            assertTrue(lines.stream().anyMatch(l -> l.what().startsWith("Read issues") && Boolean.TRUE.equals(l.ok())));
            assertTrue(lines.stream().noneMatch(l -> l.detail().contains("secret")));
        } finally {
            h.stop(0);
        }
    }

    @Test
    void aBadGithubTokenStopsTheTest() throws Exception {
        HttpServer h = server(Map.of("/user", "{\"message\":\"Bad credentials\"}"), Map.of("/user", 401));
        try {
            List<HostApi.TestLine> lines = new GitHubApi(base(h), "t").test("o/r");
            assertEquals(1, lines.size());
            assertFalse(lines.get(0).ok());
        } finally {
            h.stop(0);
        }
    }

    @Test
    void theGitlabTestUsesGitOnlyAndNeverLeaksTheToken() {
        List<HostApi.TestLine> none = new GitLabApi("gitlab.example.invalid", "glpat-secret").test("");
        assertEquals(1, none.size());
        assertNull(none.get(0).ok(), "no project named: nothing to check");
        // An unreachable host: the read check fails, and the token is in no line.
        List<HostApi.TestLine> lines = new GitLabApi("127.0.0.1:1", "glpat-secret").test("g/p");
        assertEquals(Boolean.FALSE, lines.get(0).ok());
        assertTrue(lines.stream().noneMatch(l -> l.detail().contains("glpat-secret")));
        Map<String, String> env = GitAccess.forToken("gitlab.com", true, "glpat-secret");
        assertEquals("https", env.get("GIT_ALLOW_PROTOCOL"));
        assertTrue(env.get("GIT_CONFIG_VALUE_1").startsWith("Authorization: Basic "));
        assertFalse(env.get("GIT_CONFIG_VALUE_1").contains("glpat-secret"), "only base64 of user:token, in the child's environment");
    }
}
