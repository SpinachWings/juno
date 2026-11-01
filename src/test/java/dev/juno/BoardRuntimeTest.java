package dev.juno;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.juno.git.Git;
import dev.juno.git.Worktrees;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.model.Settings;
import dev.juno.provider.AgentEvent;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.runtime.AgentRuntime;
import dev.juno.runtime.Conversations;
import dev.juno.runtime.Journal;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.JunoHome;
import dev.juno.store.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The board's runtime side without a CLI: queue, worktrees, conversations, deletion, storage. */
class BoardRuntimeTest {
    @TempDir Path dir;
    private AgentRuntime rt;
    private Settings settings;
    private JunoHome home;
    private Path repo;
    private Path claude;

    private void runtime() throws Exception {
        home = new JunoHome(dir.resolve("home"));
        SettingsStore store = new SettingsStore(home);
        settings = store.load();
        Profile t = settings.profiles.stream().filter(p -> p.name.equals("searcher")).findFirst().orElseThrow()
                .duplicate("tester");
        settings.profiles.add(t);
        settings.system.claudePath = dir.resolve("no-claude").toString();
        repo = dir.resolve("repo");
        Files.createDirectories(repo);
        if (!Files.exists(repo.resolve(".git"))) {
            git(repo, "init", "-q", "-b", "main");
            Files.writeString(repo.resolve("a.txt"), "one\n");
            git(repo, "add", "-A");
            git(repo, "commit", "-q", "-m", "init");
        }
        Project p = new Project();
        p.id = "p1";
        p.name = "repo";
        p.folder = repo.toString();
        settings.projects.add(p);
        Project q = new Project();
        q.id = "p2";
        q.name = "other";
        q.folder = repo.toString();
        settings.projects.add(q);
        claude = dir.resolve("claude");
        SkillLibrary lib = new SkillLibrary(home);
        rt = new AgentRuntime(home, new ClaudeCodeProvider(claude, home), () -> settings,
                () -> store.save(settings), lib, new SkillResolver(lib));
        rt.start();
    }

    private static void git(Path where, String... args) throws IOException {
        List<String> cmd = new java.util.ArrayList<>(List.of("-c", "user.name=t", "-c", "user.email=t@t"));
        cmd.addAll(List.of(args));
        Git.run(where, cmd.toArray(String[]::new));
    }

    @AfterEach
    void stop() {
        if (rt != null) rt.shutdown();
    }

    // ---------------------------------------------------------------- queue

    @Test
    void agentsQueueWhenTheCapIsReachedAndStartInOrder() throws Exception {
        runtime();
        settings.system.maxAgentsWorking = 1;
        Agent busy = rt.create("busy", "tester", null, null);
        Agent b = rt.create("bee", "tester", null, null);
        Agent c = rt.create("sea", "tester", null, null);
        busy.turnOpen = true;
        busy.status = Agent.Status.WORKING;

        rt.send(b, "first", "user");
        rt.send(c, "second", "user");
        assertEquals(Agent.Status.QUEUED, b.status);
        assertEquals("Queued · 1st", b.statusText());
        assertEquals("Queued · 2nd", c.statusText());
        assertTrue(rt.refusal(b).contains("queued"));
        assertEquals("first", b.queuedMessage);

        assertEquals("Dropped the queued message.", rt.stop(b));
        assertEquals(Agent.Status.IDLE, b.status);
        assertNull(b.queuedMessage);
        assertEquals("Queued · 1st", c.statusText(), "the places close up");

        busy.turnOpen = false;
        rt.drain();
        // there is no Claude Code here, so the turn fails to start; what matters is that it left the queue
        assertNull(c.queuedMessage);
        assertEquals(0, c.queuePlace);
        assertEquals(Agent.Reason.FAILED, c.reason);
    }

    @Test
    void waitingAgentsAndJunoDoNotCountTowardsTheCap() throws Exception {
        runtime();
        settings.system.maxAgentsWorking = 1;
        Agent waiting = rt.create("wait", "tester", null, null);
        waiting.turnOpen = true;
        waiting.status = Agent.Status.WAITING;
        Agent b = rt.create("bee", "tester", null, null);
        rt.send(b, "go", "user");
        assertNotEquals(Agent.Status.QUEUED, b.status);
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        assertFalse(unexpected.equals(actual), "was " + actual);
    }

