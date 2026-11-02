package dev.juno.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.comms.CommsStore;
import dev.juno.comms.JunoMcpServer;
import dev.juno.comms.JunoMcpServer.Reply;
import dev.juno.comms.Links;
import dev.juno.comms.LoopGuard;
import dev.juno.comms.Proposal;
import dev.juno.model.Agent;
import dev.juno.model.Agent.Status;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.model.SystemSettings.AssignApproval;
import dev.juno.prompts.Prompts;
import dev.juno.provider.AgentEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Juno and communication between agents (design §4): links, delegation and its approvals, capped reports and digests,
 * the loop guard, context summaries, and the Juno MCP tools besides permissions. Every rule of §4 is checked here,
 * whatever a prompt says. Everything runs under the runtime's lock.
 *
 * <p>What reaches Juno: reports start her turn when she is idle and are batched into one digest while she works;
 * notes (direct tasks, /assign), approval outcomes and link changes are notices sent with her next message, so they
 * never cost a turn of their own. Link announcements and loop-guard notices reach other agents the same way.
 */
public final class Orchestration {
    public static final String JUNO = "juno";
    /** How long a task for an agent the user named can be undone before it goes (design §4.2). */
    static final long UNDO_MS = 15_000L;

    private final AgentRuntime rt;
    private final CommsStore store;
    private final LoopGuard guard = new LoopGuard();
    /** {@link #UNDO_MS}; 0 sends at once (tests). */
    long undoMs = UNDO_MS;

    Orchestration(AgentRuntime rt, CommsStore store) {
        this.rt = rt;
        this.store = store;
    }

    /**
     * After a restart nothing may leave without the chance to undo it, and the time has gone: a task still counting
     * down waits for an answer like any other proposal.
     */
    void started() {
        boolean changed = false;
        for (Proposal p : store.proposals()) {
            if (p.sendAt > 0) {
                p.sendAt = 0;
                p.askAssign = true;
                p.why = "Juno restarted before it was sent";
                changed = true;
            }
        }
        if (changed) store.save();
    }

    public static boolean isJuno(Agent a) {
        return a != null && JUNO.equals(a.id);
    }

    private Agent juno() {
        return rt.juno();
    }

    private int limit(String what) {
        var s = rt.settingsNow().system;
        return what.equals("report") ? Math.max(100, s.reportLength) : Math.max(1, s.hopLimit);
    }

    // ---------------------------------------------------------------- tool access (design §4.4)

    /** Why the agent may not call {@code tool}, or null if it may. */
    String refusal(Agent caller, String tool) {
        if (tool.equals("request_permission")) return null;
        if (JunoMcpServer.ORCHESTRATOR_ONLY.contains(tool) && !isJuno(caller)) {
            return result("common", "orchestrator-only", Map.of("tool", tool));
        }
        Profile p = rt.profile(caller.profile);
        String full = "mcp__juno__" + tool;
        if (p == null || !covers(p.allowedTools, full) || covers(p.disallowedTools, full)) {
            return result("common", "tool-not-allowed", Map.of("tool", tool, "profile", caller.profile));
        }
        return null;
    }

    /** Whether a tool rule list names the Juno tool: exactly, or the whole server ({@code mcp__juno}, {@code mcp__juno__*}). */
    static boolean covers(List<String> rules, String full) {
        for (String r : rules) {
            String x = r.strip();
            if (x.equals(full) || x.equals("mcp__juno") || x.equals("mcp__juno__*")) return true;
        }
        return false;
    }

    Reply call(String agentId, String tool, JsonNode args) {
        synchronized (rt) {
            LiveAgent l = rt.live(agentId);
            if (l == null) return Reply.refused(result("common", "call-failed", Map.of("reason", "this agent no longer exists")));
            Agent me = l.agent;
            return switch (tool) {
                case "list_agents" -> Reply.ok(listAgents(me));
                case "send_message" -> need(args, "to", "text") != null ? missing(args, "to", "text")
                        : sendMessage(me, args.path("to").asText(), args.path("text").asText());
                case "report" -> need(args, "text") != null ? missing(args, "text") : report(me, args.path("text").asText());
                case "request_folder" -> Reply.ok(rt.requestFolder(agentId, args.path("reason").asText("")));
                case "assign_task" -> need(args, "to", "task") != null ? missing(args, "to", "task")
                        : assignTask(args.path("to").asText(), args.path("task").asText(), args.path("fresh").asBoolean(false));
                case "get_agent_output" -> need(args, "name") != null ? missing(args, "name")
                        : output(args.path("name").asText(), args.path("lines").asInt(100));
                case "get_agent_context" -> need(args, "name") != null ? missing(args, "name")
                        : context(args.path("name").asText());
                case "set_link" -> need(args, "a", "b") != null ? missing(args, "a", "b")
                        : setLink(args.path("a").asText(), args.path("b").asText(), args.path("open").asBoolean(true),
                        args.path("requested_by_user").asBoolean(false), args.path("reason").asText(""));
                // TODO(Milestone 5): pipelines, the review snapshot and the review folder (design §6.3).
                case "start_review" -> Reply.refused(result("start_review", "not-yet", Map.of()));
                case "end_review" -> endReview(l);
                default -> Reply.refused(result("common", "unknown-tool", Map.of("tool", tool)));
            };
        }
    }

