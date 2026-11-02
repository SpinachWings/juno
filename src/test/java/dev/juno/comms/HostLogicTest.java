package dev.juno.comms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.juno.comms.HostActions.Action;
import dev.juno.comms.HostApi.Check;
import dev.juno.comms.HostApi.CheckState;
import dev.juno.comms.HostApi.Checks;
import dev.juno.comms.Pipelines.Result;
import dev.juno.model.Key;
import dev.juno.store.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the proxy lets through (design §6.6), what checks mean (§6.3), and GitHub's REST answers (§6.6). */
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
    void githubCallsAreToldApartByTheirArguments() throws Exception {
        assertEquals(Action.READ, github("pull_request_read", "{\"method\":\"get_diff\"}"));
        assertEquals(Action.READ, github("get_file_contents", "{}"));
        assertEquals(Action.READ, github("actions_list", "{}"));
        assertEquals(Action.OPEN, github("create_pull_request", "{}"));
        assertEquals(Action.REPLY, github("add_reply_to_pull_request_comment", "{}"));
        assertEquals(Action.REVIEW, github("pull_request_review_write", "{\"method\":\"create\",\"commitID\":\"a\"}"));
        assertEquals(Action.REVIEW, github("add_comment_to_pending_review", "{}"));
        assertEquals(Action.REVIEW, github("pull_request_review_write", "{\"method\":\"submit_pending\",\"event\":\"COMMENT\"}"));
        assertEquals(Action.APPROVE, github("pull_request_review_write", "{\"method\":\"create\",\"event\":\"APPROVE\"}"));
        assertEquals(Action.APPROVE, github("pull_request_review_write", "{\"method\":\"submit_pending\",\"event\":\"approve\"}"));
        assertEquals(Action.REQUEST_CHANGES, github("pull_request_review_write", "{\"method\":\"submit_pending\",\"event\":\"REQUEST_CHANGES\"}"));
        assertEquals(Action.RESOLVE, github("pull_request_review_write", "{\"method\":\"resolve_thread\",\"threadId\":\"x\"}"));
        assertEquals(Action.MERGE, github("merge_pull_request", "{}"));
        assertEquals(Action.CLOSE, github("update_pull_request", "{\"state\":\"closed\"}"));
        assertEquals(Action.EDIT, github("update_pull_request", "{\"title\":\"x\"}"));
    }

    @Test
    void anythingUnknownIsRefused() throws Exception {
        assertNull(github("create_branch", "{}"));
        assertNull(github("push_files", "{}"));
        assertNull(github("some_new_tool", "{}"));
        assertNull(github("pull_request_review_write", "{\"method\":\"something_new\"}"));
        assertNull(github("pull_request_review_write", "{\"method\":\"create\",\"event\":\"SOMETHING\"}"));
        assertNull(gitlab("create_workflow", "{}"));
        assertNull(gitlab("save_merge_request_review", "{\"method\":\"something_new\"}"));
    }

    @Test
    void gitlabCallsAreToldApartByTheirArguments() throws Exception {
        assertEquals(Action.READ, gitlab("get_merge_request", "{\"include\":\"diffs\"}"));
        assertEquals(Action.OPEN, gitlab("save_merge_request", "{\"title\":\"x\"}"));
        assertEquals(Action.CLOSE, gitlab("save_merge_request", "{\"merge_request_iid\":3,\"state_event\":\"close\"}"));
        assertEquals(Action.EDIT, gitlab("save_merge_request", "{\"merge_request_iid\":3,\"title\":\"x\"}"));
        assertEquals(Action.MERGE, gitlab("accept_merge_request", "{}"));
        assertEquals(Action.REPLY, gitlab("save_note", "{\"discussion_id\":\"d\"}"));
        assertEquals(Action.REVIEW, gitlab("save_merge_request_review", "{\"method\":\"create_diff_note\",\"sha\":\"a\"}"));
        assertEquals(Action.RESOLVE, gitlab("save_merge_request_review", "{\"method\":\"resolve_discussion\"}"));
        assertEquals(Action.APPROVE, gitlab("save_merge_request_review", "{\"method\":\"approve\"}"));
        assertEquals(Action.REQUEST_CHANGES, gitlab("save_merge_request_review", "{\"method\":\"submit_review\",\"verdict\":\"requested_changes\"}"));
    }

    @Test
    void disallowedRulesWinAndNoRuleMeansNo() {
        List<String> allowed = List.of("host:read", "host:reply");
        assertTrue(HostActions.allowed(Action.READ, allowed, List.of()));
        assertFalse(HostActions.allowed(Action.READ, allowed, List.of("host:read")));
        assertFalse(HostActions.allowed(Action.MERGE, allowed, List.of()));
        assertTrue(HostActions.allowed(Action.MERGE, List.of(HostActions.ANY), List.of()));
        assertFalse(HostActions.allowed(Action.MERGE, List.of(HostActions.ANY), List.of("host:merge")));
        assertFalse(HostActions.anyAllowed(List.of("Read")));
        assertEquals("host:request-changes", Action.REQUEST_CHANGES.rule());
    }

    private static Check c(String name, CheckState state) {
        return new Check(name, "", state, false, false);
    }

    private static Check required(String name, CheckState state) {
        return new Check(name, "", state, true, false);
    }

    @Test
    void noChecksIsNoneUnlessTheRepositoryHasPipelines() {
        assertEquals(Result.NONE, Pipelines.evaluate(new Checks(List.of(), List.of(), false)).result());
        assertEquals(Result.NOT_STARTED, Pipelines.evaluate(new Checks(List.of(), List.of(), true)).result());
    }

    @Test
    void everyCheckCountsWhenNoneIsRequired() {
        assertEquals(Result.PASSED, Pipelines.evaluate(new Checks(List.of(c("a", CheckState.PASSED)), List.of(), true)).result());
        Pipelines.Outcome failed = Pipelines.evaluate(new Checks(List.of(c("a", CheckState.PASSED), c("b", CheckState.FAILED)), List.of(), true));
        assertEquals(Result.FAILED, failed.result());
        assertEquals(List.of("b"), failed.names());
        assertEquals(Result.PENDING, Pipelines.evaluate(new Checks(List.of(c("a", CheckState.PASSED), c("b", CheckState.PENDING)), List.of(), true)).result());
    }

    @Test
    void twoRunsWithOneNameBothCount() {
        Check push = new Check("CI", "push", CheckState.PASSED, false, true);
        Check pr = new Check("CI", "pull_request", CheckState.PENDING, false, true);
        assertEquals(Result.PENDING, Pipelines.evaluate(new Checks(List.of(push, pr), List.of(), true)).result());
    }

    @Test
    void onlyRequiredChecksBlockAndFailedOptionalOnesAreListed() {
        Pipelines.Outcome o = Pipelines.evaluate(new Checks(List.of(required("build", CheckState.PASSED),
                c("lint", CheckState.FAILED), c("slow", CheckState.PENDING)), List.of(), true));
        assertEquals(Result.PASSED, o.result());
        assertEquals(List.of("lint"), o.optionalFailed());
        assertEquals(Result.FAILED, Pipelines.evaluate(new Checks(List.of(required("build", CheckState.FAILED)), List.of(), true)).result());
    }

    @Test
    void aRequiredCheckWithNoResultYetKeepsItWaiting() {
        assertEquals(Result.PENDING, Pipelines.evaluate(new Checks(List.of(required("a", CheckState.PASSED)), List.of("b"), true)).result());
        assertEquals(Result.PENDING, Pipelines.evaluate(new Checks(List.of(), List.of("b"), true)).result());
    }

    @Test
    void aPersonIsAskedAtOnceWhateverElseIsGoingOn() {
        Pipelines.Outcome o = Pipelines.evaluate(new Checks(List.of(c("a", CheckState.PENDING), c("b", CheckState.FAILED),
                c("deploy", CheckState.NEEDS_PERSON)), List.of(), true));
        assertEquals(Result.NEEDS_PERSON, o.result());
        assertEquals(List.of("deploy"), o.names());
    }

    @Test
    void gitlabJobStates() {
        assertEquals(CheckState.PASSED, GitLabApi.jobState("skipped", false));
        assertEquals(CheckState.FAILED, GitLabApi.jobState("canceled", false));
        assertEquals(CheckState.NEEDS_PERSON, GitLabApi.jobState("manual", false));
        assertNull(GitLabApi.jobState("manual", true));
        assertEquals(CheckState.PENDING, GitLabApi.jobState("waiting_for_resource", false));
    }

    /** A stand-in for api.github.com with fixed answers by path. */
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

    private static final HostApi.PullRequest PR = new HostApi.PullRequest(7, "open", "abc", "def", "main", "feature");

    @Test
    void githubChecksComeFromRunsStatusesAndGraphql() throws Exception {
        Map<String, String> a = new HashMap<>();
        a.put("/repos/o/r/commits/abc/check-runs", "{\"check_runs\":[{\"name\":\"hello\",\"status\":\"completed\",\"conclusion\":\"success\"},"
                + "{\"name\":\"hello\",\"status\":\"in_progress\"},{\"name\":\"lint\",\"status\":\"completed\",\"conclusion\":\"failure\"}]}");
        a.put("/repos/o/r/commits/abc/status", "{\"state\":\"pending\",\"total_count\":0,\"statuses\":[]}");
        a.put("/repos/o/r/actions/runs", "{\"workflow_runs\":[{\"name\":\"CI\",\"event\":\"push\",\"status\":\"completed\",\"conclusion\":\"action_required\"}]}");
        a.put("/repos/o/r/actions/workflows", "{\"total_count\":1}");
        a.put("/graphql", "{\"data\":{\"repository\":{\"pullRequest\":{\"commits\":{\"nodes\":[{\"commit\":{\"statusCheckRollup\":{\"contexts\":{\"nodes\":"
                + "[{\"name\":\"hello\",\"isRequired\":true},{\"name\":\"lint\",\"isRequired\":false}]}}}}]}}}}}");
        a.put("/repos/o/r/rules/branches/main", "[{\"type\":\"required_status_checks\",\"parameters\":{\"required_status_checks\":[{\"context\":\"later\"}]}}]");
        HttpServer h = server(a, Map.of());
        try {
            Checks c = new GitHubApi(base(h), "t").checks("o/r", PR);
            assertTrue(c.configured());
            assertEquals(List.of("later"), c.missingRequired());
            assertEquals(4, c.checks().size());
            assertTrue(c.checks().get(0).required());
            assertEquals(CheckState.PENDING, c.checks().get(1).state());
            assertFalse(c.checks().get(2).required());
            assertEquals(CheckState.NEEDS_PERSON, c.checks().get(3).state());
            assertEquals("push", c.checks().get(3).event());
        } finally {
            h.stop(0);
        }
    }

    @Test
    void aZeroCountPendingStatusIsNoPipelines() throws Exception {
        Map<String, String> a = new HashMap<>();
        a.put("/repos/o/r/commits/abc/check-runs", "{\"total_count\":0,\"check_runs\":[]}");
        a.put("/repos/o/r/commits/abc/status", "{\"state\":\"pending\",\"total_count\":0,\"statuses\":[]}");
        a.put("/repos/o/r/actions/runs", "{\"workflow_runs\":[]}");
        a.put("/repos/o/r/actions/workflows", "{\"total_count\":0}");
        HttpServer h = server(a, Map.of());
        try {
            assertEquals(Result.NONE, Pipelines.evaluate(new GitHubApi(base(h), "t").checks("o/r", PR)).result());
        } finally {
            h.stop(0);
        }
    }

    @Test
    void theTestNamesWhatsMissing() throws Exception {
        Map<String, String> a = new HashMap<>();
        a.put("/user", "{\"login\":\"me\"}");
        a.put("/repos/o/r", "{\"private\":true,\"default_branch\":\"main\"}");
        a.put("/repos/o/r/commits/main", "{\"sha\":\"abc\"}");
        a.put("/repos/o/r/pulls", "[]");
        a.put("/repos/o/r/actions/runs", "{}");
        a.put("/repos/o/r/commits/abc/check-runs", "{}");
        a.put("/repos/o/r/commits/abc/status", "{}");
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
