package dev.juno.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.FakeProvider;
import dev.juno.comms.JunoMcpServer;
import dev.juno.comms.Proposal;
import dev.juno.model.Agent;
import dev.juno.model.Profile;
import dev.juno.model.Settings;
import dev.juno.model.SystemSettings.AssignApproval;
import dev.juno.provider.AgentEvent;
import dev.juno.provider.claude.ClaudeCodeProvider;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.Json;
import dev.juno.store.JunoHome;
import dev.juno.store.SettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Juno and communication (design §4) end to end through the MCP server, with {@code FakeClaude} standing in for Claude
 * Code: tool access, links, the loop guard, delegation and approvals, reports and digests, context summaries.
 */
class CommsTest {
    @TempDir Path dir;
    private AgentRuntime rt;
    private Settings settings;
    private FakeProvider provider;
    private PaneCommands commands;

    private void runtime() throws Exception {
        JunoHome home = new JunoHome(dir.resolve("home"));
        SettingsStore store = new SettingsStore(home);
        settings = store.load();
        if (settings.profiles.stream().noneMatch(p -> p.name.equals("tester"))) {
            settings.profiles.add(settings.profiles.stream().filter(p -> p.name.equals("searcher")).findFirst()
                    .orElseThrow().duplicate("tester"));
            Profile mute = settings.profiles.stream().filter(p -> p.name.equals("searcher")).findFirst().orElseThrow()
                    .duplicate("mute");
            mute.allowedTools.remove("mcp__juno__send_message");
            mute.disallowedTools.add("mcp__juno__list_agents");
            settings.profiles.add(mute);
        }
        Files.createDirectories(dir.resolve("logs"));
        provider = new FakeProvider(new ClaudeCodeProvider(dir.resolve("claude"), home), dir.resolve("logs"));
        SkillLibrary lib = new SkillLibrary(home);
        rt = new AgentRuntime(home, provider, () -> settings, () -> store.save(settings), lib, new SkillResolver(lib));
        rt.start();
        rt.orchestration().undoMs = 0; // the undo window has its own tests
        commands = new PaneCommands(rt);
    }

    @AfterEach
    void stop() {
        if (rt != null) rt.shutdown();
    }

    // ---------------------------------------------------------------- Juno herself

    @Test
    void junoIsAlwaysThereAndNeverQueued() throws Exception {
        runtime();
        Agent juno = rt.juno();
        assertEquals("Juno", juno.name);
        assertEquals("orchestrator", juno.profile);
        assertEquals(juno, rt.byName("juno").orElseThrow());
        assertTrue(rt.agents().isEmpty(), "agents() is everyone but Juno");
        assertEquals("Juno can't be deleted.", rt.delete(juno, false));
        assertNotNull(rt.rename(juno, "Bob"));
        settings.system.maxAgentsWorking = 1;
        Agent busy = rt.create("busy", "tester", null, null);
        busy.turnOpen = true;
        busy.status = Agent.Status.WORKING;
        rt.send(juno, "hello", "user");
        assertNotEquals(Agent.Status.QUEUED, juno.status);
        busy.turnOpen = false;
        waitUntil(() -> juno.status == Agent.Status.DONE);
        assertTrue(log(juno).contains("hello"));
    }

    @Test
    void junoComesBackAfterARestartWithHerConversation() throws Exception {
        runtime();
        rt.send(rt.juno(), "remember this", "user");
        waitUntil(() -> rt.juno().status == Agent.Status.DONE);
        String session = rt.juno().sessionId;
        rt.shutdown();
        runtime();
        assertEquals(session, rt.juno().sessionId);
        assertEquals(1, rt.all().size());
    }

    // ---------------------------------------------------------------- tool access (design §4.4)

    @Test
    void toolAccessFollowsTheProfile() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        assertEquals(List.of("list_agents", "send_message", "report", "request_folder", "request_permission"),
                toolNames(bob));
        List<String> juno = toolNames(rt.juno());
        assertTrue(juno.containsAll(List.of("assign_task", "get_agent_output", "get_agent_context", "set_link")));
        assertFalse(juno.contains("start_review"), "the orchestrator profile doesn't allow reviews");