    private static String need(JsonNode args, String... names) {
        for (String n : names) if (args.path(n).asText("").isBlank()) return n;
        return null;
    }

    private static Reply missing(JsonNode args, String... names) {
        return Reply.refused(result("common", "missing", Map.of("param", need(args, names))));
    }

    // ---------------------------------------------------------------- list_agents, contacts

    /** Who the agent can message: Juno sees everyone; others see Juno and the agents they're linked to (design §4.1). */
    public List<Agent> contacts(Agent a) {
        synchronized (rt) {
            if (isJuno(a)) return rt.agents();
            List<Agent> out = new ArrayList<>();
            out.add(juno());
            for (String id : store.links().of(a.id)) {
                LiveAgent l = rt.live(id);
                if (l != null) out.add(l.agent);
            }
            return out;
        }
    }

    private String listAgents(Agent me) {
        List<Agent> who = contacts(me);
        if (who.isEmpty()) return result("list_agents", "nobody", Map.of());
        StringBuilder rows = new StringBuilder();
        for (Agent x : who) rows.append(result("list_agents", "agent", agentValues(x))).append('\n');
        if (!isJuno(me)) return result("list_agents", "reachable", Map.of("agents", rows.toString().strip()));
        List<String> links = new ArrayList<>();
        for (Links.Link k : store.links().all()) links.add(name(k.a) + " ↔ " + name(k.b));
        return result("list_agents", "everyone", Map.of("agents", rows.toString().strip(), "links", String.join(", ", links)));
    }

    private Map<String, Object> agentValues(Agent x) {
        Project p = rt.project(x);
        Map<String, Object> v = new HashMap<>();
        v.put("name", x.name);
        v.put("profile", x.profile);
        v.put("project", p == null ? "" : p.name);
        v.put("status", x.statusText());
        v.put("context", contextPct(x));
        v.put("colour", x.colour);
        return v;
    }

    /** "12 %", or "unknown" before the first reply tells the window. */
    public String contextPct(Agent x) {
        LiveAgent l = rt.live(x.id);
        return l == null || l.contextWindow <= 0 ? "unknown" : Meters.percent(l.contextTokens, l.contextWindow) + " %";
    }

    /** The preamble's "Who you can talk to" list (design §11): name, profile and, for agents, status. */
    String contactsText(Agent a) {
        StringBuilder sb = new StringBuilder();
        for (Agent x : contacts(a)) {
            sb.append("- ").append(x.name).append(" (`").append(x.profile).append('`');
            if (!isJuno(x)) sb.append(", ").append(x.statusText());
            sb.append(")\n");
        }
        return sb.isEmpty() ? "- (no agents yet)" : sb.toString().strip();
    }

    private String name(String id) {
        LiveAgent l = rt.live(id);
        return l == null ? id : l.agent.name;
    }

    // ---------------------------------------------------------------- send_message, loop guard

    private Reply sendMessage(Agent me, String to, String text) {
        Agent target = rt.byName(to).orElse(null);
        if (target == null) return Reply.refused(result("common", "unknown-agent", Map.of("name", to)));
        if (target == me) return Reply.refused(result("send_message", "self", Map.of()));
        if (!isJuno(me) && !isJuno(target) && !store.links().linked(me.id, target.id)) {
            rt.say(me, "✗ message to " + target.name + " refused: not linked.", "warn");
            return Reply.refused(result("send_message", "not-linked", Map.of("to", target.name)));
        }
        int hop = LoopGuard.nextHop(me.hop, limit("hop"));
        if (hop < 0) {
            if (!guard.isStopped(me.id, target.id)) {
                guard.stop(me.id, target.id);
                String why = "⛔ Messages between " + me.name + " and " + target.name + " stopped: the chain reached "
                        + limit("hop") + " hops without a message from you. A message from you to either resets it.";
                rt.say(me, why, "bad");
                rt.say(target, why, "bad");
                rt.notify(target, wrapper("loop-guard", Map.of("other", me.name, "hop_limit", limit("hop"))));
            }
            return Reply.refused(result("send_message", "hop-limit", Map.of("to", target.name, "hop_limit", limit("hop"))));
        }
        target.hop = Math.max(target.hop, hop);
        rt.inject(target, wrapper("message", Map.of("from", me.name, "from_profile", me.profile, "text", text.strip())),
                "message");
        return Reply.ok(result("send_message", "delivered", Map.of("to", target.name)));
    }

