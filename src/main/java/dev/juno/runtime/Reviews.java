package dev.juno.runtime;

import dev.juno.comms.HostApi;
import dev.juno.comms.HostApi.PullRequest;
import dev.juno.comms.JunoMcpServer.Reply;
import dev.juno.comms.Pipelines;
import dev.juno.git.GitAccess;
import dev.juno.git.Snapshots;
import dev.juno.model.Agent;
import dev.juno.model.Agent.Reason;
import dev.juno.model.Agent.Review;
import dev.juno.model.Agent.Status;
import dev.juno.model.Key;
import dev.juno.model.Project;
import dev.juno.prompts.Prompts;
import dev.juno.store.JunoHome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reviews (design §6.3, §6.5): finds the PR/MR, waits for its pipelines by polling the host (spending no tokens), makes
 * the snapshot and tells the reviewer, and cleans up. Network and disk work happens off the runtime's lock; state
 * changes under it.
 */
public final class Reviews {
    private static final Pattern GITHUB_LINK = Pattern.compile("https?://([^/\\s]+)/([^\\s]+?)/pull/(\\d+)\\S*");
    private static final Pattern GITLAB_LINK = Pattern.compile("https?://([^/\\s]+)/([^\\s]+?)/(?:-/)?merge_requests/(\\d+)\\S*");
    private static final Pattern NUMBER = Pattern.compile("[#!]?(\\d+)");
    static final long LEFTOVER_MS = 24 * 3_600_000L;