    @Test
    void aQueuedMessageSurvivesARestart() throws Exception {
        runtime();
        settings.system.maxAgentsWorking = 1;
        Agent busy = rt.create("busy", "tester", null, null);
        Agent b = rt.create("bee", "tester", null, null);
        busy.turnOpen = true;
        busy.status = Agent.Status.WORKING;
        rt.send(b, "later", "user");
        busy.turnOpen = false;
        busy.status = Agent.Status.IDLE;
        assertEquals("later", b.queuedMessage);
        rt.shutdown();
        rt = null;
        runtime();
        Agent back = rt.byName("bee").orElseThrow();
        // Restored into the queue, and started at once because nothing else is working: with no Claude Code here
        // that start fails, but the message is no longer waiting.
        assertNull(back.queuedMessage);
        assertEquals(Agent.Reason.FAILED, back.reason);
    }

    // ---------------------------------------------------------------- worktrees

    private Agent coder() {
        return rt.create("cody", "coder", "p1", null);
    }

    @Test
    void aCoderGetsItsOwnWorktreeAndTheOthersReadTheProjectFolder() throws Exception {
        runtime();
        Agent cody = coder();
        Agent sally = rt.create("sally", "searcher", "p1", null);
        assertTrue(rt.usesWorktree(cody));
        assertFalse(rt.usesWorktree(sally), "a searcher doesn't edit files");
        assertEquals(repo, rt.folder(sally));
        assertEquals(home.worktree(cody.id), rt.folder(cody));
        rt.ensureWorktree(cody);
        assertTrue(Files.isDirectory(home.worktree(cody.id)));
        assertEquals("juno/cody", cody.branch);
        assertEquals("main", cody.baseBranch);
        assertTrue(Files.isRegularFile(home.worktreeHooks(cody.id).resolve("pre-commit")));
        assertEquals("juno/cody", Worktrees.currentBranch(home.worktree(cody.id)));
        assertEquals("main", Worktrees.currentBranch(repo), "your checkout is untouched");
    }

    @Test
    void aProjectThatIsNotARepositoryIsUsedDirectly() throws Exception {
        runtime();
        Path plain = dir.resolve("plain");
        Files.createDirectories(plain);
        Project p = new Project();
        p.id = "p3";
        p.name = "plain";
        p.folder = plain.toString();
        settings.projects.add(p);
        Agent cody = rt.create("cody", "coder", "p3", null);
        assertFalse(rt.usesWorktree(cody));
        assertEquals(plain, rt.folder(cody));
    }

    @Test
    void removingAnIdleWorktreeKeepsTheBranchAndItComesBack() throws Exception {
        runtime();
        Agent cody = coder();
        rt.ensureWorktree(cody);
        Path wt = home.worktree(cody.id);
        Files.writeString(wt.resolve("work.txt"), "x\n");
        assertTrue(rt.removeWorktree(cody).contains("uncommitted"));
        assertTrue(Files.isDirectory(wt));
        git(wt, "add", "-A");
        git(wt, "commit", "-q", "-m", "work");
        assertTrue(rt.removeWorktree(cody).contains("only on juno/cody"), "unpushed commits count");
        git(repo, "merge", "-q", "juno/cody");
        assertNull(rt.removeWorktree(cody));
        assertFalse(Files.exists(wt));
        assertTrue(Worktrees.branchExists(repo, "juno/cody"));
        rt.ensureWorktree(cody);
        assertEquals("juno/cody", Worktrees.currentBranch(wt));
        assertTrue(Files.exists(wt.resolve("work.txt")));
    }

    @Test
    void aWorkingAgentsWorktreeIsNotRemoved() throws Exception {
        runtime();
        Agent cody = coder();
        rt.ensureWorktree(cody);
        cody.turnOpen = true;
        cody.status = Agent.Status.WORKING;
        assertTrue(rt.removeWorktree(cody).contains("not idle"));
        assertTrue(rt.storage().get(0).hasWorktree());
        assertFalse(rt.storage().get(0).canRemove());
    }

