package dev.juno.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.comms.JunoMcpServer;
import dev.juno.comms.PermissionRules;
import dev.juno.git.Git;
import dev.juno.git.Worktrees;
import dev.juno.model.Agent;
import dev.juno.model.Agent.Reason;
import dev.juno.model.Agent.Status;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.model.Settings;
import dev.juno.model.SkillPack;
import dev.juno.provider.AgentEvent;
import dev.juno.provider.Provider;
import dev.juno.skills.SkillLibrary;
import dev.juno.skills.SkillResolver;
import dev.juno.store.AgentStore;
import dev.juno.store.AtomicFiles;
import dev.juno.store.Json;
import dev.juno.store.JunoHome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * All agents and their processes (design §3, §7, §12). Status follows the agent's output events, not message counts;
 * the Claude hook's events confirm turn ends when installed. Everything that matters after a crash (session id,
 * status, rules, held messages) is saved as it happens, and every event goes to the conversation's journal.
 *
 * <p>Listeners are called on whichever thread the change happened on.
 */
public final class AgentRuntime implements JunoMcpServer.Calls {
    /** Done returns to Idle after this long (fixed, design §3). */
    static final long DONE_TO_IDLE_MS = 15 * 60_000L;
    /** After the last background task ends, the turn counts as over if nothing else happens for this long. */
    static final long BACKGROUND_SETTLE_MS = 5_000L;
    static final long STOP_GRACE_MS = 10_000L;

    public interface Listener {
        /** The agent's saved state or meters changed. */
        default void changed(Agent a) {}

        /** A new event for the agent's pane. */
        default void event(Agent a, AgentEvent e) {}

        /** Plan limits from a rate-limit event. */
        default void rateLimit(AgentEvent.RateLimit r) {}

        /** The agent list changed (created / removed). */
        default void agentsChanged() {}

        /** The agent's current conversation was replaced (/new, switching to an earlier one): reload its pane. */
        default void conversationChanged(Agent a) {}
    }

    public enum Answer { ALLOW_ONCE, ALLOW_AGENT, ALLOW_PROFILE, DENY }