    /** A message from the user to {@code a} (typed, /hey, /broadcast): its chain starts again (design §4.4). */
    void fromUser(Agent a, String text, String from) {
        a.hop = 0;
        for (String other : guard.reset(a.id)) {
            LiveAgent l = rt.live(other);
            if (l != null) l.agent.hop = 0;
        }
        if (!from.equals("user") || isJuno(a)) return;
        // A plain message to an idle agent is a direct task: it takes over from Juno's (design §4.2). To an agent
        // that is asking or was interrupted, it's an answer, and Juno's task goes on.
        if (a.status == Status.IDLE || a.status == Status.DONE) {
            a.task = null;
            a.reportDraft = null;
            if (rt.settingsNow().system.tellJunoAboutDirectTasks) {
                rt.notify(juno(), wrapper("note", "direct-task", Map.of("agent", a.name, "profile", a.profile,
                        "gist", AgentPrompts.firstLine(text, 160))));
            }
        }
    }

    // ---------------------------------------------------------------- reports (design §4.3)

    private Reply report(Agent me, String text) {
        if (isJuno(me) || me.task == null) return Reply.ok(result("report", "no-task", Map.of()));
        String s = text.strip();
        int max = limit("report");
        boolean cut = s.length() > max;
        me.reportDraft = cut ? s.substring(0, max) : s;
        rt.saved(me);
        if (me.reviewOpen) return Reply.ok(result("report", "held-review", Map.of()));
        return Reply.ok(cut ? result("report", "truncated", Map.of("report_length", max)) : result("report", "sent", Map.of()));
    }

    /**
     * The agent's turn ended. For a Juno task: its report (what it passed to {@code report}, or once, its final reply)
     * goes to Juno, held while a review is open. Then the next task waiting for this turn, if any. For Juno: the
     * reports that came in while she worked, as one digest.
     */
    void turnEnded(LiveAgent l, boolean natural) {
        Agent a = l.agent;
        if (isJuno(a)) {
            flushReports();
            return;
        }
        if (natural && a.task != null) {
            String summary = a.reportDraft;
            if (summary == null && !a.task.reported) {
                String reply = l.lastReply == null ? "" : l.lastReply.strip();
                summary = reply.length() > limit("report") ? reply.substring(0, limit("report")) : reply;
            }
            if (summary != null) {
                a.reportDraft = null;
                a.task.reported = true;
                a.lastReport = summary;
                String report = wrapper("report", Map.of("agent", a.name, "profile", a.profile, "status", a.statusText(),
                        "context_pct", contextPct(a).replace(" %", ""), "task_gist", a.task.gist,
                        "summary", summary.isBlank() ? "(no summary)" : summary,
                        "files_changed", String.join(", ", files(l, true, true)).transform(s -> s.isBlank() ? "none" : s)));
                if (a.reviewOpen) a.heldReport = report;
                else toJuno(report, a);
            }
            rt.saved(a);
        }
        if (!a.pendingTasks.isEmpty() && !a.turnOpen) {
            Agent.PendingTask t = a.pendingTasks.remove(0);
            deliverTask(a, t.text, t.fresh, t.byUser);
        }
    }

    private void toJuno(String report, Agent from) {
        Agent j = juno();
        rt.say(j, "📨 Report from " + from.name + " (" + from.statusText() + ").", "info");
        if (!j.turnOpen) {
            rt.send(j, report, "report");
        } else {
            j.pendingReports.add(report);
            rt.saved(j);
        }
    }

    /** Sends the reports that arrived while Juno worked: one as it is, several as a digest. */
    void flushReports() {
        Agent j = juno();
        if (j.turnOpen || j.pendingReports.isEmpty()) return;
        List<String> reports = new ArrayList<>(j.pendingReports);
        j.pendingReports.clear();
        String text = reports.size() == 1 ? reports.get(0)
                : wrapper("digest", Map.of("count", reports.size(), "reports", String.join("\n\n", reports)));
        rt.send(j, text, "report");
    }