    @Test
    void changingProjectNeedsTheWorktreeToBeSafeToRemove() throws Exception {
        runtime();
        Agent cody = coder();
        rt.ensureWorktree(cody);
        Files.writeString(home.worktree(cody.id).resolve("wip.txt"), "x\n");
        String refused = rt.setProject(cody, "p2");
        assertNotNull(refused);
        assertEquals("p1", cody.projectId, "nothing changed");
        Files.delete(home.worktree(cody.id).resolve("wip.txt"));
        assertNull(rt.setProject(cody, "p2"));
        assertEquals("p2", cody.projectId);
        assertFalse(Files.exists(home.worktree(cody.id)));
    }

    @Test
    void aProjectChangeWhileWorkingWaitsForTheTurnToEnd() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", "p1", null);
        bob.turnOpen = true;
        bob.status = Agent.Status.WORKING;
        assertTrue(rt.requestProject(bob, "p2").contains("isn't working"));
        assertEquals("p1", bob.projectId);
        assertEquals("p2", rt.pendingProject(bob));
    }

    // ---------------------------------------------------------------- deletion

    @Test
    void deletingAnAgentRemovesWhatJunoMadeAndKeepsTheBranch() throws Exception {
        runtime();
        Agent cody = coder();
        rt.ensureWorktree(cody);
        Files.writeString(home.worktree(cody.id).resolve("lost.txt"), "x\n");
        assertTrue(rt.deleteWarning(cody).contains("uncommitted"));
        Path transcripts = claude.resolve("projects/some-project");
        Files.createDirectories(transcripts);
        cody.sessionId = "sess-1";
        Files.writeString(transcripts.resolve("sess-1.jsonl"), "{}\n");
        assertEquals(1, rt.transcriptsOf(cody).size());

        assertNull(rt.delete(cody, false));
        assertTrue(rt.byName("cody").isEmpty());
        assertFalse(Files.exists(home.agentDir(cody.id)));
        assertFalse(Files.exists(home.worktree(cody.id)));
        assertFalse(Files.exists(home.worktreeHooks(cody.id)));
        assertTrue(Worktrees.branchExists(repo, "juno/cody"), "branches are kept");
        assertTrue(Files.exists(transcripts.resolve("sess-1.jsonl")), "Claude Code's transcripts only when asked");
    }

    @Test
    void deletingCanAlsoRemoveClaudeCodesTranscripts() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        Path transcripts = claude.resolve("projects/some-project");
        Files.createDirectories(transcripts.resolve("sess-9"));
        Files.writeString(transcripts.resolve("sess-9.jsonl"), "{}\n");
        Files.writeString(transcripts.resolve("sess-9/sub.txt"), "x");
        Files.writeString(transcripts.resolve("other.jsonl"), "{}\n");
        bob.sessionId = "sess-9";
        assertNull(rt.delete(bob, true));
        assertFalse(Files.exists(transcripts.resolve("sess-9.jsonl")));
        assertFalse(Files.exists(transcripts.resolve("sess-9")));
        assertTrue(Files.exists(transcripts.resolve("other.jsonl")), "someone else's transcript is left alone");
    }

    // ---------------------------------------------------------------- conversations and limits

    private Agent.Conversation conversation(Agent a, String id, String session, long ended) throws IOException {
        Agent.Conversation c = new Agent.Conversation();
        c.id = id;
        c.sessionId = session;
        c.started = ended - 1000;
        c.ended = ended;
        c.firstTask = "task " + id;
        a.conversations.add(c);
        new Journal(home.journalsDir(a.id).resolve(id + ".jsonl")).append(new AgentEvent.UserText("hi " + id, "user"));
        Files.createDirectories(home.logsDir(a.id));
        Files.writeString(home.logsDir(a.id).resolve(id + ".log"), "log\n");
        return c;
    }

    @Test
    void anAgentKeepsTenEarlierConversationsNoneOlderThanThirtyDays() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        long now = System.currentTimeMillis();
        long day = 86_400_000L;
        for (int i = 0; i < 12; i++) conversation(bob, "c" + i, "s" + i, now - i * day / 2);
        conversation(bob, "ancient", "sa", now - 40 * day);
        Agent.Conversation current = conversation(bob, "now", "sn", 0);
        bob.conversationId = "now";
        List<Agent.Conversation> dropped = Conversations.applyLimits(home, bob, now);
        assertEquals(3, dropped.size(), "two over ten, and one over thirty days");
        assertTrue(dropped.stream().anyMatch(c -> c.id.equals("ancient")));
        assertEquals(11, bob.conversations.size(), "ten earlier ones and the current one");
        assertTrue(bob.conversations.contains(current));
        assertFalse(Files.exists(home.journalsDir(bob.id).resolve("ancient.jsonl")));
        assertFalse(Files.exists(home.logsDir(bob.id).resolve("ancient.log")));
        assertTrue(Files.exists(home.journalsDir(bob.id).resolve("c0.jsonl")));
    }

    @Test
    void switchingBackToAnEarlierConversationSwapsThemAndReloadsItsHistory() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        Agent.Conversation old = conversation(bob, "old", "sess-old", System.currentTimeMillis() - 5000);
        bob.conversationId = "now";
        Agent.Conversation now = conversation(bob, "now", "sess-now", 0);
        now.ended = 0;
        bob.sessionId = "sess-now";
        // Claude Code deleted the old transcript: greyed out, refused
        assertTrue(rt.switchConversation(bob, "old").contains("no longer has"));
        Path t = claude.resolve("projects/p");
        Files.createDirectories(t);
        Files.writeString(t.resolve("sess-old.jsonl"), "{}\n");

        assertNull(rt.switchConversation(bob, "old"));
        assertEquals("old", bob.conversationId);
        assertEquals("sess-old", bob.sessionId);
        assertEquals(0, old.ended, "it is the current one now");
        assertTrue(now.ended > 0, "the one left takes its place in the list");
        assertEquals(List.of(now), Conversations.earlier(bob));
        assertEquals(new AgentEvent.UserText("hi old", "user"), rt.live(bob.id).history().get(0));

        bob.turnOpen = true;
        assertTrue(rt.switchConversation(bob, "now").contains("working"));
    }

    @Test
    void newConversationKeepsTheOldOneWithItsSizeAndLastReply() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        String first = bob.conversationId;
        Agent.Conversation c = conversation(bob, first, "sess-1", 0);
        bob.sessionId = "sess-1";
        rt.live(bob.id).lastReply = "All done.";
        assertNull(rt.newConversation(bob));
        assertNotNull(bob.conversationId);
        assertFalse(bob.conversationId.equals(first));
        assertTrue(c.ended > 0);
        assertEquals("All done.", c.summary);
        assertEquals(List.of(c), Conversations.earlier(bob));
        assertNull(bob.sessionId);
    }

    // ---------------------------------------------------------------- options menu

    @Test
    void renamingChecksTheNameLikeCreating() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        rt.create("alice", "tester", null, null);
        assertNull(rt.rename(bob, "Bobby"));
        assertEquals("Bobby", bob.name);
        assertNull(rt.rename(bob, "bobby"), "its own name in another case is fine");
        assertTrue(rt.rename(bob, "Alice").contains("already"));
        assertTrue(rt.rename(bob, "juno").contains("taken"));
        assertTrue(rt.rename(bob, " ").contains("name"));
    }

    @Test
    void ruleAndKeyChangesAreSaved() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        bob.rules.add(new Agent.AgentRule("Bash(ls:*)", "ls", "ls -la"));
        rt.removeRule(bob, bob.rules.get(0));
        assertTrue(bob.rules.isEmpty());
        rt.setKey(bob, dev.juno.model.Key.Kind.GITHUB, "k1");
        rt.setKey(bob, dev.juno.model.Key.Kind.GITLAB, "k2");
        assertEquals("k1", bob.githubKeyId);
        assertEquals("k2", bob.gitlabKeyId);
        rt.shutdown();
        rt = null;
        runtime();
        assertEquals("k1", rt.byName("bob").orElseThrow().githubKeyId, "kept after a restart");
    }
}