    private final JunoHome home;
    private final Provider provider;
    private final Supplier<Settings> settings;
    private final Runnable saveSettings;
    private final SkillLibrary skills;
    private final SkillResolver skillResolver;
    private final AgentStore store;
    private final JunoMcpServer mcp;
    private final Map<String, LiveAgent> live = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Provider.VersionCheck> versions = new ConcurrentHashMap<>();
    private final HookEvents hooks;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "juno-runtime-timer");
        t.setDaemon(true);
        return t;
    });
    private final List<LiveAgent> queue = new ArrayList<>();
    private volatile AgentEvent.RateLimit rateLimit;

    public AgentRuntime(JunoHome home, Provider provider, Supplier<Settings> settings, Runnable saveSettings,
                        SkillLibrary skills, SkillResolver skillResolver) {
        this.home = home;
        this.provider = provider;
        this.settings = settings;
        this.saveSettings = saveSettings;
        this.skills = skills;
        this.skillResolver = skillResolver;
        this.store = new AgentStore(home);
        this.mcp = new JunoMcpServer(this);
        this.hooks = new HookEvents(home.hookEventsDir(), this::hookEvent);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Loads the agents, marks those cut off by a crash or shutdown as interrupted (§12), starts the server. */
    public void start() throws IOException {
        for (Agent a : store.loadAll()) {
            LiveAgent l = new LiveAgent(a);
            live.put(a.id, l);
            if (a.conversationId == null) a.conversationId = newConversationId();
            long lastEvent = loadJournal(l);
            boolean queued = a.status == Status.QUEUED && a.queuedMessage != null;
            if (queued) queue.add(l);
            else if (a.status == Status.QUEUED) a.status = Status.IDLE;
            boolean cutOff = a.turnOpen || a.status == Status.WORKING
                    || (a.status == Status.AWAITING && a.reason == Reason.PERMISSION);
            if (cutOff) {
                boolean closed = a.interrupted && a.interruptReason != null && a.interruptReason.startsWith("Juno was closed");
                if (!closed) {
                    // A crash: shutdown() didn't get to record it. The last journalled event is when it stopped.
                    a.interruptReason = "Juno crashed while you were working";
                    a.interruptedAt = lastEvent > 0 ? lastEvent : System.currentTimeMillis();
                }
                a.turnOpen = false;
                a.interrupted = true;
                setStatus(l, Status.AWAITING, Reason.INTERRUPTED, "Interrupted: " + a.interruptReason + ".");
                notice(l, "⏸ Interrupted: " + (closed ? "Juno was closed" : "Juno crashed")
                        + " while " + a.name + " was working. /resume continues with a recap.", "bad");
                a.unread = true;
            }
            Conversations.applyLimits(home, a, System.currentTimeMillis());
            store.save(a);
            refreshBranch(l);
        }
        queue.sort((x, y) -> Long.compare(x.agent.statusSince, y.agent.statusSince));
        mcp.start();
        hooks.start();
        timer.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
        synchronized (this) {
            drainQueue();
        }
    }

    /** Ends every process; agents in the middle of a turn are interrupted (shown on the next start). */
    public void shutdown() {
        for (LiveAgent l : live.values()) {
            if (l.process != null) l.process.kill();
            if (l.agent.turnOpen) {
                l.agent.interrupted = true;
                l.agent.interruptReason = "Juno was closed while you were working";
                l.agent.interruptedAt = System.currentTimeMillis();
            }
            cleanRun(l);
            store.save(l.agent);
        }
        hooks.stop();
        mcp.stop();
        timer.shutdownNow();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public List<Agent> agents() {
        return live.values().stream().map(l -> l.agent).sorted((x, y) -> Long.compare(x.created, y.created)).toList();
    }

    public LiveAgent live(String agentId) {
        return live.get(agentId);
    }

    public Optional<Agent> byName(String name) {
        String n = normalise(name);
        return agents().stream().filter(a -> normalise(a.name).equals(n)).findFirst();
    }

    public AgentEvent.RateLimit rateLimit() {
        return rateLimit;
    }

    /** Names in commands ignore case and spaces (design §8). */
    public static String normalise(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public Agent create(String name, String profile, String projectId, String colour) {
        return create(name, profile, projectId, colour, null, null);
    }

    public Agent create(String name, String profile, String projectId, String colour, String githubKeyId,
                        String gitlabKeyId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the agent a name.");
        if (byName(name).isPresent()) throw new IllegalArgumentException("There's already an agent called " + name + ".");
        if (normalise(name).equals("juno")) throw new IllegalArgumentException("Juno is taken.");
        if (profile(profile) == null) throw new IllegalArgumentException("No profile called " + profile + ".");
        Agent a = new Agent();
        a.id = UUID.randomUUID().toString().substring(0, 8);
        a.name = name.strip();
        a.profile = profile;
        a.projectId = projectId;
        a.githubKeyId = githubKeyId;
        a.gitlabKeyId = gitlabKeyId;
        a.colour = colour == null ? "#6FD6FF" : colour;
        a.created = System.currentTimeMillis();
        a.statusSince = a.created;
        a.conversationId = newConversationId();
        LiveAgent l = new LiveAgent(a);
        l.journal = new Journal(journalFile(a));
        live.put(a.id, l);
        store.save(a);
        listeners.forEach(Listener::agentsChanged);
        return a;
    }

    // ---------------------------------------------------------------- messages

    /** Why a plain message can't be sent right now, or null if it can (design §8). */
    public String refusal(Agent a) {
        LiveAgent l = live.get(a.id);
        if (!l.pendingPermissions().isEmpty()) {
            return a.name + " is waiting for your answer to a permission request: use its buttons, /approve or /deny.";
        }
        if (a.status == Status.QUEUED) {
            return a.name + " is queued (" + a.statusText().substring(a.statusText().indexOf('·') + 2)
                    + ") behind the agents working now. /stop cancels it.";
        }
        if (a.status == Status.WORKING || a.turnOpen) {
            return a.name + " is working. Use /hey" + normalise(a.name) + " <text> to add to its current task, "
                    + "or /stop.";
        }
        return null;
    }

    /** Sends a message the user typed (or a profile command). {@code from}: "user", or a wrapper's name. */
    public synchronized void send(Agent a, String text, String from) {
        LiveAgent l = live.get(a.id);
        Profile p = profile(a.profile);
        if (p != null && p.needsProjectFolder && project(a) == null) {
            a.heldMessage = a.heldMessage == null ? text : a.heldMessage + "\n\n" + text;
            l.folderReason = "The " + a.profile + " profile needs a project folder before it can start.";
            setStatus(l, Status.AWAITING, Reason.FOLDER, "Pick a project to start.");
            notice(l, "Held until " + a.name + " has a project: pick one below.", "warn");
            store.save(a);
            return;
        }
        if (a.pendingSkill != null) {
            text = AgentPrompts.activateSkill(a.pendingSkill, text);
            a.pendingSkill = null;
        }
        if (a.interrupted) {
            a.interrupted = false;
        }
        deliver(l, text, from);
    }

    /** Adds to the current task without interrupting it (/hey): read when the current step ends. */
    public synchronized void hey(Agent a, String text) {
        LiveAgent l = live.get(a.id);
        if (!l.running() || !a.turnOpen) {
            send(a, AgentPrompts.hey(text), "hey");
            return;
        }
        String wrapped = AgentPrompts.hey(text);
        l.process.send(provider.userMessage(wrapped));
        record(l, new AgentEvent.UserText(wrapped, "hey"));
    }

    /** Agents with an open turn, Waiting ones excluded (design §3); Juno never counts. */
    int workingCount() {
        int n = 0;
        for (LiveAgent x : live.values()) if (x.agent.turnOpen && x.agent.status != Status.WAITING) n++;
        return n;
    }

    private int cap() {
        return Math.max(1, settings.get().system.maxAgentsWorking);
    }

    /** The one place a message starts a turn: queued while "max agents working at once" is reached (design §3). */
    private void deliver(LiveAgent l, String text, String from) {
        Agent a = l.agent;
        if (a.status == Status.QUEUED) {
            a.queuedMessage = (a.queuedMessage == null ? "" : a.queuedMessage + "\n\n") + text;
            store.save(a);
            return;
        }
        if (!a.turnOpen && workingCount() >= cap()) {
            a.queuedMessage = text;
            a.queuedFrom = from;
            queue.add(l);
            setStatus(l, Status.QUEUED, null, "");
            notice(l, "Queued: " + workingCount() + " agents are working (the limit is " + cap()
                    + "). It starts when one finishes; /stop cancels it.", "dim");
            numberQueue();
            store.save(a);
            return;
        }
        startTurn(l, text, from);
    }

    private void numberQueue() {
        for (int i = 0; i < queue.size(); i++) {
            Agent a = queue.get(i).agent;
            if (a.queuePlace != i + 1) {
                a.queuePlace = i + 1;
                listeners.forEach(x -> x.changed(a));
            }
        }
    }

    /** Starts queued agents if there is room (this also runs every few seconds). */
    public synchronized void drain() {
        drainQueue();
    }

    /** Makes the agent's worktree now (it is otherwise made with the first message). */
    public synchronized void ensureWorktree(Agent a) throws IOException {
        if (usesWorktree(a)) prepareWorktree(live.get(a.id));
    }

    /** Starts queued agents while there is room. */
    private void drainQueue() {
        while (!queue.isEmpty() && workingCount() < cap()) {
            LiveAgent l = queue.remove(0);
            Agent a = l.agent;
            String text = a.queuedMessage;
            String from = a.queuedFrom;
            a.queuedMessage = null;
            a.queuePlace = 0;
            if (a.status == Status.QUEUED) setStatus(l, Status.IDLE, null, "");
            if (text != null) startTurn(l, text, from);
        }
        numberQueue();
    }

    private void startTurn(LiveAgent l, String text, String from) {
        Agent a = l.agent;
        if (!ensureProcess(l)) return;
        if (a.sessionId == null && a.conversations.stream().noneMatch(c -> c.id.equals(a.conversationId))) {
            Agent.Conversation c = new Agent.Conversation();
            c.id = a.conversationId;
            c.started = System.currentTimeMillis();
            c.firstTask = AgentPrompts.firstLine(text, 200);
            a.conversations.add(c);
        }
        record(l, new AgentEvent.UserText(text, from));
        l.process.send(provider.userMessage(text));
        l.stopRequested = false;
        a.turnOpen = true;
        a.reason = null;
        setStatus(l, Status.WORKING, null, "");
        store.save(a);
    }

    /** Starts the process if needed (resuming the conversation). False if it couldn't start (status says why). */
    private boolean ensureProcess(LiveAgent l) {
        Agent a = l.agent;
        Profile p = profile(a.profile);
        Path folder = folder(a);
        String model = effectiveModel(a, p), effort = effectiveEffort(a, p);
        if (l.running() && !a.turnOpen && (!eq(model, l.launchedModel) || !eq(effort, l.launchedEffort)
                || !eq(folder.toString(), l.launchedFolder))) {
            stopProcess(l);
        }
        if (l.running()) return true;
        String exe = p.executablePath == null || p.executablePath.isBlank()
                ? settings.get().system.effectiveClaudePath() : p.executablePath.trim();
        Provider.VersionCheck v = versions.computeIfAbsent(exe, provider::checkVersion);
        if (!v.ok()) {
            versions.remove(exe);
            fail(l, v.message());
            return false;
        }
        try {
            if (usesWorktree(a)) prepareWorktree(l);
            else Files.createDirectories(folder);
            Path run = home.agentRunDir(a.id);
            Files.createDirectories(run);
            Path sys = run.resolve("system-prompt.md");
            AtomicFiles.writeString(sys, AgentPrompts.systemPrompt(settings.get(), a, p, project(a), folder,
                    usesWorktree(a) ? a.branch : ""));
            l.token = mcp.register(a.id);
            Path mcpFile = run.resolve("mcp.json");
            AtomicFiles.writeString(mcpFile, provider.mcpConfig(mcp.url(), l.token));
            Path plugins = provider.writeSkills(run.resolve("plugins"), skillSets(a.profile));
            List<String> allowed = new ArrayList<>(p.allowedTools);
            Project project = project(a);
            if (p.editsFiles && project != null) {
                for (String cmd : project.buildAndTestCommands) {
                    if (!cmd.isBlank()) allowed.add("Bash(" + cmd.strip() + ":*)");
                }
            }
            Provider.LaunchSpec spec = new Provider.LaunchSpec(exe, folder, model, effort, p.permissionMode,
                    allowed, p.disallowedTools, p.maxTurns, p.spendLimit, sys, mcpFile, plugins, a.sessionId, a.id);
            hooks.reset(a.id);
            l.process = AgentProcess.start(provider, spec, home.logsDir(a.id).resolve(a.conversationId + ".log"),
                    e -> onEvent(l, e));
            l.launchedModel = model;
            l.launchedEffort = effort;
            l.launchedFolder = folder.toString();
            l.background = 0;
            l.lastActivity = System.currentTimeMillis();
            return true;
        } catch (IOException | RuntimeException e) {
            cleanRun(l);
            fail(l, "Couldn't start " + a.name + ": " + e.getMessage());
            return false;
        }
    }

    private void fail(LiveAgent l, String message) {
        l.agent.turnOpen = false;
        notice(l, message, "bad");
        setStatus(l, Status.AWAITING, Reason.FAILED, message);
        l.agent.unread = true;
        store.save(l.agent);
    }

    // ---------------------------------------------------------------- events

    private void onEvent(LiveAgent l, AgentEvent e) {
        Agent a = l.agent;
        l.lastActivity = System.currentTimeMillis();
        synchronized (this) {
            switch (e) {
                case AgentEvent.SessionStarted s -> {
                    if (!s.sessionId().equals(a.sessionId)) {
                        a.sessionId = s.sessionId();
                        a.conversations.stream().filter(c -> c.id.equals(a.conversationId)).findFirst()
                                .ifPresent(c -> c.sessionId = s.sessionId());
                        store.save(a);
                    }
                    return; // not shown
                }
                case AgentEvent.RateLimit r -> {
                    rateLimit = r;
                    listeners.forEach(x -> x.rateLimit(r));
                    return;
                }
                case AgentEvent.Background b -> {
                    int before = l.background;
                    l.background = b.count();
                    if (b.count() == 0 && before > 0) l.backgroundDoneAt = System.currentTimeMillis();
                    if (b.count() > 0 && !a.turnOpen) openTurn(l);
                    return;
                }
                case AgentEvent.Usage u -> usage(l, u);
                case AgentEvent.Result r -> endTurn(l, r);
                case AgentEvent.Exited x -> {
                    exited(l, x);
                    return;
                }
                case AgentEvent.Message m -> {
                    if (m.parent() == null) l.lastReply = m.text();
                    if (!a.turnOpen) openTurn(l);
                }
                case AgentEvent.ToolUse t -> {
                    if (!a.turnOpen) openTurn(l);
                }
                default -> { }
            }
        }
        record(l, e);
    }

    /** Output arriving with no turn open (e.g. after async sub-agents finish) starts one. */
    private void openTurn(LiveAgent l) {
        l.agent.turnOpen = true;
        setStatus(l, Status.WORKING, null, "");
        store.save(l.agent);
    }

    private void usage(LiveAgent l, AgentEvent.Usage u) {
        if (u.turnTotal()) {
            if (u.contextWindow() > 0) l.contextWindow = u.contextWindow();
            l.lastTurnCost = u.costUsd();
            l.conversationCost += u.costUsd();
            l.lastTurnTokens = l.turnTokens;
            l.turnTokens = 0;
        } else {
            l.contextTokens = u.contextTokens();
            l.lastInput = u.input();
            l.lastOutput = u.output();
            l.lastCacheRead = u.cacheRead();
            l.lastCacheWrite = u.cacheWrite();
            long t = u.input() + u.output() + u.cacheRead() + u.cacheWrite();
            l.turnTokens += t;
            l.conversationTokens += t;
        }
        listeners.forEach(x -> x.changed(l.agent));
    }

    private void endTurn(LiveAgent l, AgentEvent.Result r) {
        Agent a = l.agent;
        if (l.background > 0 && !r.error()) return; // still working: background tasks will continue the turn
        closeTurn(l, r);
    }

    private void closeTurn(LiveAgent l, AgentEvent.Result r) {
        Agent a = l.agent;
        a.turnOpen = false;
        if (l.stopRequested) {
            l.stopRequested = false;
            a.interrupted = true;
            a.interruptReason = "you stopped it";
            a.interruptedAt = System.currentTimeMillis();
            setStatus(l, Status.IDLE, null, "");
            notice(l, "Stopped. /resume continues where it left off.", "warn");
        } else if (r != null && r.error()) {
            String why = switch (r.kind()) {
                case "max_turns" -> "Reached the max turns (" + profile(a.profile).maxTurns + " per message).";
                case "budget" -> "Reached the spend limit for this process.";
                default -> r.text() == null || r.text().isBlank() ? "The turn failed." : AgentPrompts.firstLine(r.text(), 300);
            };
            notice(l, why + " /resume tries again.", "bad");
            a.interrupted = true;
            a.interruptReason = why;
            a.interruptedAt = System.currentTimeMillis();
            setStatus(l, Status.AWAITING, Reason.FAILED, why);
        } else if (endsWithQuestion(l.lastReply)) {
            setStatus(l, Status.AWAITING, Reason.QUESTION, "");
        } else if (a.status != Status.AWAITING || a.reason != Reason.FOLDER) {
            setStatus(l, Status.DONE, null, "");
        }
        a.unread = true;
        store.save(a);
        refreshBranch(l);
        if (a.conversationId != null) Conversations.applyLimits(home, a, System.currentTimeMillis());
        if (l.pendingProject != null) {
            String id = l.pendingProject;
            l.pendingProject = null;
            String err = setProject(a, id);
            if (err != null) notice(l, err, "warn");
        }
        if (l.restartAfterTurn) {
            l.restartAfterTurn = false;
            stopProcess(l);
            if (l.afterRestart != null) {
                String text = l.afterRestart;
                l.afterRestart = null;
                deliver(l, text, "juno");
            }
        }
        drainQueue();
    }

    /** The final reply's last paragraph starts with ❓ (docs/progress.md, Milestone 0 decision 1). */
    public static boolean endsWithQuestion(String reply) {
        if (reply == null || reply.isBlank()) return false;
        String[] paras = reply.strip().split("\n\\s*\n");
        return paras[paras.length - 1].strip().startsWith("❓");
    }

    private void exited(LiveAgent l, AgentEvent.Exited x) {
        Agent a = l.agent;
        boolean expected = "stopped".equals(x.detail()) || l.process == null;
        cancelPermissions(l, "the process ended");
        cleanRun(l);
        l.process = null;
        l.background = 0;
        if (!expected && a.turnOpen) {
            String why = x.detail() == null || x.detail().isBlank() ? "the process ended unexpectedly (exit "
                    + x.code() + ")" : launchError(x.detail());
            a.turnOpen = false;
            a.interrupted = true;
            a.interruptReason = why;
            a.interruptedAt = System.currentTimeMillis();
            record(l, new AgentEvent.Notice("Interrupted: " + why + ". /resume continues.", "bad"));
            setStatus(l, Status.AWAITING, Reason.INTERRUPTED, why);
            a.unread = true;
            store.save(a);
            drainQueue();
        } else if (!expected && x.code() != 0) {
            record(l, new AgentEvent.Notice("The process ended (exit " + x.code() + ")"
                    + (x.detail().isBlank() ? "." : ": " + AgentPrompts.firstLine(x.detail(), 300)), "warn"));
        }
        listeners.forEach(z -> z.changed(a));
    }

    /** "if a launch fails on an unknown flag, the error names the flag and the version" (design §10). */
    private String launchError(String stderr) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("unknown option '([^']+)'").matcher(stderr);
        if (m.find()) {
            String version = versions.values().stream().map(Provider.VersionCheck::version).findFirst().orElse("?");
            return "Claude Code " + version + " doesn't know the flag " + m.group(1);
        }
        return AgentPrompts.firstLine(stderr, 300);
    }

    // ---------------------------------------------------------------- commands

    /** /stop: interrupts the current turn; the conversation is kept. */
    public synchronized String stop(Agent a) {
        LiveAgent l = live.get(a.id);
        if (a.status == Status.QUEUED) {
            queue.remove(l);
            a.queuedMessage = null;
            a.queuePlace = 0;
            setStatus(l, Status.IDLE, null, "");
            store.save(a);
            numberQueue();
            return "Dropped the queued message.";
        }
        if (a.status == Status.AWAITING && a.reason == Reason.FOLDER && a.heldMessage != null) {
            a.heldMessage = null;
            setStatus(l, Status.IDLE, null, "");
            store.save(a);
            return "Dropped the held message.";
        }
        if (!l.running() || !a.turnOpen) return a.name + " isn't working.";
        l.stopRequested = true;
        cancelPermissions(l, "the user stopped you");
        l.process.send(provider.interrupt("stop-" + System.currentTimeMillis()));
        AgentProcess p = l.process;
        timer.schedule(() -> {
            synchronized (this) {
                if (l.process == p && a.turnOpen && l.stopRequested) {
                    p.kill();
                    closeTurn(l, null);
                }
            }
        }, STOP_GRACE_MS, TimeUnit.MILLISECONDS);
        return null;
    }

    /** /resume: continues an interrupted or stopped agent, with a recap of what it had done (design §12). */
    public synchronized String resume(Agent a) {
        LiveAgent l = live.get(a.id);
        if (a.turnOpen) return a.name + " is working.";
        boolean failed = a.status == Status.AWAITING && a.reason == Reason.FAILED;
        if (!a.interrupted && !failed) return "There's nothing to resume: " + a.name + " wasn't interrupted.";
        String recap = AgentPrompts.recap(a, l.history(), changes(a), folder(a));
        a.interrupted = false;
        deliver(l, recap, "recap");
        return null;
    }

    /** /new: the next message starts a new conversation; this one is kept in the list (design §7.9). */
    public synchronized String newConversation(Agent a) {
        LiveAgent l = live.get(a.id);
        if (a.turnOpen) return "Not while " + a.name + " is working.";
        if (a.status == Status.QUEUED) return a.name + " is queued: /stop first.";
        stopProcess(l);
        closeCurrentConversation(l);
        a.sessionId = null;
        a.conversationId = newConversationId();
        a.interrupted = false;
        loadJournal(l);
        setStatus(l, Status.IDLE, null, "");
        Conversations.applyLimits(home, a, System.currentTimeMillis());
        store.save(a);
        listeners.forEach(x -> x.conversationChanged(a));
        return null;
    }

    /** Notes the current conversation's end in the list; an empty one (never started) just disappears. */
    private void closeCurrentConversation(LiveAgent l) {
        Agent a = l.agent;
        Agent.Conversation c = a.conversations.stream().filter(x -> x.id.equals(a.conversationId)).findFirst().orElse(null);
        if (c != null) {
            c.ended = System.currentTimeMillis();
            c.tokens = l.conversationTokens;
            c.summary = AgentPrompts.firstLine(l.lastReply == null ? "" : l.lastReply, 200);
        } else {
            l.journal.close();
            Conversations.deleteFiles(home, a.id, a.conversationId);
        }
    }

    /** Switches back to an earlier conversation (resumed by id); the current one takes its place in the list. */
    public synchronized String switchConversation(Agent a, String conversationId) {
        LiveAgent l = live.get(a.id);
        if (a.turnOpen) return "Not while " + a.name + " is working.";
        if (a.status == Status.QUEUED) return a.name + " is queued: /stop first.";
        Agent.Conversation c = a.conversations.stream().filter(x -> x.id.equals(conversationId)).findFirst().orElse(null);
        if (c == null || c.id.equals(a.conversationId)) return "That conversation is already the current one.";
        if (!Conversations.available(provider.configFolder(), c)) {
            return "Claude Code no longer has that conversation, so it can't be resumed.";
        }
        stopProcess(l);
        closeCurrentConversation(l);
        a.sessionId = c.sessionId;
        a.conversationId = c.id;
        c.ended = 0;
        a.interrupted = false;
        loadJournal(l);
        setStatus(l, Status.IDLE, null, "");
        Conversations.applyLimits(home, a, System.currentTimeMillis());
        store.save(a);
        listeners.forEach(x -> x.conversationChanged(a));
        return null;
    }

    /** Opens the current conversation's journal and rebuilds history and meters from it; returns the last event's time. */
    private long loadJournal(LiveAgent l) {
        if (l.journal != null) l.journal.close();
        l.journal = new Journal(journalFile(l.agent));
        synchronized (l.history) {
            l.history.clear();
        }
        l.contextTokens = l.conversationTokens = l.lastTurnTokens = l.turnTokens = 0;
        l.conversationCost = l.lastTurnCost = 0;
        l.lastReply = "";
        long last = 0;
        for (Journal.Entry e : Journal.read(l.journal.file())) {
            replay(l, e.event());
            last = e.at();
        }
        return last;
    }

    public synchronized String compact(Agent a) {
        if (a.turnOpen) return "Not while " + a.name + " is working.";
        if (a.sessionId == null) return "There's no conversation to compact yet.";
        deliver(live.get(a.id), provider.compactMessage(), "compact");
        return null;
    }

    public synchronized void setModel(Agent a, String model) {
        a.modelOverride = model == null ? "" : model.strip();
        store.save(a);
        listeners.forEach(x -> x.changed(a));
    }

    public synchronized void setEffort(Agent a, String effort) {
        a.effortOverride = effort == null ? "" : effort.strip();
        store.save(a);
        listeners.forEach(x -> x.changed(a));
    }

    /** Toggles a skill for the next message (/activate). Returns what the pane says. */
    public synchronized String activate(Agent a, String query) {
        if (a.turnOpen) return "Not while " + a.name + " is working.";
        String q = normalise(query).replace("-", "");
        Settings s = settings.get();
        String found = null;
        for (SkillPack pack : s.skillPacks) {
            List<String> enabled = skillResolver.enabledIn(pack, a.profile);
            if (enabled.isEmpty()) continue;
            if (normalise(pack.id).replace("-", "").equals(q) || normalise(pack.name).replace("-", "").equals(q)) {
                String entry = enabled.contains(pack.entrySkill) ? pack.entrySkill : enabled.get(0);
                found = provider.skillName(pack.id, entry);
                break;
            }
            for (String sk : enabled) {
                if (normalise(sk).replace("-", "").equals(q)) {
                    found = provider.skillName(pack.id, sk);
                    break;
                }
            }
            if (found != null) break;
        }
        if (found == null) return a.name + " has no skill called " + query + ". Its skills: " + String.join(", ", skillNames(a));
        if (found.equals(a.pendingSkill)) {
            a.pendingSkill = null;
            store.save(a);
            listeners.forEach(x -> x.changed(a));
            return "Cancelled: " + found + " won't be loaded.";
        }
        a.pendingSkill = found;
        store.save(a);
        listeners.forEach(x -> x.changed(a));
        return "Queued: " + a.name + " loads " + found + " with its next message. Repeat to cancel.";
    }

    /** Every skill the agent has, as it names them. */
    public List<String> skillNames(Agent a) {
        List<String> out = new ArrayList<>();
        for (SkillPack pack : settings.get().skillPacks) {
            for (String sk : skillResolver.enabledIn(pack, a.profile)) out.add(provider.skillName(pack.id, sk));
        }
        return out;
    }

    /** Pack ids and skill names usable with /activate. */
    public List<String> activateChoices(Agent a) {
        List<String> out = new ArrayList<>();
        for (SkillPack pack : settings.get().skillPacks) {
            List<String> enabled = skillResolver.enabledIn(pack, a.profile);
            if (enabled.isEmpty()) continue;
            out.add(pack.id);
            out.addAll(enabled);
        }
        return out;
    }

    // ---------------------------------------------------------------- folders (design §7.7)

    @Override
    public String requestFolder(String agentId, String reason) {
        LiveAgent l = live.get(agentId);
        if (l == null) return AgentPrompts.result("common", "call-failed", Map.of("reason", "this agent no longer exists"));
        synchronized (this) {
            l.folderReason = reason == null || reason.isBlank() ? "It needs a project." : reason;
            setStatus(l, Status.AWAITING, Reason.FOLDER, l.folderReason);
            l.agent.unread = true;
            notice(l, "📁 " + l.agent.name + " asks for a project: " + l.folderReason, "warn");
            store.save(l.agent);
        }
        return AgentPrompts.result("request_folder", "asked", Map.of());
    }

    /** Gives the agent a project: restarts its process in it (conversation kept) and tells it, or sends the held message. */
    public synchronized String setProject(Agent a, String projectId) {
        LiveAgent l = live.get(a.id);
        Project p = settings.get().projects.stream().filter(x -> x.id.equals(projectId)).findFirst().orElse(null);
        if (p == null) return "That project doesn't exist.";
        if (p.id.equals(a.projectId)) return null;
        if (usesWorktree(a) && Files.isDirectory(home.worktree(a.id))) {
            // The agent has one worktree, of its old project: it goes (its branch stays) if no work is lost with it.
            String unsafe = Worktrees.unsafeToRemove(home.worktree(a.id));
            if (unsafe != null) {
                return a.name + "'s worktree of " + project(a).name + " still has " + unsafe + ". Commit and push it "
                        + "first (or ask " + a.name + " to).";
            }
            stopProcess(l);
            try {
                Worktrees.remove(Path.of(project(a).folder), home.worktree(a.id));
            } catch (IOException e) {
                return "Couldn't remove " + a.name + "'s old worktree: " + e.getMessage();
            }
            a.branch = "";
            a.baseBranch = "";
        }
        a.projectId = p.id;
        if (!usesWorktree(a)) a.branch = "";
        refreshBranch(l);
        l.folderReason = null;
        notice(l, "📁 Project set: " + p.name + ".", "good");
        String text;
        if (a.heldMessage != null) {
            text = a.heldMessage;
            a.heldMessage = null;
        } else {
            text = a.sessionId != null ? AgentPrompts.folderSet(p, folder(a), usesWorktree(a) ? a.branch : "") : null;
        }
        if (a.status == Status.AWAITING && a.reason == Reason.FOLDER) setStatus(l, Status.IDLE, null, "");
        store.save(a);
        if (a.turnOpen) {
            l.restartAfterTurn = true;
            l.afterRestart = text;
        } else {
            stopProcess(l);
            if (text != null) send(a, text, a.sessionId == null ? "user" : "juno");
        }
        return null;
    }

    /** The options menu's project change: waits until the agent isn't working (design §7.10). */
    public synchronized String requestProject(Agent a, String projectId) {
        LiveAgent l = live.get(a.id);
        if (a.turnOpen || a.status == Status.QUEUED) {
            l.pendingProject = projectId;
            return "The project changes when " + a.name + " isn't working any more.";
        }
        return setProject(a, projectId);
    }

    public synchronized String pendingProject(Agent a) {
        return live.get(a.id).pendingProject;
    }

    // ---------------------------------------------------------------- permissions (design §7.5)

    @Override
    public ObjectNode requestPermission(String agentId, String tool, JsonNode input, String toolUseId) {
        LiveAgent l = live.get(agentId);
        if (l == null) return deny(AgentPrompts.result("common", "call-failed", Map.of("reason", "this agent no longer exists")));
        Agent a = l.agent;
        Profile p = profile(a.profile);
        Path folder = folder(a);
        String refused = PermissionRules.refusedBy(p.disallowedTools, tool, input, folder);
        if (refused != null) {
            notice(l, "✗ " + tool + " refused: `" + refused + "` is disallowed for " + a.profile + ".", "bad");
            return deny(AgentPrompts.result("request_permission", "disallowed",
                    Map.of("rule", refused, "profile", a.profile)));
        }
        for (Agent.AgentRule r : a.rules) {
            if (PermissionRules.allows(List.of(r.rule), tool, input, folder)) {
                notice(l, "✓ allowed by rule " + r.rule, "dim");
                return allow(input);
            }
        }
        PermissionRules.Proposal proposal = PermissionRules.propose(tool, input, folder);
        String id = toolUseId == null || toolUseId.isBlank() ? UUID.randomUUID().toString() : toolUseId;
        LiveAgent.PendingPermission pending = new LiveAgent.PendingPermission(id, tool, input, proposal.rule(),
                proposal.description());
        synchronized (this) {
            synchronized (l.permissions) {
                l.permissions.put(id, pending);
            }
            record(l, new AgentEvent.Permission(id, tool, input.toString(), proposal.rule(), proposal.description(), null));
            setStatus(l, Status.AWAITING, Reason.PERMISSION, tool);
            a.unread = true;
            store.save(a);
        }
        try {
            return pending.answer.get();
        } catch (Exception e) {
            return deny(AgentPrompts.result("request_permission", "denied", Map.of()));
        } finally {
            synchronized (l.permissions) {
                l.permissions.remove(id);
            }
        }
    }

    /** Answers a pending permission request. {@code rule} is the (edited) rule for the "always" answers. */
    public synchronized String answer(Agent a, String requestId, Answer answer, String rule) {
        LiveAgent l = live.get(a.id);
        LiveAgent.PendingPermission pp;
        synchronized (l.permissions) {
            pp = requestId == null ? l.permissions.values().stream().findFirst().orElse(null)
                    : l.permissions.get(requestId);
        }
        if (pp == null) return "There's no permission request waiting.";
        Profile p = profile(a.profile);
        String r = rule == null || rule.isBlank() ? pp.proposedRule : rule.strip();
        if ((answer == Answer.ALLOW_AGENT || answer == Answer.ALLOW_PROFILE) && !PermissionRules.valid(r)) {
            return "`" + r + "` isn't a valid rule.";
        }
        if (answer != Answer.DENY) {
            String refused = PermissionRules.refusedBy(p.disallowedTools, pp.tool, pp.input, folder(a));
            if (refused != null) answer = Answer.DENY;
        }
        String outcome;
        switch (answer) {
            case ALLOW_ONCE -> outcome = "allowed once";
            case ALLOW_AGENT -> {
                a.rules.add(new Agent.AgentRule(r, PermissionRules.describe(r), AgentPrompts.firstLine(
                        pp.tool + " " + pp.input.toString(), 160)));
                outcome = "always allowed for " + a.name + ": " + r;
            }
            case ALLOW_PROFILE -> {
                if (p.builtIn) return "Built-in profiles can't be changed; duplicate it in Settings instead.";
                if (!p.allowedTools.contains(r)) p.allowedTools.add(r);
                saveSettings.run();
                outcome = "always allowed for the " + p.name + " profile (from its agents' next start): " + r;
            }
            default -> outcome = "denied";
        }
        record(l, new AgentEvent.Permission(pp.id, pp.tool, pp.input.toString(), r, PermissionRules.describe(r), outcome));
        pp.answer.complete(answer == Answer.DENY
                ? deny(AgentPrompts.result("request_permission", "denied", Map.of())) : allow(pp.input));
        if (l.pendingPermissions().size() <= 1) setStatus(l, Status.WORKING, null, "");
        store.save(a);
        return null;
    }

    private void cancelPermissions(LiveAgent l, String why) {
        for (LiveAgent.PendingPermission pp : l.pendingPermissions()) {
            record(l, new AgentEvent.Permission(pp.id, pp.tool, pp.input.toString(), pp.proposedRule,
                    pp.proposedDescription, "cancelled"));
            pp.answer.complete(deny(AgentPrompts.result("request_permission", "cancelled", Map.of("reason", why))));
        }
    }

    private static ObjectNode allow(JsonNode input) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("behavior", "allow");
        o.set("updatedInput", input);
        return o;
    }

    private static ObjectNode deny(String message) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("behavior", "deny");
        o.put("message", message);
        return o;
    }

    // ---------------------------------------------------------------- options menu, deletion, storage

    /** Renames an agent (design §7.10); null if done, else why not. */
    public synchronized String rename(Agent a, String name) {
        if (name == null || name.isBlank()) return "Give the agent a name.";
        if (normalise(name).equals("juno")) return "Juno is taken.";
        Optional<Agent> other = byName(name);
        if (other.isPresent() && other.get() != a) return "There's already an agent called " + name.strip() + ".";
        a.name = name.strip();
        saved(a);
        return null;
    }

    public synchronized void setColour(Agent a, String colour) {
        a.colour = colour;
        saved(a);
    }

    /** The GitHub/GitLab key; used from the agent's next start (Milestone 5 wires it in). */
    public synchronized void setKey(Agent a, dev.juno.model.Key.Kind kind, String keyId) {
        String id = keyId == null || keyId.isBlank() ? null : keyId;
        if (kind == dev.juno.model.Key.Kind.GITHUB) a.githubKeyId = id;
        else a.gitlabKeyId = id;
        saved(a);
    }

    public synchronized void removeRule(Agent a, Agent.AgentRule rule) {
        a.rules.remove(rule);
        saved(a);
    }

    /** What deleting the agent would lose: its worktree's uncommitted or unpushed work, if any (design §7.9). */
    public String deleteWarning(Agent a) {
        Path wt = home.worktree(a.id);
        if (!Files.isDirectory(wt)) return null;
        String unsafe = Worktrees.unsafeToRemove(wt);
        return unsafe == null ? null : a.name + "'s worktree still has " + unsafe + ". Deleting the agent removes the "
                + "worktree (its branch " + (a.branch.isBlank() ? "" : a.branch + " ") + "is kept, but uncommitted "
                + "changes are lost).";
    }

    /** Claude Code transcripts of all the agent's conversations, which deletion may remove too. */
    public List<Path> transcriptsOf(Agent a) {
        List<Path> out = new ArrayList<>();
        List<String> sessions = new ArrayList<>();
        if (a.sessionId != null) sessions.add(a.sessionId);
        for (Agent.Conversation c : a.conversations) {
            if (c.sessionId != null && !sessions.contains(c.sessionId)) sessions.add(c.sessionId);
        }
        for (String sid : sessions) out.addAll(Conversations.transcripts(provider.configFolder(), sid));
        return out;
    }

    /**
     * Deletes the agent and everything Juno made for it: process, worktree (its branch stays), hooks, journals, logs,
     * conversation list, per-agent rules. Claude Code's transcripts only if {@code transcripts}.
     */
    public synchronized String delete(Agent a, boolean transcripts) {
        LiveAgent l = live.get(a.id);
        if (l == null) return null;
        List<Path> theirs = transcripts ? transcriptsOf(a) : List.of();
        cancelPermissions(l, "the agent was deleted");
        queue.remove(l);
        if (l.process != null) {
            AgentProcess p = l.process;
            l.process = null;
            p.kill();
        }
        cleanRun(l);
        Path wt = home.worktree(a.id);
        Project project = project(a);
        String problem = null;
        try {
            if (Files.isDirectory(wt) && project != null) Worktrees.remove(Path.of(project.folder), wt);
            else if (Files.isDirectory(wt)) deleteTree(wt);
            if (Files.isDirectory(home.worktreeHooks(a.id))) deleteTree(home.worktreeHooks(a.id));
            if (Files.isDirectory(home.reviewsDir(a.id))) deleteTree(home.reviewsDir(a.id));
            l.journal.close();
            if (Files.isDirectory(home.agentDir(a.id))) deleteTree(home.agentDir(a.id));
            Files.deleteIfExists(home.hookEventsDir().resolve(a.id + ".jsonl"));
            for (Path t : theirs) {
                if (Files.isDirectory(t)) deleteTree(t);
                else Files.deleteIfExists(t);
            }
        } catch (IOException e) {
            problem = "Deleted " + a.name + ", but couldn't remove everything: " + e.getMessage();
        }
        live.remove(a.id);
        numberQueue();
        listeners.forEach(Listener::agentsChanged);
        drainQueue();
        return problem;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true);
                Files.deleteIfExists(p);
            }
        }
    }

    /** Space used by one agent (design §5.2 Storage). */
    public record AgentStorage(Agent agent, boolean hasWorktree, long worktree, long snapshots, long current,
                               long earlier, boolean canRemove, String why, boolean stale) {
        public long total() {
            return worktree + snapshots + current + earlier;
        }
    }

    /** Worktrees untouched this long are flagged (design §5.2). */
    public static final long STALE_MS = 14L * 86_400_000L;

    /** Sizes and what can be removed, per agent. Walks folders: call off the UI thread. */
    public List<AgentStorage> storage() {
        List<AgentStorage> out = new ArrayList<>();
        for (Agent a : agents()) {
            Path wt = home.worktree(a.id);
            boolean has = Files.isDirectory(wt);
            long earlier = 0, current = 0;
            for (Agent.Conversation c : List.copyOf(a.conversations)) {
                long sz = Conversations.size(home, a.id, c.id);
                if (c.id.equals(a.conversationId)) current += sz;
                else earlier += sz;
            }
            if (a.conversationId != null && a.conversations.stream().noneMatch(c -> c.id.equals(a.conversationId))) {
                current += Conversations.size(home, a.id, a.conversationId);
            }
            String why = has ? removalBlock(a) : null;
            boolean stale = has && System.currentTimeMillis() - lastTouched(a) > STALE_MS;
            out.add(new AgentStorage(a, has, has ? Worktrees.size(wt) : 0, Worktrees.size(home.reviewsDir(a.id)),
                    current, earlier, has && why == null, why, stale));
        }
        return out;
    }

    private static long lastTouched(Agent a) {
        return Math.max(a.statusSince, Math.max(a.interruptedAt, a.created));
    }

    /** Why the worktree can't be removed now, or null if it can: idle only, and nothing uncommitted or unpushed. */
    private String removalBlock(Agent a) {
        if (a.turnOpen || a.status == Status.WORKING || a.status == Status.QUEUED || a.status == Status.WAITING
                || (a.status == Status.AWAITING && a.reason != Reason.QUESTION && a.reason != Reason.FAILED
                && a.reason != Reason.INTERRUPTED)) {
            return a.statusText().toLowerCase(Locale.ROOT) + ", not idle";
        }
        return Worktrees.unsafeToRemove(home.worktree(a.id));
    }

    /** Remove worktree (design §5.2): idle agents with nothing uncommitted or unpushed; recreated on the next start. */
    public synchronized String removeWorktree(Agent a) {
        LiveAgent l = live.get(a.id);
        Path wt = home.worktree(a.id);
        if (!Files.isDirectory(wt)) return a.name + " has no worktree.";
        String why = removalBlock(a);
        if (why != null) return "Can't remove " + a.name + "'s worktree: " + why + ".";
        Project project = project(a);
        if (project == null) return "Can't remove " + a.name + "'s worktree: its project is gone.";
        String branch = Worktrees.currentBranch(wt);
        if (!branch.isEmpty()) a.branch = branch;
        stopProcess(l);
        try {
            Worktrees.remove(Path.of(project.folder), wt);
        } catch (IOException e) {
            return "Couldn't remove the worktree: " + e.getMessage();
        }
        notice(l, "Worktree removed to save space; it comes back on " + (a.branch.isBlank() ? "a new branch" : a.branch)
                + " with the next message.", "dim");
        store.save(a);
        return null;
    }

    /** Clean up old data (design §5.2): applies the §7.9 limits to every agent now; returns how many were dropped. */
    public synchronized int cleanUp() {
        int n = 0;
        for (LiveAgent l : live.values()) {
            n += Conversations.applyLimits(home, l.agent, System.currentTimeMillis()).size();
            store.save(l.agent);
        }
        return n;
    }

    // ---------------------------------------------------------------- hook, timers

    /** Claude hook events (when installed): {@code Stop} confirms a turn ended; output events stay primary. */
    private void hookEvent(String agentId, JsonNode e) {
        LiveAgent l = live.get(agentId);
        if (l == null) return;
        String name = e.path("hook_event_name").asText();
        synchronized (this) {
            if (name.equals("Stop") && l.agent.turnOpen && l.background == 0 && l.pendingPermissions().isEmpty()) {
                // The stream's result normally comes first; this covers a result line that never arrives.
                timer.schedule(() -> {
                    synchronized (this) {
                        if (l.agent.turnOpen && l.background == 0 && l.pendingPermissions().isEmpty()
                                && System.currentTimeMillis() - l.lastActivity > 2_000) closeTurn(l, null);
                    }
                }, 3, TimeUnit.SECONDS);
            } else if (name.equals("Notification") && l.agent.turnOpen && l.pendingPermissions().isEmpty()
                    && e.path("message").asText().toLowerCase(Locale.ROOT).contains("permission")) {
                setStatus(l, Status.AWAITING, Reason.PERMISSION, e.path("message").asText());
            }
        }
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();
            long idleLimit = Math.max(1, settings.get().system.idleTimeoutMinutes) * 60_000L;
            synchronized (this) {
                drainQueue();
                for (LiveAgent l : live.values()) {
                    Agent a = l.agent;
                    if (a.status == Status.DONE && now - a.statusSince > DONE_TO_IDLE_MS) {
                        setStatus(l, Status.IDLE, null, ""); // unread survives (design §3)
                        store.save(a);
                    }
                    if (a.turnOpen && l.background == 0 && l.backgroundDoneAt > 0
                            && now - l.backgroundDoneAt > BACKGROUND_SETTLE_MS && now - l.lastActivity > BACKGROUND_SETTLE_MS
                            && l.pendingPermissions().isEmpty()) {
                        l.backgroundDoneAt = 0;
                        closeTurn(l, null);
                    }
                    if (l.running() && !a.turnOpen && l.background == 0 && now - l.lastActivity > idleLimit) {
                        notice(l, "Process closed after " + settings.get().system.idleTimeoutMinutes
                                + " idle minutes; the next message resumes it.", "dim");
                        stopProcess(l);
                    }
                }
            }
        } catch (RuntimeException e) {
            System.err.println("Runtime tick failed: " + e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void stopProcess(LiveAgent l) {
        if (l.process != null) {
            AgentProcess p = l.process;
            l.process = null;
            p.close();
        }
        cleanRun(l);
    }

    /** The per-process files hold the MCP token: deleted when the process ends (design §12). */
    private void cleanRun(LiveAgent l) {
        mcp.unregister(l.token);
        l.token = null;
        Path run = home.agentRunDir(l.agent.id);
        try {
            if (Files.exists(run)) {
                try (var walk = Files.walk(run)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            System.err.println("Couldn't clean " + run + ": " + e.getMessage());
        }
    }

    private void setStatus(LiveAgent l, Status s, Reason r, String detail) {
        Agent a = l.agent;
        if (a.status != s || a.reason != r) a.statusSince = System.currentTimeMillis();
        a.status = s;
        a.reason = s == Status.AWAITING ? r : null;
        a.statusDetail = detail == null ? "" : detail;
        listeners.forEach(x -> x.changed(a));
    }

    public void markRead(Agent a) {
        if (!a.unread) return;
        a.unread = false;
        store.save(a);
        listeners.forEach(x -> x.changed(a));
    }

    /** Saves agent changes made elsewhere (e.g. the name) and tells listeners. */
    public void saved(Agent a) {
        store.save(a);
        listeners.forEach(x -> x.changed(a));
    }

    private void notice(LiveAgent l, String text, String level) {
        record(l, new AgentEvent.Notice(text, level));
    }

    /** A pane-only line (e.g. a command's answer); journalled like everything else. */
    public void say(Agent a, String text, String level) {
        notice(live.get(a.id), text, level);
    }

    private void record(LiveAgent l, AgentEvent e) {
        l.journal.append(e);
        synchronized (l.history) {
            l.history.add(e);
        }
        listeners.forEach(x -> x.event(l.agent, e));
    }

    /** Rebuilds meters and history from the journal after a restart. */
    private void replay(LiveAgent l, AgentEvent e) {
        synchronized (l.history) {
            l.history.add(e);
        }
        switch (e) {
            case AgentEvent.Usage u -> usage(l, u);
            case AgentEvent.Message m when m.parent() == null -> l.lastReply = m.text();
            default -> { }
        }
    }

    public Profile profile(String name) {
        return settings.get().profiles.stream().filter(p -> p.name.equals(name)).findFirst().orElse(null);
    }

    public Project project(Agent a) {
        if (a.projectId == null) return null;
        return settings.get().projects.stream().filter(p -> p.id.equals(a.projectId)).findFirst().orElse(null);
    }

    /**
     * The agent's working folder (design §7.7, §7.8): its own worktree if its profile edits files and the project is a
     * git repository, else the project folder, else a private empty folder. The worktree is made on first start.
     */
    public Path folder(Agent a) {
        Project p = project(a);
        if (p == null) return home.agentHome(a.id);
        return usesWorktree(a) ? home.worktree(a.id) : Path.of(p.folder);
    }

    /** Whether the agent works in a worktree of its own: it edits files and its project is a git repository. */
    public boolean usesWorktree(Agent a) {
        Profile p = profile(a.profile);
        Project project = project(a);
        return p != null && p.editsFiles && project != null && Git.isRepository(Path.of(project.folder));
    }

    /** Creates the worktree if it isn't there (back on its branch if it had one), and (re)installs the guard hooks. */
    private void prepareWorktree(LiveAgent l) throws IOException {
        Agent a = l.agent;
        Path repo = Path.of(project(a).folder);
        Path wt = home.worktree(a.id);
        if (!Files.isDirectory(wt)) {
            Worktrees.Created c = null;
            if (a.branch != null && !a.branch.isBlank() && Worktrees.branchExists(repo, a.branch)) {
                Optional<Path> elsewhere = Worktrees.checkedOutAt(repo, a.branch);
                if (elsewhere.isEmpty()) {
                    c = Worktrees.attach(repo, wt, a.branch);
                } else {
                    notice(l, "Its branch " + a.branch + " is checked out at " + elsewhere.get()
                            + ", so the worktree starts on a new branch.", "warn");
                }
            }
            if (c == null) {
                c = Worktrees.create(repo, wt, Worktrees.branchFor(a.name));
                a.baseBranch = c.start();
                notice(l, "Worktree " + wt + " on the new branch " + c.branch() + ", from " + c.start() + ".", "dim");
            }
            a.branch = c.branch();
            store.save(a);
        }
        Worktrees.installHooks(repo, wt, home.worktreeHooks(a.id), Worktrees.baseBranches(repo, a.baseBranch));
        String branch = Worktrees.currentBranch(wt);
        if (!branch.isEmpty() && !branch.equals(a.branch)) {
            a.branch = branch;
            store.save(a);
        }
    }

    /** Looks up the agent's current branch off the calling thread and tells listeners if it changed. */
    private void refreshBranch(LiveAgent l) {
        timer.execute(() -> {
            try {
                Agent a = l.agent;
                Path f = folder(a);
                if (project(a) == null || !Files.isDirectory(f) || !Git.isRepository(f)) return;
                String b = Worktrees.currentBranch(f);
                if (!b.isEmpty() && !b.equals(a.branch)) {
                    a.branch = b;
                    store.save(a);
                    listeners.forEach(x -> x.changed(a));
                }
            } catch (RuntimeException e) {
                // a pane label; never worth failing for
            }
        });
    }

    public String effectiveModel(Agent a, Profile p) {
        return a.modelOverride != null && !a.modelOverride.isBlank() ? a.modelOverride : p.model;
    }

    public String effectiveEffort(Agent a, Profile p) {
        return a.effortOverride != null && !a.effortOverride.isBlank() ? a.effortOverride : p.effort;
    }

    /** Whether the next message restarts the process (to apply /model, /effort or a project change). */
    public boolean pendingRestart(Agent a) {
        LiveAgent l = live.get(a.id);
        Profile p = profile(a.profile);
        return l.running() && (!eq(effectiveModel(a, p), l.launchedModel) || !eq(effectiveEffort(a, p), l.launchedEffort));
    }

    private List<Provider.SkillSet> skillSets(String profile) {
        List<Provider.SkillSet> out = new ArrayList<>();
        Settings s = settings.get();
        for (Map.Entry<String, List<String>> e : skillResolver.enabledFor(s, profile).entrySet()) {
            SkillPack pack = s.skillPacks.stream().filter(p -> p.id.equals(e.getKey())).findFirst().orElseThrow();
            out.add(new Provider.SkillSet(pack.id, pack.name, skills.packDir(pack).resolve("skills"), e.getValue()));
        }
        return out;
    }

    /** {@code git status --short} of the agent's folder, for the recap; empty if not a repository or clean. */
    private String changes(Agent a) {
        Path f = folder(a);
        if (!Files.isDirectory(f.resolve(".git")) && !Files.isRegularFile(f.resolve(".git"))) return "";
        try {
            String s = dev.juno.git.Git.run(f, "status", "--short").strip();
            if (s.isEmpty()) return "";
            String[] lines = s.split("\n");
            String joined = String.join(", ", java.util.Arrays.stream(lines).limit(12).map(String::strip).toList());
            return lines.length > 12 ? joined + " …" : joined;
        } catch (IOException e) {
            return "";
        }
    }

    private Path journalFile(Agent a) {
        return home.journalsDir(a.id).resolve(a.conversationId + ".jsonl");
    }

    private static String newConversationId() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    public Settings settingsNow() {
        return settings.get();
    }

    /** Shows (and journals) an event Juno made, e.g. /diff output. */
    public void show(Agent a, AgentEvent e) {
        record(live.get(a.id), e);
    }

    /** For tests: the MCP server's URL. */
    public String mcpUrl() {
        return mcp.url();
    }
}
