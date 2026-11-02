package dev.juno.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.juno.FakeProvider;
import dev.juno.comms.HostApi;
import dev.juno.comms.HostApi.Check;
import dev.juno.comms.HostApi.CheckState;
import dev.juno.git.Git;
import dev.juno.git.GitAccess;
import dev.juno.model.Agent;
import dev.juno.model.Key;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.model.Settings;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.Json;
import dev.juno.store.JunoHome;
import dev.juno.store.SettingsStore;
import dev.juno.store.TokenStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Keys on agents and reviews (design §5.1.1, §6) end to end through Juno's servers: {@code FakeClaude} stands in for
 * Claude Code, a fake REST client and a fake MCP server for the host, and git is real, on local repositories.
 */
class ReviewsTest {
    @TempDir Path dir;
    private AgentRuntime rt;
    private Settings settings;
    private FakeProvider provider;
    private JunoHome home;
    private Path repo, origin;
    private String head;
    private final FakeHost hostApi = new FakeHost();
    private HttpServer mcpServer;
    private final List<JsonNode> forwarded = new CopyOnWriteArrayList<>();
    private Key githubKey;

    /** The host's REST API as a reviewer's poll sees it. */
    private static final class FakeHost implements HostApi {
        volatile PullRequest pr;
        volatile Checks checks = new Checks(List.of(), List.of(), false);
        volatile String threadBody = "";
        volatile HostException refuse;
        volatile Optional<PullRequest> open = Optional.empty();

        @Override public String label(int n) { return "#" + n; }
        @Override public String headRef(int n) { return "refs/pull/" + n + "/head"; }
        @Override public PullRequest pullRequest(String repo, int number) throws IOException {
            if (refuse != null) throw refuse;
            return pr;
        }
        @Override public Optional<PullRequest> openFor(String repo, String branch) { return open; }
        @Override public Checks checks(String repo, PullRequest p) { return checks; }
        @Override public String threadStarter(String repo, int n, String thread) { return threadBody; }
        @Override public List<TestLine> test(String repo) { return List.of(); }
    }

    private static String git(Path where, String... args) throws IOException {
        return Git.run(where, args).strip();
    }

    @BeforeEach
    void setUp() throws Exception {
        origin = dir.resolve("origin/acme/api.git");
        Files.createDirectories(origin);
        git(origin, "init", "--bare", "-b", "main");
        repo = dir.resolve("checkout");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "dev");
        git(repo, "config", "user.name", "t");
        git(repo, "config", "user.email", "t@example.com");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git(repo, "add", ".");
        git(repo, "commit", "-m", "one");
        git(repo, "branch", "main");
        git(repo, "remote", "add", "origin", origin.toString());
        git(repo, "push", "-q", "origin", "main", "dev");
        head = git(repo, "rev-parse", "HEAD");
        hostApi.pr = new HostApi.PullRequest(7, "open", head, head, "main", "dev");

        mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpServer.createContext("/", ex -> {
            JsonNode m = Json.MAPPER.readTree(ex.getRequestBody().readAllBytes());
            ObjectNode r = Json.MAPPER.createObjectNode();
            r.put("jsonrpc", "2.0");
            if (m.has("id")) r.set("id", m.get("id"));
            ObjectNode result = r.putObject("result");
            switch (m.path("method").asText()) {
                case "tools/list" -> {
                    var tools = result.putArray("tools");
                    for (String t : List.of("pull_request_read", "create_pull_request", "pull_request_review_write",
                            "add_comment_to_pending_review", "add_reply_to_pull_request_comment", "merge_pull_request",
                            "update_pull_request", "create_branch")) {
                        tools.addObject().put("name", t).put("description", "d").putObject("inputSchema").put("type", "object");
                    }
                }
                case "tools/call" -> {
                    forwarded.add(m.path("params"));
                    result.putArray("content").addObject().put("type", "text").put("text", "done");
                }
                default -> { }
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            byte[] b = r.toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        mcpServer.start();
        runtime();
    }

    private void runtime() throws Exception {
        home = new JunoHome(dir.resolve("home"));
        SettingsStore store = new SettingsStore(home);
        settings = store.load();
        if (settings.projects.isEmpty()) {
            Project p = new Project();
            p.id = "p1";
            p.name = "api";
            p.folder = repo.toString();
            p.remote = "acme/api";
            settings.projects.add(p);
            githubKey = new Key();
            githubKey.id = "k1";
            githubKey.label = "bot";
            githubKey.kind = Key.Kind.GITHUB;
            githubKey.host = "github.com";
            githubKey.token = "env:THE_TOKEN";
            githubKey.gitName = "Bot";
            githubKey.gitEmail = "bot@example.com";
            githubKey.mcpUrlOverride = "http://127.0.0.1:" + mcpServer.getAddress().getPort() + "/mcp";
            settings.keys.add(githubKey);
            store.save(settings);
        }
        Files.createDirectories(dir.resolve("logs"));
        provider = new FakeProvider(new ClaudeCodeProvider(dir.resolve("claude"), home), dir.resolve("logs"));
        SkillLibrary lib = new SkillLibrary(home);
        TokenStore tokens = new TokenStore() {
            public boolean vaultAvailable() { return true; }
            public String vaultProblem() { return ""; }
            public String save(String id, String token) { return TokenStore.keyringReference(id); }
            public void delete(String id) {}
            public Optional<String> resolve(String r) { return Optional.of("secret-token"); }
        };
        rt = new AgentRuntime(home, provider, () -> settings, () -> store.save(settings), lib, new SkillResolver(lib), tokens);
        rt.host().apis = k -> hostApi;
        rt.start();
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (rt != null) rt.shutdown();
        if (mcpServer != null) mcpServer.stop(0);
        Thread.sleep(1_000); // Windows: ended processes and git commands let go of the temp folder a moment later
    }

    private static void waitFor(BooleanSupplier c, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + 30_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("Timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    private Agent reviewer(String name) {
        return rt.create(name, "reviewer", "p1", null, "k1", null);
    }

    private String log(Agent a) {
        try {
            return Files.exists(provider.log(a.id)) ? Files.readString(provider.log(a.id)) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void waitIdle(Agent a) throws InterruptedException {
        waitFor(() -> !a.turnOpen, a.name + " to end its turn");
    }

    /** Sends a message that makes the fake call {@code start_review}; returns once its turn is over. */
    private void startReview(Agent a, String target) throws InterruptedException {
        rt.send(a, "CALL start_review {\"target\":\"" + target + "\"}", "task");
        waitFor(() -> log(a).contains("start_review"), "the message");
        waitIdle(a);
    }

    private void poll() {
        rt.reviews().tick(System.currentTimeMillis() + 3_600_000L);
    }

    private JsonNode mcpConfig(Agent a) throws IOException {
        return Json.MAPPER.readTree(Files.readString(home.agentRunDir(a.id).resolve("mcp.json"))).path("mcpServers");
    }

    /** A call to the proxy as the agent would make it. */
    private JsonNode hostCall(Agent a, String method, String params) throws Exception {
        JsonNode server = mcpConfig(a).path("github");
        assertFalse(server.isMissingNode(), "no github server for " + a.name);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server.path("url").asText()))
                .header("Authorization", server.at("/headers/Authorization").asText()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return Json.MAPPER.readTree(r.body()).path("result");
    }

    private JsonNode tool(Agent a, String name, String args) throws Exception {
        return hostCall(a, "tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}");
    }

    private static boolean refused(JsonNode r) {
        return r.path("isError").asBoolean(false);
    }

    private static String text(JsonNode r) {
        return r.at("/content/0/text").asText();
    }

    private static HostApi.Checks checks(CheckState state) {
        return new HostApi.Checks(List.of(new Check("build", "", state, false, false)), List.of(), true);
    }

    private void waitReady(Agent a) throws InterruptedException {
        waitFor(() -> a.review != null && a.review.phase == Agent.Review.Phase.READY, "the snapshot");
        waitFor(() -> log(a).contains("Review now."), "the reviewer to be told");
        waitIdle(a);
    }

    // ---------------------------------------------------------------- the whole review

    @Test
    void aReviewWaitsForPipelinesThenWorksInTheSnapshotAndCleansUp() throws Exception {
        hostApi.checks = checks(CheckState.PENDING);
        Agent r = reviewer("rita");
        startReview(r, "#7");
        assertTrue(r.reviewOpen);
        assertEquals(Agent.Status.WAITING, r.status, "Waiting, and its turn is over");
        assertTrue(r.statusText().contains("pipelines on #7"), r.statusText());
        assertEquals(0, rt.workingCount(), "Waiting agents don't count");

        hostApi.checks = checks(CheckState.PASSED);
        poll();
        waitReady(r);
        String told = log(r);
        assertTrue(told.contains("[Pipelines passed on #7]"), told);
        assertTrue(told.contains(head), told);
        Path snapshot = Path.of(r.review.snapshot);
        assertEquals(snapshot, rt.folder(r), "the reviewer's folder is the snapshot");
        assertEquals("HEAD", git(snapshot, "rev-parse", "--abbrev-ref", "HEAD"));
        assertEquals(head, git(snapshot, "rev-parse", "HEAD"));
        assertTrue(snapshot.startsWith(home.reviewDir()), snapshot.toString());
        assertTrue(snapshot.getFileName().toString().startsWith(r.id + "-7-"));
        assertEquals(Agent.Status.DONE, r.status);
        assertTrue(rt.storage().stream().filter(s -> s.agent() == r).findFirst().orElseThrow().snapshots() > 0, "shown in Storage");

        rt.send(r, "CALL end_review {}", "user");
        waitFor(() -> r.review == null, "the end");
        waitIdle(r);
        assertFalse(r.reviewOpen);
        waitFor(() -> !Files.exists(snapshot), "the snapshot to go");
        assertNotEquals(snapshot, rt.folder(r));
        assertEquals("dev", git(repo, "rev-parse", "--abbrev-ref", "HEAD"));
    }

    @Test
    void noPipelinesReviewsStraightAwayAndSaysSo() throws Exception {
        Agent r = reviewer("rita");
        startReview(r, "#7");
        waitReady(r);
        assertTrue(log(r).contains("[No pipelines on #7]"));
    }

    @Test
    void aRepositoryWithPipelinesGetsAMomentBeforeNoneCounts() throws Exception {
        hostApi.checks = new HostApi.Checks(List.of(), List.of(), true);
        Agent r = reviewer("rita");
        startReview(r, "#7");
        poll();
        Thread.sleep(200);
        assertEquals(Agent.Review.Phase.WAITING, r.review.phase, "its run may just not have started");
        r.review.waitingSince -= rt.reviews().notStartedMs;
        poll();
        waitReady(r);
        assertTrue(log(r).contains("[No pipelines on #7]"));
    }

    @Test
    void aPersonIsAskedThenItGoesOn() throws Exception {
        hostApi.checks = checks(CheckState.NEEDS_PERSON);
        Agent r = reviewer("rita");
        startReview(r, "#7");
        waitFor(() -> r.status == Agent.Status.AWAITING && r.reason == Agent.Reason.FAILED, "the question");
        assertEquals(Agent.Review.Ask.NEEDS_PERSON, r.review.ask);
        assertTrue(r.statusDetail.contains("then press Continue"), r.statusDetail);
        assertNotNull(rt.resume(r), "/resume doesn't skip the question");

        hostApi.checks = checks(CheckState.PASSED);
        assertNull(rt.reviews().answer(r, true));
        waitReady(r);
    }

    @Test
    void failedPipelinesWaitForANewCommit() throws Exception {
        hostApi.checks = checks(CheckState.FAILED);
        Agent r = reviewer("rita");
        startReview(r, "#7");
        waitFor(() -> r.status == Agent.Status.AWAITING, "the question");
        assertEquals(Agent.Review.Ask.FAILED, r.review.ask);
        assertTrue(r.statusDetail.contains("build"), "names the failed job: " + r.statusDetail);

        assertNull(rt.reviews().answer(r, true));
        waitFor(() -> r.status == Agent.Status.WAITING, "waiting again");
        poll();
        Thread.sleep(200);
        assertEquals(Agent.Status.WAITING, r.status, "the same commit stays failed");

        Files.writeString(repo.resolve("a.txt"), "two\n");
        git(repo, "commit", "-qam", "two");
        String second = git(repo, "rev-parse", "HEAD");
        hostApi.pr = new HostApi.PullRequest(7, "open", second, head, "main", "dev");
        hostApi.checks = checks(CheckState.PASSED);
        poll();
        waitFor(() -> second.equals(r.review.headSha), "the new head");
        poll();
        waitReady(r);
        assertEquals(second, git(Path.of(r.review.snapshot), "rev-parse", "HEAD"));
    }

    @Test
    void theWaitLimitAsks() throws Exception {
        hostApi.checks = checks(CheckState.PENDING);
        settings.system.pipelineWaitLimitMinutes = 5;
        Agent r = reviewer("rita");
        startReview(r, "#7");
        r.review.waitingSince -= 6 * 60_000L;
        poll();
        waitFor(() -> r.status == Agent.Status.AWAITING, "the question");
        assertEquals(Agent.Review.Ask.LIMIT, r.review.ask);
        assertNull(rt.reviews().answer(r, true));
        assertEquals(Agent.Status.WAITING, r.status);
        assertTrue(System.currentTimeMillis() - r.review.waitingSince < 60_000, "keep waiting starts the limit again");
    }

    @Test
    void pollingSlowsDownAfterTenMinutes() throws Exception {
        hostApi.checks = checks(CheckState.PENDING);
        Agent r = reviewer("rita");
        startReview(r, "#7");
        waitFor(() -> r.review.nextPoll > 0, "the first poll");
        long soon = r.review.nextPoll - System.currentTimeMillis();
        assertTrue(soon <= 60_000 && soon > 50_000, "every 60 s at first: " + soon);
        r.review.waitingSince -= 11 * 60_000L;
        poll();
        waitFor(() -> r.review.nextPoll - System.currentTimeMillis() > 60_000, "the slower polls");
        assertTrue(r.review.nextPoll - System.currentTimeMillis() <= 120_000);
    }

    @Test
    void cancellingTellsTheReviewerAndClosingThePrCancelsToo() throws Exception {
        hostApi.checks = checks(CheckState.FAILED);
        Agent r = reviewer("rita");
        startReview(r, "#7");
        waitFor(() -> r.status == Agent.Status.AWAITING, "the question");
        assertNull(rt.reviews().answer(r, false));
        assertNull(r.review);
        assertFalse(r.reviewOpen);
        waitFor(() -> log(r).contains("[Review cancelled]"), "the notice");
        waitIdle(r);

        hostApi.checks = checks(CheckState.PENDING);
        Agent s = reviewer("sam");
        startReview(s, "#7");
        hostApi.pr = new HostApi.PullRequest(7, "merged", head, head, "main", "dev");
        poll();
        waitFor(() -> s.review == null, "the cancellation");
        waitFor(() -> log(s).contains("merged"), "the reason");
    }

    @Test
    void stopCancelsAWaitingReview() throws Exception {
        hostApi.checks = checks(CheckState.PENDING);
        Agent r = reviewer("rita");
        startReview(r, "#7");
        assertEquals(Agent.Status.WAITING, r.status);
        assertNull(rt.stop(r));
        assertNull(r.review);
        waitFor(() -> log(r).contains("[Review cancelled]"), "the notice");
    }

    @Test
    void aReviewSurvivesARestart() throws Exception {
        hostApi.checks = checks(CheckState.PENDING);
        Agent r = reviewer("rita");
        startReview(r, "#7");
        String id = r.id;
        rt.shutdown();
        runtime();
        Agent back = rt.live(id).agent;
        assertEquals(Agent.Status.WAITING, back.status);
        hostApi.checks = checks(CheckState.PASSED);
        poll();
        waitFor(() -> back.review.phase == Agent.Review.Phase.READY, "the snapshot");
        waitFor(() -> log(back).contains("Review now."), "the message");
    }

    // ---------------------------------------------------------------- targets

    @Test
    void anAgentsNameMeansItsOpenPr() throws Exception {
        Agent bob = rt.create("bob", "coder", "p1", null, "k1", null);
        bob.branch = "juno/bob";
        Agent r = reviewer("rita");
        startReview(r, "bob");
        assertTrue(log(r).contains("start_review"));
        assertNull(r.review);
        assertFalse(r.reviewOpen);
        // The fake's reply repeats what start_review said.
        waitFor(() -> rt.live(r.id).lastReply.contains("bob has no open PR/MR"), "the refusal");

        hostApi.open = Optional.of(hostApi.pr);
        startReview(r, "bob");
        assertNotNull(r.review);
        assertEquals("#7", r.review.target);
        waitReady(r);
    }

    @Test
    void aLinkFindsItsProjectAndAnUnknownRepositoryAsksForAClone() throws Exception {
        Agent r = rt.create("rita", "reviewer", null, null, "k1", null);
        startReview(r, "https://github.com/acme/api/pull/7");
        assertNotNull(r.review, "no project of its own: the project with that remote");
        assertEquals("p1", r.review.projectId);
        assertEquals("acme/api", r.review.repo);
        waitReady(r);
        assertEquals(Path.of(r.review.snapshot), rt.folder(r));
        rt.send(r, "CALL end_review {}", "user");
        waitFor(() -> r.review == null, "the end");
        waitIdle(r);

        startReview(r, "https://github.com/acme/other/pull/1");
        assertNull(r.review);
        assertEquals(Agent.Status.AWAITING, r.status);
        assertEquals(Agent.Reason.FOLDER, r.reason);
        assertTrue(r.statusDetail.contains("git clone https://github.com/acme/other.git"), r.statusDetail);
        assertTrue(rt.live(r.id).lastReply.contains("git clone https://github.com/acme/other.git"));
    }

    @Test
    void refusalsSayWhy() throws Exception {
        Agent r = reviewer("rita");
        rt.send(r, "CALL start_review {\"target\":\"nobody\"}", "task");
        waitFor(() -> rt.live(r.id).lastReply.contains("Couldn't find nobody"), "not-found");
        waitIdle(r);

        hostApi.refuse = new HostApi.HostException(403, "HTTP 403", null);
        rt.send(r, "CALL start_review {\"target\":\"#7\"}", "task");
        waitFor(() -> rt.live(r.id).lastReply.contains("can't read acme/api"), "key-cant-read");
        waitIdle(r);

        hostApi.refuse = new HostApi.HostException(404, "HTTP 404", null);
        rt.send(r, "CALL start_review {\"target\":\"#7\"}", "task");
        waitFor(() -> rt.live(r.id).lastReply.contains("Couldn't find #7"), "not-found");
        waitIdle(r);
        hostApi.refuse = null;

        settings.projects.get(0).remote = "";
        rt.send(r, "CALL start_review {\"target\":\"#7\"}", "task");
        waitFor(() -> rt.live(r.id).lastReply.contains("has no remote"), "no-remote");
        waitIdle(r);
        settings.projects.get(0).remote = "acme/api";

        hostApi.checks = checks(CheckState.PENDING);
        startReview(r, "#7");
        hostApi.pr = new HostApi.PullRequest(8, "open", head, head, "main", "dev");
        rt.send(r, "CALL start_review {\"target\":\"#8\"}", "task");
        waitFor(() -> rt.live(r.id).lastReply.contains("You already have a review open on #7"), "already-open");
    }

    @Test
    void reportsWaitForTheEndOfTheReview() throws Exception {
        Agent r = reviewer("rita");
        r.task = new Agent.Task();
        r.task.gist = "review #7";
        startReview(r, "#7");
        waitReady(r);
        rt.send(r, "CALL report {\"text\":\"posted 5 comments on #7\"}", "user");
        waitFor(() -> rt.live(r.id).lastReply.contains("Juno gets it when your review is finished"), "the hold");
        waitIdle(r);
        assertNotNull(r.heldReport);
        rt.send(r, "CALL end_review {}", "user");
        waitFor(() -> r.heldReport == null, "the release");
    }

    // ---------------------------------------------------------------- keys

    @Test
    void noKeyNoHost() throws Exception {
        Agent r = rt.create("rita", "reviewer", "p1", null);
        rt.send(r, "CALL start_review {\"target\":\"#7\"}", "task");
        waitFor(() -> r.reason == Agent.Reason.KEY, "the 🔑");
        waitIdle(r);
        assertEquals(Agent.Status.AWAITING, r.status, "stays there after the turn");
        assertTrue(r.statusDetail.contains("No GitHub key is set for rita"), r.statusDetail);
        assertTrue(r.statusDetail.contains("acme/api"), r.statusDetail);
        assertTrue(rt.disallowed(r, rt.profile("searcher")).contains("Bash(gh:*)"), "gh is disallowed for anyone keyless");
        assertTrue(rt.disallowed(r, rt.profile("searcher")).contains("Bash(glab:*)"));

        rt.send(r, "ok", "user");
        waitFor(() -> Files.exists(home.agentRunDir(r.id).resolve("mcp.json")), "a process");
        assertTrue(mcpConfig(r).path("github").isMissingNode(), "no host MCP servers without a key");
        assertFalse(mcpConfig(r).path("juno").isMissingNode());
        waitIdle(r);

        rt.setKey(r, Key.Kind.GITHUB, "k1");
        rt.send(r, "ok", "user");
        waitFor(() -> !mcpConfig0(r).path("github").isMissingNode(), "the host server after the key was set");
    }

    private JsonNode mcpConfig0(Agent a) {
        try {
            return mcpConfig(a);
        } catch (IOException e) {
            return Json.MAPPER.createObjectNode();
        }
    }

    @Test
    void gitRefusalsStopAtTheKey() {
        Agent none = rt.create("nina", "coder", "p1", null);
        assertTrue(rt.host().keyProblem(none, "fatal: transport 'https' not allowed").contains("No GitHub key is set for nina"));
        assertNull(rt.host().keyProblem(none, "fatal: something else"));
        Agent keyed = rt.create("kim", "coder", "p1", null, "k1", null);
        assertNull(rt.host().keyProblem(keyed, "fatal: transport 'https' not allowed"));
        assertTrue(rt.host().keyProblem(keyed, "fatal: Authentication failed for 'https://github.com/a/b'").contains("refused"));
    }

    @Test
    void settingAKeyAnswersTheStop() throws Exception {
        Agent a = rt.create("nina", "coder", "p1", null);
        rt.keyStop(rt.live(a.id), "No key");
        assertEquals(Agent.Reason.KEY, a.reason);
        rt.setKey(a, Key.Kind.GITHUB, "k1");
        assertEquals(Agent.Status.IDLE, a.status);
    }

    @Test
    void theCredentialHelperGivesTheTokenOnlyForTheKeysHost() throws Exception {
        Agent a = rt.create("kim", "coder", "p1", null, "k1", null);
        rt.send(a, "hi", "user");
        waitFor(() -> Files.exists(home.agentRunDir(a.id).resolve("git-credential.sh")), "the helper");
        waitIdle(a);
        Path helper = home.agentRunDir(a.id).resolve("git-credential.sh");
        assertFalse(Files.readString(helper).contains("secret-token"), "the token isn't on disk");
        assertFalse(Files.readString(home.agentRunDir(a.id).resolve("mcp.json")).contains("secret-token"));

        var env = new java.util.HashMap<>(GitAccess.forAgent(List.of("github.com"), helper.toString()));
        String answer = fill(env, "protocol=https\nhost=github.com\n\n");
        assertTrue(answer.contains("username=x-access-token"), answer);
        assertTrue(answer.contains("password=secret-token"), answer);
        assertEquals("", rt.host().gitCredential(a.id, "protocol=https\nhost=gitlab.com\n\n"));
        assertEquals("", rt.host().gitCredential(a.id, "protocol=http\nhost=github.com\n\n"));
        rt.newConversation(a);
        waitFor(() -> !Files.exists(helper), "the helper to go with the process");
    }

    /** {@code git credential fill} with only the helper in the environment. */
    private String fill(java.util.Map<String, String> env, String request) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "-C", repo.toString(), "credential", "fill");
        env.forEach((k, v) -> {
            if (v == null) pb.environment().remove(k);
            else pb.environment().put(k, v);
        });
        Process p = pb.start();
        p.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    @Test
    void aWorktreeUsesTheKeysIdentityAndItsOwnOnly() throws Exception {
        Agent a = rt.create("kim", "coder", "p1", null, "k1", null);
        rt.ensureWorktree(a);
        Path wt = home.worktree(a.id);
        assertEquals("Bot", git(wt, "config", "user.name"));
        assertEquals("bot@example.com", git(wt, "config", "user.email"));
        assertEquals("t", git(repo, "config", "user.name"), "the user's checkout is untouched");
    }

    // ---------------------------------------------------------------- the proxy

    @Test
    void aCoderSeesAndDoesOnlyWhatItMay() throws Exception {
        Agent c = rt.create("kim", "coder", "p1", null, "k1", null);
        rt.send(c, "hi", "user");
        waitFor(() -> Files.exists(home.agentRunDir(c.id).resolve("mcp.json")), "a process");
        waitIdle(c);
        List<String> names = new ArrayList<>();
        hostCall(c, "tools/list", "{}").path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertEquals(List.of("pull_request_read", "create_pull_request", "add_reply_to_pull_request_comment"), names);

        assertFalse(refused(tool(c, "pull_request_read", "{\"method\":\"get\"}")));
        for (String[] bad : new String[][] {{"merge_pull_request", "{}"}, {"update_pull_request", "{\"state\":\"closed\"}"},
                {"update_pull_request", "{\"title\":\"x\"}"}, {"pull_request_review_write", "{\"method\":\"resolve_thread\",\"threadId\":\"t\"}"},
                {"pull_request_review_write", "{\"method\":\"create\",\"event\":\"APPROVE\"}"}, {"create_branch", "{}"}}) {
            JsonNode r = tool(c, bad[0], bad[1]);
            assertTrue(refused(r), bad[0] + " " + bad[1]);
        }
        assertEquals(1, forwarded.size(), "none of the refused calls left Juno");

        JsonNode r = tool(c, "add_reply_to_pull_request_comment", "{\"body\":\"fixed in abc\"}");
        assertFalse(refused(r));
        assertEquals("🤖 kim (coder): fixed in abc", forwarded.get(1).at("/arguments/body").asText(), "the marker is always there");
    }

    @Test
    void openingAPrProtectsItsBaseBranch() throws Exception {
        Agent c = rt.create("kim", "coder", "p1", null, "k1", null);
        rt.send(c, "hi", "user");
        waitFor(() -> Files.exists(home.agentRunDir(c.id).resolve("mcp.json")), "a process");
        waitIdle(c);
        assertFalse(Files.readString(home.worktreeHooks(c.id).resolve("protected-branches")).contains("release"));
        assertFalse(refused(tool(c, "create_pull_request", "{\"base\":\"release\",\"head\":\"juno/kim\",\"title\":\"t\"}")));
        assertTrue(Files.readString(home.worktreeHooks(c.id).resolve("protected-branches")).contains("release"));
        assertEquals(List.of("release"), c.prBases);
    }

    @Test
    void aReviewerPostsOnlyDuringAReviewAfterTheUserAnswered() throws Exception {
        Agent r = reviewer("rita");
        rt.send(r, "hi", "task");
        waitFor(() -> log(r).contains("hi"), "the message");
        waitIdle(r);
        String create = "{\"method\":\"create\",\"commitID\":\"" + head + "\"}";

        JsonNode before = tool(r, "pull_request_review_write", create);
        assertTrue(refused(before));
        assertTrue(text(before).contains("start_review"), text(before));

        startReview(r, "#7");
        waitReady(r);
        JsonNode early = tool(r, "pull_request_review_write", create);
        assertTrue(refused(early), "nothing before the user has answered");
        assertTrue(text(early).contains("hasn't answered"), text(early));
        assertEquals(0, forwarded.size());

        rt.send(r, "post them all", "user");
        waitIdle(r);
        assertFalse(refused(tool(r, "pull_request_review_write", create)));
        JsonNode moved = tool(r, "pull_request_review_write", "{\"method\":\"create\",\"commitID\":\"0000000\"}");
        assertTrue(refused(moved), "only on the reviewed commit");
        assertTrue(text(moved).contains(head), text(moved));

        assertFalse(refused(tool(r, "add_comment_to_pending_review", "{\"path\":\"a.txt\",\"line\":1,\"body\":\"**blocker**: bad\"}")));
        assertEquals("🤖 rita (reviewer): **blocker**: bad", forwarded.get(1).at("/arguments/body").asText());
        assertFalse(refused(tool(r, "add_comment_to_pending_review", "{\"body\":\"🤖 rita (reviewer): as written\"}")));
        assertEquals("🤖 rita (reviewer): as written", forwarded.get(2).at("/arguments/body").asText());

        for (String[] bad : new String[][] {{"pull_request_review_write", "{\"method\":\"submit_pending\",\"event\":\"APPROVE\"}"},
                {"pull_request_review_write", "{\"method\":\"submit_pending\",\"event\":\"REQUEST_CHANGES\"}"},
                {"merge_pull_request", "{}"}, {"update_pull_request", "{\"state\":\"closed\"}"}, {"create_pull_request", "{}"}}) {
            assertTrue(refused(tool(r, bad[0], bad[1])), bad[0] + " " + bad[1]);
        }
        assertFalse(refused(tool(r, "pull_request_review_write", "{\"method\":\"submit_pending\",\"event\":\"COMMENT\",\"body\":\"Reviewed at `abc`\"}")));
    }

    @Test
    void aReviewerResolvesOnlyThreadsItStarted() throws Exception {
        Agent r = reviewer("rita");
        startReview(r, "#7");
        waitReady(r);
        rt.send(r, "yes", "user");
        waitIdle(r);
        String resolve = "{\"method\":\"resolve_thread\",\"threadId\":\"PRRT_1\"}";

        hostApi.threadBody = "A person's comment";
        JsonNode theirs = tool(r, "pull_request_review_write", resolve);
        assertTrue(refused(theirs));
        assertTrue(text(theirs).contains("didn't start"), text(theirs));
        hostApi.threadBody = "🤖 sam (reviewer): another agent's";
        assertTrue(refused(tool(r, "pull_request_review_write", resolve)));
        assertEquals(0, forwarded.size());

        hostApi.threadBody = "🤖 rita (reviewer): mine";
        assertFalse(refused(tool(r, "pull_request_review_write", resolve)));
        assertEquals(1, forwarded.size());
    }

    @Test
    void otherProfilesGetNoHostToolsAndUserProfilesChooseByAction() throws Exception {
        Agent s = rt.create("sue", "searcher", null, null, "k1", null);
        rt.send(s, "hi", "user");
        waitFor(() -> Files.exists(home.agentRunDir(s.id).resolve("mcp.json")), "a process");
        waitIdle(s);
        assertTrue(mcpConfig(s).path("github").isMissingNode());

        Profile own = rt.profile("searcher").duplicate("merger");
        own.allowedTools.add("host:merge");
        settings.profiles.add(own);
        Agent m = rt.create("max", "merger", null, null, "k1", null);
        rt.send(m, "hi", "user");
        waitFor(() -> Files.exists(home.agentRunDir(m.id).resolve("mcp.json")), "a process");
        waitIdle(m);
        assertFalse(refused(tool(m, "merge_pull_request", "{}")));
        assertTrue(refused(tool(m, "pull_request_read", "{}")));
    }

    @Test
    void aWrongTokenIsRefusedAndTheTokenNeverReachesTheAgent() throws Exception {
        Agent r = reviewer("rita");
        rt.send(r, "hi", "task");
        waitFor(() -> Files.exists(home.agentRunDir(r.id).resolve("mcp.json")), "a process");
        waitIdle(r);
        String url = mcpConfig(r).at("/github/url").asText();
        HttpResponse<String> no = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer wrong").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, no.statusCode());
        assertFalse(Files.readString(home.agentRunDir(r.id).resolve("system-prompt.md")).contains("secret-token"));
        assertTrue(Files.readString(home.agentRunDir(r.id).resolve("system-prompt.md")).contains("\"bot\""));
    }
}