    private final AgentRuntime rt;
    private final HostAccess host;
    private final JunoHome home;
    private final Set<String> polling = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "juno-reviews");
        t.setDaemon(true);
        return t;
    });
    /** Every 60 s for 10 minutes, then every 2 minutes (design §6.3). Tests shorten them. */
    long fastMs = 60_000, slowMs = 120_000, fastForMs = 10 * 60_000;
    /** A repository with pipelines where none has appeared on the commit yet gets this long before it counts as none. */
    long notStartedMs = 120_000;
    private long lastSweep;

    Reviews(AgentRuntime rt, HostAccess host, JunoHome home) {
        this.rt = rt;
        this.host = host;
        this.home = home;
    }

    void stop() {
        pool.shutdownNow();
    }

    /** After a restart: a snapshot being made when Juno stopped is made again. */
    void started() {
        for (Agent a : rt.all()) {
            if (a.review != null && a.review.phase == Review.Phase.PREPARING) {
                a.review.phase = Review.Phase.WAITING;
                a.review.nextPoll = 0;
            }
        }
    }

    // ---------------------------------------------------------------- start_review

    private record Target(String host, Key.Kind kind, String repo, int number, Agent owner, Project project) {}

    /** What a refusal does besides answering the agent: a stop for the user. */
    Reply start(String agentId, String text) {
        LiveAgent l = rt.live(agentId);
        if (l == null) return Reply.refused(result("common", "call-failed", Map.of("reason", "this agent no longer exists")));
        Agent a = l.agent;
        String wanted = text.strip();
        Target t;
        Key key;
        synchronized (rt) {
            Object parsed = parse(a, wanted);
            if (parsed instanceof Reply r) return r;
            t = (Target) parsed;
            key = host.keyForHost(a, t.host()).orElse(null);
            if (key == null) {
                String kind = Key.kindName(t.kind());
                String why = result("start_review", "no-key", Map.of("host_kind", kind, "agent_name", a.name, "repo", t.repo()));
                rt.keyStop(l, "No " + kind + " key is set for " + a.name + ", so I can't read " + t.repo()
                        + " — set one in " + a.name + "'s options menu.");
                return Reply.refused(why);
            }
        }
        HostApi api = host.api(key);
        String repoNote = t.repo();
        Map<String, Object> cant = Map.of("agent_name", a.name, "host_kind", Key.kindName(t.kind()), "repo", repoNote);
        if (api == null) return Reply.refused(result("start_review", "key-cant-read", cant));
        PullRequest pr;
        try {
            if (t.owner() != null) {
                pr = api.openFor(t.repo(), t.owner().branch).orElse(null);
                if (pr == null) return Reply.refused(result("start_review", "no-open-pr", Map.of("name", t.owner().name)));
            } else {
                pr = api.pullRequest(t.repo(), t.number());
            }
        } catch (HostApi.HostException e) {
            if (e.denied()) return Reply.refused(result("start_review", "key-cant-read", cant));
            return Reply.refused(result("start_review", "not-found", Map.of("target", wanted, "detail", e.getMessage())));
        } catch (IOException e) {
            return Reply.refused(result("start_review", "not-found", Map.of("target", wanted, "detail", String.valueOf(e.getMessage()))));
        }
        String label = api.label(pr.number());
        synchronized (rt) {
            Project project = t.project() != null ? t.project() : projectFor(t.repo());
            if (project == null) {
                String clone = "https://" + t.host() + "/" + t.repo() + ".git";
                l.folderReason = "Run: git clone " + clone + " — then add the folder as a project (with the remote "
                        + t.repo() + ") and tell " + a.name + ".";
                rt.setStatus(l, Status.AWAITING, Reason.FOLDER, l.folderReason);
                a.unread = true;
                rt.say(a, "📁 " + l.folderReason, "warn");
                rt.saved(a);
                return Reply.ok(result("start_review", "not-cloned", Map.of("repo", t.repo(), "clone_url", clone)));
            }
            Review r = a.review;
            if (r != null && !(r.repo.equalsIgnoreCase(t.repo()) && r.number == pr.number())) {
                return Reply.refused(result("start_review", "already-open", Map.of("open_target", r.target)));
            }
            boolean refresh = r != null;
            if (r == null) r = new Review();
            r.target = label;
            r.kind = t.kind();
            r.host = t.host();
            r.repo = t.repo();
            r.number = pr.number();
            r.projectId = project.id;
            r.headSha = pr.headSha();
            r.baseSha = pr.baseSha();
            r.baseRef = pr.baseRef();
            r.phase = Review.Phase.WAITING;
            r.ask = null;
            r.waitNewCommit = false;
            r.waitingSince = System.currentTimeMillis();
            r.nextPoll = 0;
            r.approved = false;
            a.review = r;
            a.reviewOpen = true;
            rt.say(a, (refresh ? "⏳ Waiting again for pipelines on " : "⏳ Waiting for pipelines on ") + label + " ("
                    + t.repo() + ", head " + shortSha(pr.headSha()) + ").", "dim");
            rt.saved(a);
        }
        pollSoon(a);
        return Reply.ok(result("start_review", "waiting", Map.of("target", label, "head", pr.headSha())));
    }

    /** A {@link Target}, or the {@link Reply} refusing what was asked. */
    private Object parse(Agent a, String text) {
        Matcher m = GITHUB_LINK.matcher(text);
        if (m.matches()) return linkTarget(m, Key.Kind.GITHUB);
        m = GITLAB_LINK.matcher(text);
        if (m.matches()) return linkTarget(m, Key.Kind.GITLAB);
        m = NUMBER.matcher(text);
        if (m.matches()) {
            Project p = a.review != null ? projectById(a.review.projectId) : rt.project(a);
            if (p == null) {
                return Reply.refused(result("start_review", "not-found", Map.of("target", text,
                        "detail", "a number alone needs a project to look in; ask the user for a link")));
            }
            return ofProject(a, p, Integer.parseInt(m.group(1)), null);
        }
        Agent owner = rt.byName(text.startsWith("@") ? text.substring(1) : text).orElse(null);
        if (owner == null) {
            return Reply.refused(result("start_review", "not-found", Map.of("target", text,
                    "detail", "that's no PR/MR number or link, and there's no agent with that name")));
        }
        Project p = rt.project(owner);
        if (p == null || owner.branch.isBlank()) return Reply.refused(result("start_review", "no-open-pr", Map.of("name", owner.name)));
        return ofProject(a, p, 0, owner);
    }

    private Target linkTarget(Matcher m, Key.Kind byPath) {
        String h = m.group(1).toLowerCase(java.util.Locale.ROOT);
        return new Target(h, byPath, m.group(2), Integer.parseInt(m.group(3)), null, null);
    }

    private Object ofProject(Agent a, Project p, int number, Agent owner) {
        if (p.remote == null || p.remote.isBlank()) {
            return Reply.refused(result("start_review", "no-remote", Map.of("project", p.name)));
        }
        String h = HostAccess.originHost(Path.of(p.folder));
        if (h == null) {
            List<Key> keys = host.keys(a);
            h = keys.size() == 1 ? keys.get(0).host : Key.Kind.GITHUB.defaultHost;
        }
        return new Target(h, host.kindOfHost(a, h), p.remote.strip(), number, owner, p);
    }

    private Project projectFor(String repo) {
        return rt.settingsNow().projects.stream().filter(p -> p.remote != null && p.remote.strip().equalsIgnoreCase(repo))
                .findFirst().orElse(null);
    }

    private Project projectById(String id) {
        return rt.settingsNow().projects.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null);
    }

    /** The project a review's snapshot belongs to: named in the agent's prompt while it reviews. */
    Project reviewProject(Agent a) {
        return a.review == null ? null : projectById(a.review.projectId);
    }

    // ---------------------------------------------------------------- polling

    /** From the runtime's timer. */
    void tick(long now) {
        List<Agent> due = new ArrayList<>();
        synchronized (rt) {
            for (Agent a : rt.all()) {
                Review r = a.review;
                if (r != null && r.phase == Review.Phase.WAITING && now >= r.nextPoll) due.add(a);
            }
        }
        due.forEach(this::pollSoon);
        if (now - lastSweep > 3_600_000L) {
            lastSweep = now;
            pool.execute(this::sweepLeftovers);
        }
    }

    private void pollSoon(Agent a) {
        if (!polling.add(a.id)) return;
        pool.execute(() -> {
            try {
                poll(a);
            } catch (RuntimeException e) {
                System.err.println("Pipeline poll failed: " + e);
            } finally {
                polling.remove(a.id);
            }
        });
    }

    private void poll(Agent a) {
        LiveAgent l = rt.live(a.id);
        if (l == null) return;
        Review r;
        Key key;
        synchronized (rt) {
            r = a.review;
            if (r == null || r.phase != Review.Phase.WAITING) return;
            key = host.keyForHost(a, r.host).orElse(null);
            r.nextPoll = System.currentTimeMillis() + interval(r);
            if (key == null) {
                String m = "No " + Key.kindName(r.kind) + " key is set for " + a.name + ", so I can't read " + r.repo
                        + " — set one in " + a.name + "'s options menu.";
                r.phase = Review.Phase.ASK;
                r.ask = Review.Ask.SNAPSHOT;
                r.askText = m;
                rt.keyStop(l, m);
                return;
            }
        }
        HostApi api = host.api(key);
        PullRequest pr = null;
        Pipelines.Outcome outcome = null;
        String trouble = null;
        if (api == null) trouble = "the key's token can't be read";
        else {
            try {
                pr = api.pullRequest(r.repo, r.number);
                if (pr.open() && pr.headSha().equals(r.headSha) && !r.waitNewCommit) {
                    outcome = Pipelines.evaluate(api.checks(r.repo, pr));
                }
            } catch (IOException e) {
                trouble = e.getMessage();
            }
        }
        boolean snapshot;
        synchronized (rt) {
            if (a.review != r || r.phase != Review.Phase.WAITING) return;
            snapshot = applyPoll(l, r, api, pr, outcome, trouble);
        }
        if (snapshot) makeSnapshot(l, r, key, api, outcome);
    }

    /** Under the lock. True when the wait is over and the snapshot is next. */
    private boolean applyPoll(LiveAgent l, Review r, HostApi api, PullRequest pr, Pipelines.Outcome o, String trouble) {
        Agent a = l.agent;
        long now = System.currentTimeMillis();
        r.troubles = trouble == null ? 0 : r.troubles + 1;
        if (trouble != null && r.troubles == 3) rt.say(a, "⚠ Can't read pipelines on " + r.target + ": " + trouble + ". Still trying.", "warn");
        if (pr != null) {
            if (!pr.open()) {
                cancel(a, "the PR/MR was " + (pr.state().equals("merged") ? "merged" : "closed"));
                return false;
            }
            if (!pr.headSha().equals(r.headSha)) {
                r.headSha = pr.headSha();
                r.baseSha = pr.baseSha();
                r.baseRef = pr.baseRef();
                r.waitNewCommit = false;
                r.waitingSince = now;
                r.nextPoll = 0;
                rt.say(a, "⏳ New commit on " + r.target + " (" + shortSha(pr.headSha()) + "): waiting for its pipelines.", "dim");
                waiting(l, r);
                return false;
            }
        }
        if (o != null) {
            r.checks = o.summary();
            r.optionalFailed = String.join(", ", o.optionalFailed());
            switch (o.result()) {
                case PASSED -> {
                    r.noPipelines = false;
                    r.phase = Review.Phase.PREPARING;
                    return true;
                }
                case NONE -> {
                    r.noPipelines = true;
                    r.phase = Review.Phase.PREPARING;
                    return true;
                }
                case NOT_STARTED -> {
                    if (now - r.waitingSince >= notStartedMs) {
                        r.noPipelines = true;
                        r.phase = Review.Phase.PREPARING;
                        return true;
                    }
                }
                case NEEDS_PERSON -> {
                    ask(l, r, Review.Ask.NEEDS_PERSON, "Pipelines on " + r.target + " need a person: "
                            + String.join(", ", o.names()) + ". Approve them on " + Key.kindName(r.kind)
                            + ", then press Continue.");
                    return false;
                }
                case FAILED -> {
                    ask(l, r, Review.Ask.FAILED, "Pipelines failed on " + r.target + ": " + String.join(", ", o.names())
                            + ". No review until there's a new commit.");
                    return false;
                }
                case PENDING -> { }
            }
        }
        if (now - r.waitingSince >= rt.settingsNow().system.pipelineWaitLimitMinutes * 60_000L) {
            ask(l, r, Review.Ask.LIMIT, "Still no result from the pipelines on " + r.target + " after "
                    + rt.settingsNow().system.pipelineWaitLimitMinutes + " minutes.");
            return false;
        }
        waiting(l, r);
        return false;
    }

    private long interval(Review r) {
        return System.currentTimeMillis() - r.waitingSince < fastForMs ? fastMs : slowMs;
    }

    /** Shows the wait; while the agent still finishes its turn, that turn's end shows it (see {@link #afterTurn}). */
    private void waiting(LiveAgent l, Review r) {
        if (!l.agent.turnOpen) rt.setStatus(l, Status.WAITING, null, "pipelines on " + r.target);
        rt.saved(l.agent);
    }

    private void ask(LiveAgent l, Review r, Review.Ask what, String text) {
        r.phase = Review.Phase.ASK;
        r.ask = what;
        r.askText = text;
        rt.setStatus(l, Status.AWAITING, Reason.FAILED, text);
        l.agent.unread = true;
        rt.say(l.agent, "⚠ " + text, "warn");
        rt.saved(l.agent);
    }

    /** At the end of a turn: true if the review decides the status (waiting, or asking the user). */
    boolean afterTurn(LiveAgent l) {
        Review r = l.agent.review;
        if (r == null || r.phase == Review.Phase.READY) return false;
        if (r.phase == Review.Phase.ASK && r.ask != null && l.agent.status == Status.AWAITING) return true;
        if (r.phase == Review.Phase.WAITING || r.phase == Review.Phase.PREPARING) {
            rt.setStatus(l, Status.WAITING, null, "pipelines on " + r.target);
        }
        return true;
    }

    /** The buttons of the card and inbox: {@code go} keeps waiting (or waits for a new commit); otherwise cancels. */
    public String answer(Agent a, boolean go) {
        synchronized (rt) {
            Review r = a.review;
            if (r == null || r.phase != Review.Phase.ASK) return "That was already answered.";
            if (!go) {
                cancel(a, "the user cancelled it");
                return null;
            }
            LiveAgent l = rt.live(a.id);
            r.waitNewCommit = r.ask == Review.Ask.FAILED;
            if (r.ask == Review.Ask.LIMIT) r.waitingSince = System.currentTimeMillis();
            r.phase = Review.Phase.WAITING;
            r.ask = null;
            r.askText = null;
            r.nextPoll = 0;
            if (a.turnOpen) rt.setStatus(l, Status.WORKING, null, "");
            else waiting(l, r);
            rt.saved(a);
        }
        pollSoon(a);
        return null;
    }

    // ---------------------------------------------------------------- the snapshot (design §6.5)

    private void makeSnapshot(LiveAgent l, Review r, Key key, HostApi api, Pipelines.Outcome o) {
        Agent a = l.agent;
        Project project;
        synchronized (rt) {
            project = projectById(r.projectId);
        }
        String why = null;
        Path folder = null;
        List<String> warnings = List.of();
        try {
            if (project == null) throw new IOException("the project is gone");
            String token = host.restToken(key).orElseThrow(() -> new IOException("the key's token can't be read"));
            Path repo = Path.of(project.folder);
            folder = Snapshots.folder(home.reviewDir(), a.id, r.number, r.headSha);
            warnings = Snapshots.create(new Snapshots.Request(repo, folder, Snapshots.source(repo, r.host, r.repo), a.id,
                    r.number, r.headSha, r.baseSha, api.headRef(r.number), r.baseRef,
                    GitAccess.forJuno(r.host, r.kind == Key.Kind.GITLAB, token))).warnings();
        } catch (IOException e) {
            why = e.getMessage();
        }
        synchronized (rt) {
            if (a.review != r) {
                if (folder != null) staleAndClean(a, folder.toString());
                return;
            }
            if (why != null) {
                ask(l, r, Review.Ask.SNAPSHOT, "Couldn't make the snapshot of " + r.target + ": "
                        + AgentPrompts.firstLine(why, 300));
                return;
            }
            ready(l, r, folder, o, warnings);
        }
    }

    /** Under the lock: the reviewer's folder becomes the snapshot (its process restarts, conversation kept). */
    private void ready(LiveAgent l, Review r, Path folder, Pipelines.Outcome o, List<String> warnings) {
        Agent a = l.agent;
        if (r.snapshot != null && !r.snapshot.equals(folder.toString())) a.staleSnapshots.add(r.snapshot);
        r.snapshot = folder.toString();
        r.snapshotAt = System.currentTimeMillis();
        r.phase = Review.Phase.READY;
        r.approved = false;
        for (String w : warnings) rt.say(a, "⚠ Snapshot without " + w, "warn");
        rt.say(a, "✓ Review snapshot of " + r.target + " at " + shortSha(r.headSha) + ".", "good");
        Map<String, Object> v = new HashMap<>();
        v.put("target", r.target);
        v.put("head", r.headSha);
        v.put("base", r.baseSha);
        v.put("folder", r.snapshot);
        v.put("checks", r.checks == null || r.checks.isBlank() ? "none" : r.checks);
        v.put("optional_failed", r.optionalFailed == null ? "" : r.optionalFailed);
        String text = wrapper(r.noPipelines ? "none" : "passed", v);
        if (a.turnOpen) {
            rt.restartAfterTurn(l, text);
        } else {
            rt.setStatus(l, Status.IDLE, null, "");
            rt.send(a, text, "juno");
        }
        rt.saved(a);
    }

    // ---------------------------------------------------------------- ending

    /** {@code end_review}, or finishing: the snapshot goes once no process runs in it. */
    void finish(Agent a) {
        Review r = a.review;
        a.review = null;
        a.reviewOpen = false;
        if (r != null && r.snapshot != null) a.staleSnapshots.add(r.snapshot);
    }

    /** Ends the review and tells the reviewer: post nothing (design §11, {@code wrappers/pipeline.md}). */
    public void cancel(Agent a, String reason) {
        synchronized (rt) {
            LiveAgent l = rt.live(a.id);
            Review r = a.review;
            if (r == null) return;
            finish(a);
            rt.say(a, "✂ Review of " + r.target + " cancelled: " + reason + ".", "warn");
            rt.releaseSnapshots(l);
            if (!a.turnOpen) rt.setStatus(l, Status.IDLE, null, "");
            Map<String, Object> v = new HashMap<>();
            v.put("target", r.target);
            v.put("reason", reason.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + reason.substring(1));
            v.put("from_juno", a.task != null);
            rt.inject(a, wrapper("cancelled", v), "juno");
            rt.saved(a);
        }
    }

    /** A user's message after the snapshot was ready: from now on the reviewer may post what they approved. */
    void userAnswered(Agent a) {
        if (a.review != null && a.review.phase == Review.Phase.READY) a.review.approved = true;
    }

    private void staleAndClean(Agent a, String folder) {
        a.staleSnapshots.add(folder);
        clean(a);
    }

    /** Removes the agent's stale snapshots, retrying while a dying process still holds them (Windows). */
    void clean(Agent a) {
        List<String> folders;
        synchronized (rt) {
            folders = List.copyOf(a.staleSnapshots);
        }
        if (folders.isEmpty()) return;
        pool.execute(() -> {
            for (String f : folders) {
                if (removeWithRetries(Path.of(f), 8)) {
                    synchronized (rt) {
                        a.staleSnapshots.remove(f);
                        if (rt.live(a.id) != null) rt.saved(a);
                    }
                }
            }
        });
    }

    private boolean removeWithRetries(Path folder, int tries) {
        String[] name = Snapshots.parseName(folder.getFileName().toString());
        for (int i = 0; i < tries; i++) {
            try {
                Snapshots.remove(folder, name == null ? "" : name[0], name == null ? 0 : Integer.parseInt(name[1]));
                if (!Files.exists(folder)) return true;
            } catch (IOException | RuntimeException e) {
                // still in use
            }
            try {
                Thread.sleep(750);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** All of an agent's snapshot folders, for Storage and deletion. */
    List<Path> snapshotsOf(String agentId) {
        Path dir = home.reviewDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (var list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().startsWith(agentId + "-")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The agent is being deleted: its snapshots go, whatever state they're in. */
    void deleted(Agent a) {
        List<Path> all = snapshotsOf(a.id);
        a.review = null;
        pool.execute(() -> all.forEach(f -> removeWithRetries(f, 8)));
    }

    /** "Leftovers are removed after 24 hours and shown in Storage meanwhile" (design §6.3). */
    void sweepLeftovers() {
        Path dir = home.reviewDir();
        if (!Files.isDirectory(dir)) return;
        List<Path> all;
        try (var list = Files.list(dir)) {
            all = list.toList();
        } catch (IOException e) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Path f : all) {
            boolean inUse;
            synchronized (rt) {
                inUse = rt.all().stream().anyMatch(a -> a.review != null && f.toString().equals(a.review.snapshot));
            }
            try {
                if (!inUse && now - Files.getLastModifiedTime(f).toMillis() > LEFTOVER_MS) removeWithRetries(f, 2);
            } catch (IOException e) {
                // gone meanwhile
            }
        }
    }

    // ---------------------------------------------------------------- text

    static String shortSha(String sha) {
        return sha == null ? "" : sha.substring(0, Math.min(7, sha.length()));
    }

    private static String wrapper(String key, Map<String, ?> values) {
        return Prompts.render(Prompts.sections(Prompts.load("wrappers/pipeline.md")).get(key), values).trim();
    }

    private static String result(String file, String key, Map<String, ?> values) {
        return AgentPrompts.result(file, key, values).strip();
    }
}