    private Reply endReview(LiveAgent l) {
        Agent a = l.agent;
        if (!a.reviewOpen) return Reply.ok(result("end_review", "none", Map.of()));
        a.reviewOpen = false;
        if (a.heldReport != null) {
            String r = a.heldReport;
            a.heldReport = null;
            toJuno(r, a);
        }
        rt.saved(a);
        return Reply.ok(result("end_review", "ended", Map.of()));
    }

    // ---------------------------------------------------------------- delegation (design §4.2)

    private Reply assignTask(String to, String task, boolean fresh) {
        Agent t = rt.byName(to).orElse(null);
        if (t == null) return Reply.refused(result("common", "unknown-agent", Map.of("name", to)));
        if (isJuno(t)) return Reply.refused(result("assign_task", "not-assignable", Map.of("to", t.name)));
        // A fresh start clears a conversation; an agent without one has nothing to clear, so nothing to approve.
        if (fresh && t.sessionId == null) fresh = false;
        AssignApproval mode = rt.settingsNow().system.assignApproval;
        boolean ask = mode == AssignApproval.ALWAYS || (mode == AssignApproval.UNNAMED && !userNamed(t));
        if (ask || fresh) {
            List<String> why = new ArrayList<>();
            if (ask) why.add(result("assign_task", mode == AssignApproval.ALWAYS ? "why-always" : "why-unnamed",
                    Map.of("to", t.name)));
            if (fresh) why.add(result("assign_task", "why-fresh", Map.of()));
            Proposal p = new Proposal();
            p.id = UUID.randomUUID().toString().substring(0, 8);
            p.kind = Proposal.Kind.ASSIGN;
            p.created = System.currentTimeMillis();
            p.agentId = t.id;
            p.task = task.strip();
            p.fresh = fresh;
            p.askAssign = ask;
            p.why = String.join("; ", why);
            addProposal(p, (ask ? "Juno proposes giving " : "Juno wants to start ") + t.name
                    + (ask ? " a task" : " fresh") + ": approve or cancel below.");
            return Reply.ok(result("assign_task", "held", Map.of("why", p.why)));
        }
        // "Named" is only a guess from the words of the message ("don't give it to bob" names bob too), so a task sent
        // on that guess waits a moment, with an Undo. "Never" is the user's own choice: no wait.
        if (mode == AssignApproval.UNNAMED && undoMs > 0) {
            Proposal p = new Proposal();
            p.id = UUID.randomUUID().toString().substring(0, 8);
            p.kind = Proposal.Kind.ASSIGN;
            p.created = System.currentTimeMillis();
            p.agentId = t.id;
            p.task = task.strip();
            p.sendAt = p.created + undoMs;
            addProposal(p, t.name + " gets Juno's task in " + undoMs / 1000 + " s unless you undo it.");
            String id = p.id;
            rt.later(() -> sendIfStillCounting(id), undoMs);
            return Reply.ok(result("assign_task", "undo-window", Map.of("to", t.name, "seconds", undoMs / 1000)));
        }
        String queued = deliverTask(t, task, false, false);
        rt.say(juno(), "→ Task delivered to " + t.name + ".", "dim");
        return Reply.ok(result("assign_task", "delivered", Map.of("to", t.name, "fresh", false, "queued", queued)));
    }

    private void sendIfStillCounting(String id) {
        synchronized (rt) {
            boolean counting = store.proposals().stream().anyMatch(p -> p.id.equals(id) && p.sendAt > 0);
            if (counting) approveAssign(id, null, null, false);
        }
    }

