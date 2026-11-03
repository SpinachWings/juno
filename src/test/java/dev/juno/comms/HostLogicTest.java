package dev.juno.comms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.juno.comms.HostActions.Action;
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

/** What the proxy lets through (design §6.6), the PRs tab's entries (§7.11) and the key Test's REST answers (§5.1). */
class HostLogicTest {
    private static JsonNode json(String s) throws IOException {
        return Json.MAPPER.readTree(s);
    }

    private static Action github(String tool, String args) throws IOException {
        return HostActions.classify(Key.Kind.GITHUB, tool, json(args));
    }

    private static Action gitlab(String tool, String args) throws IOException {
        return HostActions.classify(Key.Kind.GITLAB, tool, json(args));
    }

    @Test
    void githubReadsOpensAndRepliesOnly() throws Exception {
        assertEquals(Action.READ, github("pull_request_read", "{\"method\":\"get_diff\"}"));
        assertEquals(Action.READ, github("issue_read", "{\"method\":\"get\"}"));
        assertEquals(Action.READ, github("list_issues", "{}"));
        assertEquals(Action.READ, github("get_file_contents", "{}"));
        assertEquals(Action.OPEN, github("create_pull_request", "{}"));
        assertEquals(Action.REPLY, github("add_reply_to_pull_request_comment", "{}"));
    }

    @Test
    void everythingElseIsRefusedWhateverTheProfileSays() throws Exception {
        assertNull(github("merge_pull_request", "{}"));
        assertNull(github("update_pull_request", "{\"state\":\"closed\"}"));
        assertNull(github("update_pull_request", "{\"title\":\"x\"}"));
        assertNull(github("pull_request_review_write", "{\"method\":\"create\",\"event\":\"APPROVE\"}"));
        assertNull(github("pull_request_review_write", "{\"method\":\"resolve_thread\",\"threadId\":\"x\"}"));
        assertNull(github("add_comment_to_pending_review", "{}"));
        assertNull(github("add_issue_comment", "{}"));
        assertNull(github("create_branch", "{}"));
        assertNull(github("push_files", "{}"));
        assertNull(github("some_new_tool", "{}"));
        assertNull(gitlab("accept_merge_request", "{}"));
        assertNull(gitlab("save_merge_request", "{\"merge_request_iid\":3,\"state_event\":\"close\"}"));
        assertNull(gitlab("save_merge_request", "{\"merge_request_iid\":3,\"title\":\"x\"}"));
        assertNull(gitlab("save_note", "{}"));
        assertNull(gitlab("save_merge_request_review", "{\"method\":\"approve\"}"));
        assertNull(gitlab("save_merge_request_review", "{\"method\":\"resolve_discussion\"}"));
        assertNull(gitlab("create_workflow", "{}"));
    }

    @Test
    void gitlabReadsOpensAndRepliesOnly() throws Exception {
        assertEquals(Action.READ, gitlab("get_merge_request", "{\"include\":\"diffs\"}"));
        assertEquals(Action.READ, gitlab("get_issue", "{}"));
        assertEquals(Action.OPEN, gitlab("save_merge_request", "{\"title\":\"x\"}"));
        assertEquals(Action.OPEN, gitlab("create_merge_request", "{}"));
        assertEquals(Action.REPLY, gitlab("save_note", "{\"discussion_id\":\"d\"}"));
        assertEquals(Action.REPLY, gitlab("save_merge_request_review", "{\"method\":\"reply_discussion\"}"));
    }

    @Test
    void onlyToolsThatCanDoAllowedThingsAreListed() {
        List<String> readOnly = List.of("host:read");
        assertTrue(HostActions.listable(Key.Kind.GITHUB, "issue_read", readOnly, List.of()));
        assertFalse(HostActions.listable(Key.Kind.GITHUB, "create_pull_request", readOnly, List.of()));
        assertFalse(HostActions.listable(Key.Kind.GITHUB, "merge_pull_request", List.of(HostActions.ANY), List.of()));
        assertTrue(HostActions.listable(Key.Kind.GITLAB, "save_merge_request", List.of("host:open"), List.of()));
        assertFalse(HostActions.listable(Key.Kind.GITHUB, "pull_request_review_write", List.of(HostActions.ANY), List.of()));
    }

    @Test
    void disallowedRulesWinAndNoRuleMeansNo() {
        List<String> allowed = List.of("host:read", "host:reply");
        assertTrue(HostActions.allowed(Action.READ, allowed, List.of()));
        assertFalse(HostActions.allowed(Action.READ, allowed, List.of("host:read")));
        assertFalse(HostActions.allowed(Action.OPEN, allowed, List.of()));
        assertTrue(HostActions.allowed(Action.OPEN, List.of(HostActions.ANY), List.of()));
        assertFalse(HostActions.allowed(Action.OPEN, List.of(HostActions.ANY), List.of("host:open")));
        assertFalse(HostActions.anyAllowed(List.of("Read")));
        assertEquals("host:reply", Action.REPLY.rule());
    }

    @Test
    void onlyRepliesCarryTheMarker() {
        assertEquals("body", HostActions.commentField(Key.Kind.GITHUB, "add_reply_to_pull_request_comment", Action.REPLY));
        assertEquals("note", HostActions.commentField(Key.Kind.GITLAB, "save_merge_request_review", Action.REPLY));
        assertEquals("body", HostActions.commentField(Key.Kind.GITLAB, "save_note", Action.REPLY));
        assertNull(HostActions.commentField(Key.Kind.GITHUB, "create_pull_request", Action.OPEN));
        assertEquals("base", HostActions.baseField(Key.Kind.GITHUB));
        assertEquals("target_branch", HostActions.baseField(Key.Kind.GITLAB));
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

    // ---------------------------------------------------------------- the key Test (REST)

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
    void theTestNamesWhatsMissing() throws Exception {
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
            HostApi.TestLine comment = lines.stream().filter(l -> l.what().startsWith("Comment")).findFirst().orElseThrow();
            assertTrue(comment.ok(), "a 404 means the write was allowed; nothing was there to write to");
            assertTrue(lines.stream().anyMatch(l -> l.what().startsWith("Read issues") && Boolean.TRUE.equals(l.ok())));
            assertTrue(lines.stream().noneMatch(l -> l.detail().contains("secret")));
        } finally {
            h.stop(0);
        }
    }

    @Test
    void aBadTokenStopsTheTest() throws Exception {
        HttpServer h = server(Map.of("/user", "{\"message\":\"Bad credentials\"}"), Map.of("/user", 401));
        try {
            List<HostApi.TestLine> lines = new GitHubApi(base(h), "t").test("o/r");
            assertEquals(1, lines.size());
            assertFalse(lines.get(0).ok());
        } finally {
            h.stop(0);
        }
    }
}