        JsonNode r = call(bob, "assign_task", "{\"to\":\"bob\",\"task\":\"x\"}");
        assertTrue(r.path("isError").asBoolean());
        assertEquals("`assign_task` is for Juno only.", text(r));

        Agent mute = rt.create("mo", "mute", null, null);
        assertEquals("`send_message` isn't allowed for the `mute` profile.", text(call(mute, "send_message",
                "{\"to\":\"Juno\",\"text\":\"hi\"}")));
        assertTrue(text(call(mute, "list_agents", "{}")).contains("isn't allowed"), "disallowed wins");
        assertTrue(text(call(bob, "nope", "{}")).contains("no tool called"));
    }

    // ---------------------------------------------------------------- links and messages (design §4.1)

    @Test
    void messagesNeedALinkExceptWithJuno() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        Agent jim = rt.create("jim", "tester", null, "#FF0000");
        assertEquals("You are not linked to jim. Ask Juno to link you, or ask the user.",
                text(call(bob, "send_message", "{\"to\":\"Jim\",\"text\":\"hi\"}")));
        assertEquals("You can't message yourself.", text(call(bob, "send_message", "{\"to\":\"bob\",\"text\":\"hi\"}")));
        assertTrue(text(call(bob, "send_message", "{\"to\":\"Juno\",\"text\":\"a question\"}")).startsWith("Delivered to Juno"));
        waitUntil(() -> log(rt.juno()).contains("[Message from bob (tester)]\na question"));

        // list_agents: Juno and the linked agents only, until a link opens.
        assertFalse(text(call(bob, "list_agents", "{}")).contains("jim"));
        assertEquals(PaneCommands.Outcome.Sent.class, commands.handle(bob, "/cantalktojim").getClass());
        assertEquals("jim and bob are already linked.", ((PaneCommands.Outcome.Said) commands.handle(jim, "/cantalkto bob")).text());
        assertTrue(text(call(bob, "list_agents", "{}")).contains("- jim · tester · no project · Idle · context unknown · colour #FF0000"));
        assertEquals(List.of(rt.juno(), jim), rt.orchestration().contacts(bob));
        assertTrue(jim.notices.get(0).startsWith("[Link opened] You can now message bob (tester, Idle). Linked by the user."));
        assertTrue(rt.juno().notices.contains("[Link] bob ↔ jim linked by the user."));
        assertTrue(notices(rt.juno()).contains("🔗 bob ↔ jim linked by you."));
        assertTrue(notices(bob).contains("🔗 bob ↔ jim linked by you."));

        assertEquals("Delivered to jim. It's read when jim's current step ends.",
                text(call(bob, "send_message", "{\"to\":\"jim\",\"text\":\"can you check x?\"}")));
        waitUntil(() -> log(jim).contains("can you check x?"));
        String got = log(jim);
        assertTrue(got.indexOf("[Link opened]") < got.indexOf("[Message from bob (tester)]"), "notices ride along first");
        assertTrue(jim.notices.isEmpty());

        commands.handle(bob, "/stoptalkingtojim");
        assertTrue(text(call(bob, "send_message", "{\"to\":\"jim\",\"text\":\"x\"}")).startsWith("You are not linked"));
        assertTrue(jim.notices.contains("[Link closed] You can no longer message bob. Unlinked by the user."));
        assertEquals("Juno can always talk to everyone; links are between two other agents.",
                ((PaneCommands.Outcome.Said) commands.handle(rt.juno(), "/cantalktobob")).text());
    }

    @Test
    void theSystemPromptListsWhoTheAgentCanTalkTo() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        Agent jim = rt.create("jim", "tester", null, null);
        commands.handle(bob, "/cantalktojim");
        String contacts = rt.orchestration().contactsText(bob);
        assertEquals("- Juno (`orchestrator`)\n- jim (`tester`, Idle)", contacts);
        assertEquals("- bob (`tester`, Idle)\n- jim (`tester`, Idle)", rt.orchestration().contactsText(rt.juno()));
        assertTrue(AgentPrompts.systemPrompt(settings, bob, rt.profile("tester"), null, dir, "", contacts)
                .contains("- jim (`tester`, Idle)"));
        assertNotNull(jim);
    }

    @Test
    void linksSurviveARestartAndGoWithADeletedAgent() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        rt.create("jim", "tester", null, null);
        commands.handle(bob, "/cantalktojim");
        rt.shutdown();
        runtime();
        bob = rt.byName("bob").orElseThrow();
        Agent jim = rt.byName("jim").orElseThrow();
        assertEquals(List.of(jim.id), rt.orchestration().contacts(bob).stream().skip(1).map(a -> a.id).toList());
        rt.delete(jim, false);
        assertEquals(List.of(rt.juno()), rt.orchestration().contacts(bob));
        assertTrue(bob.notices.contains("[Link closed] jim was deleted."));
    }

    @Test
    void junoOpensRequestedLinksAndHoldsHerOwnSuggestions() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        Agent jim = rt.create("jim", "tester", null, null);
        Agent juno = rt.juno();
        assertEquals("Juno can always talk to everyone; links are only between two other agents.",
                text(call(juno, "set_link", "{\"a\":\"bob\",\"b\":\"Juno\",\"open\":true,\"requested_by_user\":true}")));
        assertEquals("An agent can't be linked to itself.",
                text(call(juno, "set_link", "{\"a\":\"bob\",\"b\":\"bob\",\"open\":true,\"requested_by_user\":true}")));
        assertEquals("Give a `reason`: the user sees it on the approval card.",
                text(call(juno, "set_link", "{\"a\":\"bob\",\"b\":\"jim\",\"open\":true,\"requested_by_user\":false}")));

        assertEquals("Held for the user's approval. The outcome arrives as a message.", text(call(juno, "set_link",
                "{\"a\":\"bob\",\"b\":\"jim\",\"open\":true,\"requested_by_user\":false,\"reason\":\"jim wrote the API\"}")));
        assertFalse(linked(bob, jim), "held until the user approves");
        Proposal p = rt.orchestration().proposals().get(0);
        assertEquals(Proposal.Kind.LINK, p.kind);
        assertEquals("jim wrote the API", p.reason);
        assertTrue(juno.unread);
        assertNull(rt.orchestration().approveLink(p.id));
        assertTrue(linked(bob, jim));
        assertTrue(juno.notices.contains("[Approved] bob ↔ jim linked."));
        assertTrue(bob.notices.get(0).endsWith("Linked by Juno."));

        assertEquals("Unlinked jim ↔ bob.", text(call(juno, "set_link",
                "{\"a\":\"jim\",\"b\":\"bob\",\"open\":false,\"requested_by_user\":false}")), "closing is immediate");
        assertEquals("jim and bob are already unlinked.", text(call(juno, "set_link",
                "{\"a\":\"jim\",\"b\":\"bob\",\"open\":false,\"requested_by_user\":true}")));
        call(juno, "set_link", "{\"a\":\"bob\",\"b\":\"jim\",\"open\":true,\"requested_by_user\":false,\"reason\":\"r\"}");
        assertTrue(((PaneCommands.Outcome) commands.handle(juno, "/deny")) instanceof PaneCommands.Outcome.Sent);
        assertFalse(linked(bob, jim));
        assertTrue(juno.notices.contains("[Declined] The user didn't link bob ↔ jim."));
        assertEquals("Linked bob ↔ jim.", text(call(juno, "set_link",
                "{\"a\":\"bob\",\"b\":\"jim\",\"open\":true,\"requested_by_user\":true}")));
    }

    // ---------------------------------------------------------------- loop guard (design §4.4)

    @Test
    void theLoopGuardStopsAChainAndAMessageFromTheUserResetsIt() throws Exception {
        runtime();
        settings.system.hopLimit = 2;
        Agent bob = rt.create("bob", "tester", null, null);
        Agent jim = rt.create("jim", "tester", null, null);
        commands.handle(bob, "/cantalktojim");
        assertTrue(text(call(bob, "send_message", "{\"to\":\"jim\",\"text\":\"one\"}")).startsWith("Delivered"));
        assertTrue(text(call(jim, "send_message", "{\"to\":\"bob\",\"text\":\"two\"}")).startsWith("Delivered"));
        assertEquals(2, bob.hop);
        JsonNode r = call(bob, "send_message", "{\"to\":\"jim\",\"text\":\"three\"}");
        assertTrue(r.path("isError").asBoolean());
        assertEquals("Not delivered: the chain between you and jim has reached 2 hops without a message from the user.\n"
                + "Finish without jim, or ask the user.", text(r));
        assertTrue(jim.notices.stream().anyMatch(n -> n.startsWith("[Stopped by Juno] The chain of messages between you "
                + "and bob reached 2 hops")));
        assertTrue(notices(bob).stream().anyMatch(n -> n.startsWith("⛔ Messages between bob and jim stopped")));
        // The count goes with bob into a chain with a third agent.
        Agent sue = rt.create("sue", "tester", null, null);
        commands.handle(bob, "/cantalktosue");
        assertTrue(text(call(bob, "send_message", "{\"to\":\"sue\",\"text\":\"x\"}")).startsWith("Not delivered"));

        waitUntil(() -> !bob.turnOpen);
        rt.hey(bob, "carry on, you two");
        assertEquals(0, bob.hop);
        assertEquals(0, jim.hop, "a message from the user to either agent resets the pair");
        assertTrue(text(call(bob, "send_message", "{\"to\":\"jim\",\"text\":\"three\"}")).startsWith("Delivered"));
        assertNotNull(sue);
    }

    // ---------------------------------------------------------------- delegation (design §4.2)

    @Test
    void anUnnamedTaskIsHeldUntilApprovedAndItsReportComesBack() throws Exception {
        runtime();
        Agent juno = rt.juno();
        Agent bob = rt.create("bob", "tester", null, null);
        rt.send(juno, "get someone to fix the login bug", "user");
        waitUntil(() -> juno.status == Agent.Status.DONE);
        String task = "Fix the login bug.\nCALL report {\"text\":\"Fixed on branch login-fix.\"}";
        JsonNode held = call(juno, "assign_task", Json.MAPPER.createObjectNode().put("to", "bob").put("task", task)
                .put("fresh", false).toString());
        assertEquals("Held for the user's approval (the user didn't name bob). The outcome arrives as a message.", text(held));
        assertNull(bob.task);
        Proposal p = rt.orchestration().proposals().get(0);
        assertTrue(p.askAssign);
        assertFalse(p.fresh);

        assertNull(rt.orchestration().approveAssign(p.id, task + "\nAlso add a test.", null, false));
        assertTrue(rt.orchestration().proposals().isEmpty());
        assertEquals("Fix the login bug. …", bob.task.gist);
        waitUntil(() -> log(bob).contains("[Task from Juno]\nFix the login bug."));
        assertTrue(log(bob).contains("Also add a test."));
        // bob reports; the report reaches Juno as a message, after the approval outcome.
        waitUntil(() -> log(juno).contains("[Report from bob (tester) · Done · context 10 %]"));
        String got = log(juno);
        assertTrue(got.contains("[Approved] bob got the task (edited by the user)."));
        assertTrue(got.contains("Task: Fix the login bug. …\nFixed on branch login-fix.\nFiles changed: none"));
        assertTrue(bob.task.reported);
        assertEquals("Fixed on branch login-fix.", bob.lastReport);
    }

    @Test
    void namedTasksGoStraightThroughAndFreshStartsAlwaysAsk() throws Exception {
        runtime();
        Agent juno = rt.juno();
        Agent bob = rt.create("Bob Smith", "tester", null, null);
        rt.send(juno, "give bob smith the docs task", "user");
        waitUntil(() -> juno.status == Agent.Status.DONE);
        assertEquals("Delivered to Bob Smith (continuing its conversation). Its result will be reported to you.",
                text(call(juno, "assign_task", "{\"to\":\"bobsmith\",\"task\":\"write the docs\",\"fresh\":false}")));
        waitUntil(() -> bob.status == Agent.Status.DONE && log(juno).contains("[Report from Bob Smith"));
        assertTrue(log(juno).contains("\nok\n"), "no report call: the final reply is used");
        String firstConversation = bob.conversationId;

        assertTrue(text(call(juno, "assign_task", "{\"to\":\"Bob Smith\",\"task\":\"new thing\",\"fresh\":true}"))
                .contains("a fresh start always needs the user's approval"));
        Proposal p = rt.orchestration().proposals().get(0);
        assertFalse(p.askAssign, "named, so only the fresh start is asked");
        assertTrue(((PaneCommands.Outcome) commands.handle(juno, "/approve")) instanceof PaneCommands.Outcome.Sent);
        assertNotEquals(firstConversation, bob.conversationId);
        assertEquals(1, Conversations.earlier(bob).size(), "the old conversation stays in the list");
        assertTrue(juno.notices.contains("[Approved] Bob Smith starts fresh with the task."));
        waitUntil(() -> bob.status == Agent.Status.DONE);
        // The replaced process's exit must not take the new one's MCP token with it.
        Thread.sleep(500);
        rt.send(bob, "CALL list_agents {}", "user");
        waitUntil(() -> bob.status == Agent.Status.DONE && replies(bob).contains("list_agents: Agents you can message now"));

        call(juno, "assign_task", "{\"to\":\"Bob Smith\",\"task\":\"another\",\"fresh\":true}");
        String id = rt.orchestration().proposals().get(0).id;
        String conversation = bob.conversationId;
        assertNull(rt.orchestration().approveAssign(id, null, null, false));
        assertEquals(conversation, bob.conversationId, "Continue instead keeps the conversation");
        assertTrue(juno.notices.contains("[Approved] Bob Smith gets the task in its current conversation instead."));
    }

    /** Juno on the fake CLI, after a message from the user that names bob. */
    private Agent bobNamed(long undoMs) throws Exception {
        runtime();
        rt.orchestration().undoMs = undoMs;
        Agent bob = rt.create("bob", "tester", null, null);
        rt.send(rt.juno(), "don't give the docs to bob", "user");
        waitUntil(() -> rt.juno().status == Agent.Status.DONE);
        return bob;
    }

    @Test
    void aTaskForAnAgentTheUserNamedCanBeUndone() throws Exception {
        Agent bob = bobNamed(60_000);
        Agent juno = rt.juno();
        assertEquals("It goes to bob in 60 seconds, continuing its conversation, unless the user undoes it. If they do, "
                + "a message tells you. Otherwise its result will be reported to you.",
                text(call(juno, "assign_task", "{\"to\":\"bob\",\"task\":\"write the docs\"}")));
        assertTrue(rt.orchestration().proposals().get(0).sendAt > 0);
        assertNull(bob.task, "nothing reaches the agent during the window");
        assertEquals(PaneCommands.Outcome.Sent.class, commands.handle(juno, "/deny").getClass());
        assertTrue(rt.orchestration().proposals().isEmpty());
        assertTrue(juno.notices.contains("[Cancelled] The user cancelled the task for bob."));
        assertNull(bob.task);
        assertEquals("", log(bob));
    }

    @Test
    void aTaskForAnAgentTheUserNamedGoesByItselfWhenTheTimeIsUp() throws Exception {
        Agent bob = bobNamed(300);
        Agent juno = rt.juno();
        call(juno, "assign_task", "{\"to\":\"bob\",\"task\":\"write the docs\"}");
        waitUntil(() -> log(bob).contains("[Task from Juno]\nwrite the docs"));
        assertTrue(rt.orchestration().proposals().isEmpty());
        assertEquals("write the docs", bob.task.gist);
        assertFalse(String.join(" ", juno.notices).contains("[Approved]"), "she was already told it would go");
    }

    @Test
    void sendNowSkipsTheWaitAndNeverHasNoWindow() throws Exception {
        Agent bob = bobNamed(60_000);
        Agent juno = rt.juno();
        call(juno, "assign_task", "{\"to\":\"bob\",\"task\":\"first\"}");
        commands.handle(juno, "/approve");
        waitUntil(() -> log(bob).contains("first"));
        waitUntil(() -> bob.status == Agent.Status.DONE);
        settings.system.assignApproval = AssignApproval.NEVER;
        assertTrue(text(call(juno, "assign_task", "{\"to\":\"bob\",\"task\":\"second\"}")).startsWith("Delivered to bob"));
        assertTrue(rt.orchestration().proposals().isEmpty());
    }

    @Test
    void anAgentDeletedDuringTheWindowGetsNothingAndARestartAsksInstead() throws Exception {
        Agent bob = bobNamed(200);
        call(rt.juno(), "assign_task", "{\"to\":\"bob\",\"task\":\"gone\"}");
        rt.delete(bob, false);
        assertTrue(rt.orchestration().proposals().isEmpty());
        Thread.sleep(400); // the timer finds nothing to send
        assertTrue(rt.juno().notices.contains("[Cancelled] The user cancelled the task for bob."));

        Agent jim = rt.create("jim", "tester", null, null);
        rt.orchestration().undoMs = 60_000;
        rt.send(rt.juno(), "give jim the docs", "user");
        waitUntil(() -> rt.juno().status == Agent.Status.DONE);
        call(rt.juno(), "assign_task", "{\"to\":\"jim\",\"task\":\"after restart\"}");
        rt.shutdown();
        runtime();
        Proposal p = rt.orchestration().proposals().get(0);
        assertEquals(0, p.sendAt, "it no longer goes by itself");
        assertTrue(p.askAssign);
        assertNotNull(jim);
    }

    @Test
    void aFreshStartForAnAgentWithNoConversationNeedsNoApproval() throws Exception {
        runtime();
        settings.system.assignApproval = AssignApproval.NEVER;
        Agent bob = rt.create("bob", "tester", null, null);
        assertEquals("Delivered to bob (continuing its conversation). Its result will be reported to you.",
                text(call(rt.juno(), "assign_task", "{\"to\":\"bob\",\"task\":\"t\",\"fresh\":true}")));
        assertTrue(rt.orchestration().proposals().isEmpty());
        waitUntil(() -> bob.status == Agent.Status.DONE);
    }

    @Test
    void theApprovalSettingDecidesWhenJunoAsks() throws Exception {
        runtime();
        Agent juno = rt.juno();
        Agent bob = rt.create("bob", "tester", null, null);
        Agent jim = rt.create("jim", "tester", null, null);
        rt.send(juno, "give bob the docs task", "user");
        waitUntil(() -> juno.status == Agent.Status.DONE);
        settings.system.assignApproval = AssignApproval.ALWAYS;
        assertTrue(text(call(juno, "assign_task", "{\"to\":\"bob\",\"task\":\"t\"}"))
                .contains("the user approves every assignment"));
        assertTrue(text(call(juno, "assign_task", "{\"to\":\"Juno\",\"task\":\"t\"}")).contains("you can't assign tasks to yourself"));
        settings.system.assignApproval = AssignApproval.NEVER;
        assertTrue(text(call(juno, "assign_task", "{\"to\":\"jim\",\"task\":\"t\"}")).startsWith("Delivered to jim"));
        // Choose another / Cancel.
        Proposal p = rt.orchestration().proposals().get(0);
        assertNull(rt.orchestration().approveAssign(p.id, null, jim.id, false));
        assertTrue(juno.notices.contains("[Approved] The user gave the task to jim instead."));
        waitUntil(() -> !jim.pendingTasks.isEmpty() || jim.status == Agent.Status.DONE);
        settings.system.assignApproval = AssignApproval.UNNAMED;
        call(juno, "assign_task", "{\"to\":\"jim\",\"task\":\"unnamed\"}");
        assertNull(rt.orchestration().decline(rt.orchestration().proposals().get(0).id));
        assertTrue(juno.notices.contains("[Cancelled] The user cancelled the task for jim."));
        assertNotNull(bob);
    }

    @Test
    void aTaskForAWorkingAgentWaitsForItsTurnToEnd() throws Exception {
        runtime();
        settings.system.assignApproval = AssignApproval.NEVER;
        Agent bob = rt.create("bob", "tester", null, null);
        bob.turnOpen = true;
        assertEquals("Delivered to bob (continuing its conversation).\nIt's queued, after its current turn. Its result "
                + "will be reported to you.", text(call(rt.juno(), "assign_task", "{\"to\":\"bob\",\"task\":\"later\"}")));
        assertEquals(1, bob.pendingTasks.size());
        bob.turnOpen = false;
        rt.orchestration().turnEnded(rt.live(bob.id), true);
        assertTrue(bob.pendingTasks.isEmpty());
        waitUntil(() -> log(bob).contains("[Task from Juno]\nlater"));
    }

    @Test
    void assignInJunosPaneSpendsNoJunoTokensAndHerNoteWaits() throws Exception {
        runtime();
        Agent juno = rt.juno();
        Agent bob = rt.create("bob", "tester", null, null);
        assertEquals(PaneCommands.Outcome.Sent.class, commands.handle(juno, "/assignbob tidy the readme").getClass());
        waitUntil(() -> log(bob).contains("[Task from Juno, written by the user]\ntidy the readme"));
        assertTrue(bob.task.byUser);
        // The note starts no turn of its own: the report starts Juno's turn and carries it.
        waitUntil(() -> log(juno).contains("[Report from bob"));
        assertTrue(log(juno).startsWith("[Note] The user assigned bob via /assign: tidy the readme. Its result will be "
                + "reported to you.\n\n[Report from bob"), log(juno));
        assertEquals("/assign works only in Juno's pane.", ((PaneCommands.Outcome.Said) commands.handle(bob, "/assignbob x")).text());
    }

    @Test
    void directTasksAreNotedForJunoWhenTheSettingIsOn() throws Exception {
        runtime();
        Agent juno = rt.juno();
        Agent bob = rt.create("bob", "tester", null, null);
        rt.send(bob, "rename the button", "user");
        assertEquals(List.of("[Note] The user gave bob (tester) a task directly: rename the button"), juno.notices);
        waitUntil(() -> bob.status == Agent.Status.DONE);
        settings.system.tellJunoAboutDirectTasks = false;
        rt.send(bob, "and the label", "user");
        assertEquals(1, juno.notices.size());
        waitUntil(() -> bob.status == Agent.Status.DONE);
    }

    // ---------------------------------------------------------------- reports (design §4.3)

    @Test
    void reportsAreCappedAndOnlyForJunosTasks() throws Exception {
        runtime();
        settings.system.reportLength = 100;
        Agent bob = rt.create("bob", "tester", null, null);
        assertEquals("You have no task from Juno, so there's no one to report to. Tell the user in your reply.",
                text(call(bob, "report", "{\"text\":\"x\"}")));
        String longText = "y".repeat(300);
        commands.handle(rt.juno(), "/assignbob CALL report {\"text\":\"" + longText + "\"}");
        waitUntil(() -> replies(bob).contains("report: Reported to Juno, cut to 100 characters."));
        waitUntil(() -> log(rt.juno()).contains("[Report from bob"));
        String report = log(rt.juno()).substring(log(rt.juno()).indexOf("[Report from bob"));
        assertTrue(report.contains("\n" + "y".repeat(100) + "\nFiles changed: none"), "the summary is cut");
    }

    @Test
    void reportsWhileJunoWorksArriveAsOneDigest() throws Exception {
        runtime();
        Agent juno = rt.juno();
        Agent bob = rt.create("bob", "tester", null, null);
        Agent jim = rt.create("jim", "tester", null, null);
        juno.turnOpen = true; // Juno is busy
        commands.handle(juno, "/assignbob CALL report {\"text\":\"bob done\"}");
        commands.handle(juno, "/assignjim CALL report {\"text\":\"jim done\"}");
        waitUntil(() -> juno.pendingReports.size() == 2);
        juno.turnOpen = false;
        synchronized (rt) {
            rt.orchestration().flushReports();
        }
        waitUntil(() -> log(juno).contains("[2 reports arrived while you were busy]"));
        assertTrue(log(juno).contains("bob done") && log(juno).contains("jim done"));
        assertTrue(juno.pendingReports.isEmpty());
        assertNotNull(bob);
        assertNotNull(jim);
    }

    @Test
    void anOpenReviewHoldsTheReportUntilItEnds() throws Exception {
        runtime();
        Profile rev = rt.profile("tester");
        rev.allowedTools.add("mcp__juno__end_review");
        Agent bob = rt.create("bob", "tester", null, null);
        bob.reviewOpen = true;
        commands.handle(rt.juno(), "/assignbob CALL report {\"text\":\"comments posted\"}");
        waitUntil(() -> replies(bob).contains("report: Saved. Juno gets it when your review is finished."));
        waitUntil(() -> bob.heldReport != null);
        assertFalse(log(rt.juno()).contains("[Report from bob"));
        assertEquals("Review ended; snapshot removed.", text(call(bob, "end_review", "{}")));
        waitUntil(() -> log(rt.juno()).contains("comments posted"));
        assertEquals("You have no review open.", text(call(bob, "end_review", "{}")));
    }

    // ---------------------------------------------------------------- context and output (design §8.2)

    @Test
    void contextSummariesOutputStatusAndBroadcast() throws Exception {
        runtime();
        Agent bob = rt.create("bob", "tester", null, null);
        Agent jim = rt.create("jim", "tester", null, null);
        commands.handle(bob, "/cantalktojim");
        rt.send(bob, "look at the code", "user");
        waitUntil(() -> bob.status == Agent.Status.DONE);
        String summary = rt.orchestration().contextSummary(bob);
        assertTrue(summary.startsWith("bob (tester) · Done · context 10 %"), summary);
        assertTrue(summary.contains("Last task: look at the code"));
        assertTrue(summary.contains("Links: jim"));
        assertTrue(summary.contains("1 turns"));
        assertEquals(summary, ((PaneCommands.Outcome.Said) commands.handle(bob, "/getcontext")).text());
        assertEquals(summary, ((PaneCommands.Outcome.Said) commands.handle(rt.juno(), "/getcontextfrombob")).text());
        assertEquals(summary, text(call(rt.juno(), "get_agent_context", "{\"name\":\"bob\"}")));
        assertEquals("/getcontextfrom works only in Juno's pane.", ((PaneCommands.Outcome.Said) commands.handle(bob,
                "/getcontextfromjim")).text());

        String out = text(call(rt.juno(), "get_agent_output", "{\"name\":\"bob\",\"lines\":2}"));
        assertEquals("The last 2 lines of bob's output, newest last:\n  look at the code\nok", out);
        assertEquals("jim has no output yet.", text(call(rt.juno(), "get_agent_output", "{\"name\":\"jim\"}")));

        String status = ((PaneCommands.Outcome.Said) commands.handle(rt.juno(), "/status")).text();
        assertTrue(status.contains("bob · tester · Done · no project · ctx 10 % · no task from Juno"), status);
        assertTrue(status.contains("Links: bob ↔ jim"));

        assertEquals("Sent to 2 agents.", ((PaneCommands.Outcome.Said) commands.handle(rt.juno(), "/broadcast stop for lunch")).text());
        waitUntil(() -> log(jim).contains("[Message from the user to every agent]\nstop for lunch"));
        waitUntil(() -> log(bob).contains("stop for lunch"));
    }

    // ---------------------------------------------------------------- helpers

    private boolean linked(Agent a, Agent b) {
        return rt.orchestration().contacts(a).contains(b);
    }

    private String replies(Agent a) {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : rt.live(a.id).history()) if (e instanceof AgentEvent.Message m) sb.append(m.text()).append('\n');
        return sb.toString();
    }

    private List<String> notices(Agent a) {
        List<String> out = new ArrayList<>();
        for (AgentEvent e : rt.live(a.id).history()) if (e instanceof AgentEvent.Notice n) out.add(n.text());
        return out;
    }

    private String log(Agent a) {
        try {
            Path f = provider.log(a.id);
            return Files.exists(f) ? Files.readString(f) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> toolNames(Agent a) throws Exception {
        JsonNode tools = Json.MAPPER.readTree(post(token(a), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").body())
                .path("result").path("tools");
        List<String> out = new ArrayList<>();
        tools.forEach(t -> out.add(t.path("name").asText()));
        return out;
    }

    private JsonNode call(Agent a, String tool, String args) throws Exception {
        ObjectNode c = Json.MAPPER.createObjectNode();
        c.put("jsonrpc", "2.0");
        c.put("id", 3);
        c.put("method", "tools/call");
        c.putObject("params").put("name", tool).set("arguments", Json.MAPPER.readTree(args));
        return Json.MAPPER.readTree(post(token(a), c.toString()).body()).path("result");
    }

    private static String text(JsonNode result) {
        return result.path("content").get(0).path("text").asText();
    }

    private String token(Agent a) throws Exception {
        var f = AgentRuntime.class.getDeclaredField("mcp");
        f.setAccessible(true);
        return ((JunoMcpServer) f.get(rt)).register(a.id);
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(rt.mcpUrl()))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void waitUntil(BooleanSupplier c) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (!c.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(25);
        assertTrue(c.getAsBoolean(), "timed out");
    }
}