    /** Whether the user's latest message to Juno names the agent (case and spaces ignored, design §8). */
    boolean userNamed(Agent t) {
        String last = null;
        for (AgentEvent e : rt.live(JUNO).history()) {
            if (e instanceof AgentEvent.UserText u && (u.from().equals("user") || u.from().equals("hey"))) last = u.text();
        }
        if (last == null) return false;
        String n = String.join("\\s*", java.util.Arrays.stream(t.name.strip().split("\\s+")).map(Pattern::quote).toList());
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + n + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE
                | Pattern.UNICODE_CASE).matcher(last).find();
    }

    /**
     * Gives the agent the task now, or after its current turn when it's working. Returns where it waits ("" when it
     * started), for Juno's tool result.
     */
    String deliverTask(Agent t, String task, boolean fresh, boolean byUser) {
        if (t.turnOpen || t.status == Status.QUEUED) {
            Agent.PendingTask pt = new Agent.PendingTask();
            pt.text = task;
            pt.fresh = fresh;
            pt.byUser = byUser;
            t.pendingTasks.add(pt);
            rt.saved(t);
            rt.say(t, "A task from " + (byUser ? "you (/assign)" : "Juno") + " starts when this turn ends.", "dim");
            return result("assign_task", "after-turn", Map.of());
        }
        if (fresh && t.sessionId != null) {
            String err = rt.newConversation(t);
            if (err != null) rt.say(t, err, "warn");
            else rt.say(t, "Fresh start for the new task; the previous conversation is in " + t.name + "'s conversations.", "dim");
        }
        Agent.Task task1 = new Agent.Task();
        task1.gist = AgentPrompts.firstLine(task, 160);
        task1.byUser = byUser;
        task1.at = System.currentTimeMillis();
        t.task = task1;
        t.reportDraft = null;
        t.hop = 0;
        for (String other : guard.reset(t.id)) {
            LiveAgent l = rt.live(other);
            if (l != null) l.agent.hop = 0;
        }
        rt.send(t, wrapper("task", Map.of("by_user", byUser, "task", task.strip())), "task");
        if (t.status == Status.QUEUED) {
            return result("assign_task", "place", Map.of("place", Agent.ordinal(Math.max(1, t.queuePlace))));
        }
        return "";
    }

    /** /assign in Juno's pane: delivered by the app, no Juno tokens; she's told in one line (design §8.2). */
    public String assign(String name, String task) {
        synchronized (rt) {
            Agent t = rt.byName(name).orElse(null);
            if (t == null) return "There's no agent called " + name + ".";
            if (isJuno(t)) return "Juno doesn't take tasks; give it to an agent.";
            if (task == null || task.isBlank()) return "Usage: /assign<name> <task>";
            fromUser(t, task, "assign");
            String queued = deliverTask(t, task, false, true);
            rt.notify(juno(), wrapper("note", "assign", Map.of("agent", t.name, "gist", AgentPrompts.firstLine(task, 160))));
            rt.say(juno(), "→ Assigned to " + t.name + (queued.isEmpty() ? "" : " (" + queued + ")")
                    + ". Its report comes back here.", "info");
            return null;
        }
    }

    // ---------------------------------------------------------------- links (design §4.1)

    private Reply setLink(String aName, String bName, boolean open, boolean byUser, String reason) {
        Agent a = rt.byName(aName).orElse(null);
        Agent b = rt.byName(bName).orElse(null);
        if (a == null) return Reply.refused(result("common", "unknown-agent", Map.of("name", aName)));
        if (b == null) return Reply.refused(result("common", "unknown-agent", Map.of("name", bName)));
        if (isJuno(a) || isJuno(b)) return Reply.refused(result("set_link", "juno", Map.of()));
        if (a == b) return Reply.refused(result("set_link", "same", Map.of()));
        if (open == store.links().linked(a.id, b.id)) {
            return Reply.ok(result("set_link", "already", Map.of("a", a.name, "b", b.name, "open", open)));
        }
        if (!open) {
            changeLink(a, b, false, false);
            return Reply.ok(result("set_link", "closed", Map.of("a", a.name, "b", b.name)));
        }
        if (byUser) {
            changeLink(a, b, true, false);
            return Reply.ok(result("set_link", "opened", Map.of("a", a.name, "b", b.name)));
        }
        if (reason == null || reason.isBlank()) return Reply.refused(result("set_link", "no-reason", Map.of()));
        boolean held = store.proposals().stream().anyMatch(p -> p.kind == Proposal.Kind.LINK
                && ((p.a.equals(a.id) && p.b.equals(b.id)) || (p.a.equals(b.id) && p.b.equals(a.id))));
        if (!held) {
            Proposal p = new Proposal();
            p.id = UUID.randomUUID().toString().substring(0, 8);
            p.kind = Proposal.Kind.LINK;
            p.created = System.currentTimeMillis();
            p.a = a.id;
            p.b = b.id;
            p.reason = reason.strip();
            addProposal(p, "Juno suggests linking " + a.name + " ↔ " + b.name + ": link or don't, below.");
        }
        return Reply.ok(result("set_link", "held", Map.of()));
    }

    /** /cantalkto and /stoptalkingto in {@code from}'s pane. Null when done (the change is announced), else why not. */
    public String linkByUser(Agent from, String otherName, boolean open) {
        synchronized (rt) {
            if (isJuno(from)) return "Juno can always talk to everyone; links are between two other agents.";
            Agent other = rt.byName(otherName).orElse(null);
            if (other == null) return "There's no agent called " + otherName + ".";
            if (isJuno(other)) return from.name + " can always talk to Juno.";
            if (other == from) return "An agent can't be linked to itself.";
            if (open == store.links().linked(from.id, other.id)) {
                return from.name + " and " + other.name + " are already " + (open ? "linked." : "unlinked.");
            }
            changeLink(from, other, open, true);
            return null;
        }
    }

    /** Opens or closes a link and announces it in both agents' panes and Juno's, and to the agents (design §4.1). */
    private void changeLink(Agent a, Agent b, boolean open, boolean byUser) {
        if (open) store.links().open(a.id, b.id, byUser ? "user" : "juno");
        else store.links().close(a.id, b.id);
        // An answered suggestion for the same pair is gone either way.
        List<Proposal> answered = store.proposals().stream().filter(p -> p.kind == Proposal.Kind.LINK
                && ((p.a.equals(a.id) && p.b.equals(b.id)) || (p.a.equals(b.id) && p.b.equals(a.id)))).toList();
        store.proposals().removeAll(answered);
        store.save();
        String by = byUser ? "the user" : "Juno";
        String line = (open ? "🔗 " : "✂ ") + a.name + " ↔ " + b.name + (open ? " linked" : " unlinked") + " by "
                + (byUser ? "you" : "Juno") + ".";
        for (Agent[] pair : new Agent[][] {{a, b}, {b, a}}) {
            Agent me = pair[0], other = pair[1];
            rt.notify(me, open
                    ? wrapper("link", "opened", Map.of("other", other.name, "other_profile", other.profile,
                    "other_status", other.statusText(), "by", by))
                    : wrapper("link", "closed", Map.of("other", other.name, "by", by)));
            rt.say(me, line, "info");
        }
        rt.say(juno(), line, "info");
        if (byUser) rt.notify(juno(), wrapper("link", "juno", Map.of("a", a.name, "b", b.name, "open", open, "by", by)));
        if (!answered.isEmpty()) rt.proposalsChanged();
        rt.saved(a);
        rt.saved(b);
    }

    // ---------------------------------------------------------------- proposals (design §4.1, §4.2)

    private void addProposal(Proposal p, String line) {
        store.proposals().add(p);
        store.save();
        Agent j = juno();
        rt.say(j, "⏳ " + line, "warn");
        j.unread = true;
        rt.saved(j);
        rt.proposalsChanged();
    }

    public List<Proposal> proposals() {
        synchronized (rt) {
            return List.copyOf(store.proposals());
        }
    }

    private Proposal take(String id) {
        Proposal p = store.proposals().stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
        if (p != null) {
            store.proposals().remove(p);
            store.save();
            rt.proposalsChanged();
        }
        return p;
    }

    /**
     * Approves an assignment: {@code task} null keeps Juno's text, {@code agentId} null keeps her choice, {@code fresh}
     * false continues the conversation. Null when done, else why not.
     */
    public String approveAssign(String id, String task, String agentId, boolean fresh) {
        synchronized (rt) {
            Proposal p = take(id);
            if (p == null || p.kind != Proposal.Kind.ASSIGN) return "That proposal was already answered.";
            LiveAgent l = rt.live(agentId == null ? p.agentId : agentId);
            if (l == null) {
                rt.notify(juno(), wrapper("approval", "assign-cancelled", Map.of("agent", name(p.agentId))));
                return "That agent no longer exists.";
            }
            Agent t = l.agent;
            if (p.sendAt > 0) { // the undo window ended or Send now: Juno was already told it would go
                String queued = deliverTask(t, p.task, false, false);
                rt.say(juno(), "→ Task delivered to " + t.name + (queued.isEmpty() ? "" : " (" + queued + ")") + ".", "dim");
                return null;
            }
            boolean edited = task != null && !task.strip().equals(p.task.strip());
            String text = task == null || task.isBlank() ? p.task : task.strip();
            String outcome;
            Map<String, Object> v = new HashMap<>(Map.of("agent", t.name, "edited", edited));
            if (!t.id.equals(p.agentId)) outcome = "assign-other";
            else if (p.askAssign) outcome = p.fresh && !fresh ? "fresh-continue" : "assign-approved";
            else outcome = fresh ? "fresh-approved" : "fresh-continue";
            fromUser(t, text, "approval");
            String queued = deliverTask(t, text, fresh, false);
            rt.notify(juno(), wrapper("approval", outcome, v));
            rt.say(juno(), "✓ " + t.name + " got the task" + (fresh ? ", starting fresh" : "") + (edited ? " (edited)" : "")
                    + (queued.isEmpty() ? "" : " — " + queued) + ".", "good");
            return null;
        }
    }

    public String approveLink(String id) {
        synchronized (rt) {
            Proposal p = take(id);
            if (p == null || p.kind != Proposal.Kind.LINK) return "That proposal was already answered.";
            LiveAgent a = rt.live(p.a), b = rt.live(p.b);
            if (a == null || b == null) return "One of those agents no longer exists.";
            if (!store.links().linked(p.a, p.b)) changeLink(a.agent, b.agent, true, false);
            rt.notify(juno(), wrapper("approval", "link-approved", Map.of("a", a.agent.name, "b", b.agent.name)));
            return null;
        }
    }

    /** Cancel (assignment) or Don't link. */
    public String decline(String id) {
        synchronized (rt) {
            Proposal p = take(id);
            if (p == null) return "That proposal was already answered.";
            if (p.kind == Proposal.Kind.LINK) {
                rt.notify(juno(), wrapper("approval", "link-declined", Map.of("a", name(p.a), "b", name(p.b))));
                rt.say(juno(), "✗ Not linked: " + name(p.a) + " ↔ " + name(p.b) + ".", "dim");
            } else {
                rt.notify(juno(), wrapper("approval", p.askAssign || p.sendAt > 0 ? "assign-cancelled" : "fresh-cancelled",
                        Map.of("agent", name(p.agentId))));
                rt.say(juno(), "✗ Cancelled the task for " + name(p.agentId) + ".", "dim");
            }
            return null;
        }
    }

    /** /approve in Juno's pane: the oldest proposal, as proposed. */
    public String approveOldest() {
        Proposal p = proposals().stream().findFirst().orElse(null);
        if (p == null) return "There's nothing to approve.";
        return p.kind == Proposal.Kind.LINK ? approveLink(p.id) : approveAssign(p.id, null, null, p.fresh);
    }

    public String declineOldest() {
        Proposal p = proposals().stream().findFirst().orElse(null);
        return p == null ? "There's nothing to deny." : decline(p.id);
    }

    // ---------------------------------------------------------------- deletion

    /** The agent is being deleted: its links go (the other agents are told), and proposals about it. */
    void deleted(Agent a) {
        for (String other : store.links().remove(a.id)) {
            LiveAgent l = rt.live(other);
            if (l == null) continue;
            rt.notify(l.agent, wrapper("link", "removed", Map.of("other", a.name)));
            rt.say(l.agent, "✂ " + a.name + " was deleted; its link to " + l.agent.name + " is gone.", "dim");
            rt.saved(l.agent);
        }
        List<Proposal> gone = store.proposals().stream().filter(p -> p.involves(a.id)).toList();
        for (Proposal p : gone) {
            if (p.kind == Proposal.Kind.ASSIGN) {
                rt.notify(juno(), wrapper("approval", "assign-cancelled", Map.of("agent", a.name)));
            }
        }
        store.proposals().removeAll(gone);
        store.save();
        guard.forget(a.id);
        if (!gone.isEmpty()) rt.proposalsChanged();
    }

    // ---------------------------------------------------------------- user commands (design §8.2)

    /** /broadcast: the text to every agent, as a message from the user. Returns how many got it. */
    public int broadcast(String text) {
        synchronized (rt) {
            int n = 0;
            for (Agent a : rt.agents()) {
                fromUser(a, text, "broadcast");
                rt.inject(a, wrapper("broadcast", Map.of("text", text.strip())), "broadcast");
                n++;
            }
            return n;
        }
    }

    /** /status: every agent's status, project, current task and context use (no tokens). */
    public String status() {
        synchronized (rt) {
            List<Agent> all = rt.agents();
            if (all.isEmpty()) return "No agents yet: + New agent adds one.";
            StringBuilder sb = new StringBuilder("Agents:\n");
            for (Agent a : all) {
                Project p = rt.project(a);
                sb.append("  ").append(a.name).append(" · ").append(a.profile).append(" · ").append(a.statusText())
                        .append(" · ").append(p == null ? "no project" : p.name).append(" · ctx ").append(contextPct(a))
                        .append(" · ").append(a.task == null ? "no task from Juno" : "task: " + a.task.gist).append('\n');
            }
            List<String> links = new ArrayList<>();
            for (Links.Link k : store.links().all()) links.add(name(k.a) + " ↔ " + name(k.b));
            sb.append(links.isEmpty() ? "No links." : "Links: " + String.join(", ", links));
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- output and context (design §8.2)

    private Reply output(String name, int lines) {
        Agent a = rt.byName(name).orElse(null);
        if (a == null) return Reply.refused(result("common", "unknown-agent", Map.of("name", name)));
        int n = Math.max(1, Math.min(1000, lines <= 0 ? 100 : lines));
        List<String> out = outputLines(a);
        if (out.isEmpty()) return Reply.ok(result("get_agent", "empty", Map.of("name", a.name)));
        List<String> last = out.subList(Math.max(0, out.size() - n), out.size());
        return Reply.ok(result("get_agent", "output", Map.of("count", last.size(), "name", a.name,
                "lines", String.join("\n", last))));
    }

    /** The agent's conversation as plain lines: messages in, replies, tool calls, failed results. */
    List<String> outputLines(Agent a) {
        LiveAgent l = rt.live(a.id);
        Path folder = rt.folder(a);
        List<String> out = new ArrayList<>();
        for (AgentEvent e : l.history()) {
            switch (e) {
                case AgentEvent.UserText u -> {
                    out.add("› " + u.from() + ":");
                    for (String s : u.text().split("\n")) out.add("  " + s);
                }
                case AgentEvent.Message m when m.parent() == null -> out.addAll(List.of(m.text().split("\n")));
                case AgentEvent.ToolUse t when t.parent() == null -> out.add("⚙ " + AgentPrompts.describeTool(t, folder));
                case AgentEvent.ToolResult r when r.parent() == null && r.error() -> out.add("✗ " + AgentPrompts.firstLine(r.text(), 200));
                default -> { }
            }
        }
        return out;
    }

    private Reply context(String name) {
        Agent a = rt.byName(name).orElse(null);
        if (a == null) return Reply.refused(result("common", "unknown-agent", Map.of("name", name)));
        return Reply.ok(contextSummary(a));
    }

    /**
     * The context summary (design §8.2), built from the journal: instant, no tokens, nothing added to a conversation.
     */
    public String contextSummary(Agent a) {
        synchronized (rt) {
            LiveAgent l = rt.live(a.id);
            Project p = rt.project(a);
            Agent.Conversation c = a.conversations.stream().filter(x -> x.id.equals(a.conversationId)).findFirst().orElse(null);
            String task = a.task != null ? a.task.gist : c != null && !c.firstTask.isBlank() ? c.firstTask : "none";
            int turns = 0;
            for (AgentEvent e : l.history()) if (e instanceof AgentEvent.Usage u && u.turnTotal()) turns++;
            List<String> links = isJuno(a) ? List.of("everyone") : store.links().of(a.id).stream().map(this::name).toList();
            String summary = !a.lastReport.isBlank() ? a.lastReport
                    : l.lastReply == null || l.lastReply.isBlank() ? "none" : AgentPrompts.firstLine(l.lastReply, 300);
            Map<String, Object> v = new HashMap<>();
            v.put("name", a.name);
            v.put("profile", a.profile);
            v.put("status", a.statusText());
            v.put("context", contextPct(a));
            v.put("project", p == null ? "none" : p.name);
            v.put("current", a.task != null);
            v.put("task", task);
            v.put("files_read", orNone(files(l, false, false)));
            v.put("files_changed", orNone(files(l, true, false)));
            v.put("links", links.isEmpty() ? "none" : String.join(", ", links));
            v.put("summary", summary);
            v.put("started", c != null);
            v.put("age", c == null ? "" : AgentPrompts.ago(c.started));
            v.put("turns", turns);
            return result("get_agent", "context", v).strip();
        }
    }

    private static String orNone(List<String> files) {
        if (files.isEmpty()) return "none";
        List<String> last = files.subList(Math.max(0, files.size() - 15), files.size());
        return (files.size() > last.size() ? "… " : "") + String.join(", ", last);
    }

    /** Files the conversation changed (diffs) or read; {@code sinceTask}: only since the latest task arrived. */
    private List<String> files(LiveAgent l, boolean changed, boolean sinceTask) {
        Path folder = rt.folder(l.agent);
        Set<String> out = new LinkedHashSet<>();
        for (AgentEvent e : l.history()) {
            switch (e) {
                case AgentEvent.UserText u when sinceTask && u.from().equals("task") -> out.clear();
                case AgentEvent.Diff d when changed -> out.add(AgentPrompts.relative(d.file(), folder));
                case AgentEvent.ToolUse t when !changed && t.name().equals("Read") -> {
                    String f = AgentPrompts.describeTool(t, folder);
                    if (f.startsWith("Read ")) out.add(f.substring(5));
                }
                default -> { }
            }
        }
        return new ArrayList<>(out);
    }

    // ---------------------------------------------------------------- prompts

    private static String result(String file, String key, Map<String, ?> values) {
        return AgentPrompts.result(file, key, values).strip();
    }

    private static String wrapper(String name, Map<String, ?> values) {
        return Prompts.render(Prompts.load("wrappers/" + name + ".md"), values).trim();
    }

    private static String wrapper(String file, String key, Map<String, ?> values) {
        return Prompts.render(Prompts.sections(Prompts.load("wrappers/" + file + ".md")).get(key), values).trim();
    }
}
