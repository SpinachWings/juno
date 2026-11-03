package dev.juno.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.comms.GitHubApi;
import dev.juno.comms.GitLabApi;
import dev.juno.comms.HostActions;
import dev.juno.comms.HostApi;
import dev.juno.comms.McpClient;
import dev.juno.git.Git;
import dev.juno.git.GitAccess;
import dev.juno.model.Agent;
import dev.juno.model.Key;
import dev.juno.model.McpUrls;
import dev.juno.model.Profile;
import dev.juno.model.Project;
import dev.juno.store.Json;
import dev.juno.store.TokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An agent's way to GitHub and GitLab (design §5.1.1, §6.3): which of its keys applies, the host MCP proxy that holds
 * the key's token and forwards only what its profile may do, and git credentials on request. Tokens are read here, are
 * held only for the running process, and are never logged, shown or sent to the agent except through git's own helper.
 */
public final class HostAccess {
    public static final String GITHUB = "github";
    public static final String GITLAB = "gitlab";
    private static final Pattern URL_HOST = Pattern.compile("^[a-z+]+://(?:[^@/]+@)?([^/:]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCP_HOST = Pattern.compile("^[^@/]+@([^:/]+):");

    private final AgentRuntime rt;
    private final TokenStore tokens;
    /** How Juno itself talks to a host; tests replace it. */
    Function<Key, HostApi> apis = this::realApi;

    HostAccess(AgentRuntime rt, TokenStore tokens) {
        this.rt = rt;
        this.tokens = tokens;
    }

    /** One host MCP server as one agent's process sees it: its clients and which of them has which tool. */
    static final class Upstream {
        final Key key;
        final List<McpClient> clients = new ArrayList<>();
        final Map<String, McpClient> byTool = new LinkedHashMap<>();
        final List<JsonNode> tools = new ArrayList<>();
        boolean listed;

        Upstream(Key key) {
            this.key = key;
        }
    }

    // ---------------------------------------------------------------- keys

    public Key key(Agent a, Key.Kind kind) {
        String id = kind == Key.Kind.GITHUB ? a.githubKeyId : a.gitlabKeyId;
        return id == null ? null : rt.settingsNow().keys.stream().filter(k -> k.id.equals(id)).findFirst().orElse(null);
    }

    /** The agent's keys, whichever hosts they're for. */
    List<Key> keys(Agent a) {
        List<Key> out = new ArrayList<>();
        for (Key.Kind kind : Key.Kind.values()) {
            Key k = key(a, kind);
            if (k != null) out.add(k);
        }
        return out;
    }

    Optional<Key> keyForHost(Agent a, String host) {
        return keys(a).stream().filter(k -> k.host.equalsIgnoreCase(host)).findFirst();
    }

    /** The host of the repository's {@code origin}, or null when it has none. */
    static String originHost(Path repo) {
        try {
            if (!Files.isDirectory(repo) || !Git.isRepository(repo)) return null;
            return hostOfUrl(Git.run(repo, "remote", "get-url", "origin").strip());
        } catch (IOException e) {
            return null;
        }
    }

    static String hostOfUrl(String url) {
        Matcher m = URL_HOST.matcher(url);
        if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        m = SCP_HOST.matcher(url);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** Which kind of host that is: from the agent's keys, then the name. */
    Key.Kind kindOfHost(Agent a, String host) {
        if (host == null) return Key.Kind.GITHUB;
        Optional<Key> own = keyForHost(a, host);
        if (own.isPresent()) return own.get().kind;
        return host.toLowerCase(Locale.ROOT).contains("gitlab") ? Key.Kind.GITLAB : Key.Kind.GITHUB;
    }

    /** The key used for the agent's project's commits: the one for its host, or its only key. */
    private Key keyForProject(Agent a, Project p) {
        String host = p == null ? null : originHost(Path.of(p.folder));
        if (host != null) return keyForHost(a, host).orElse(null);
        List<Key> all = keys(a);
        return all.size() == 1 ? all.get(0) : null;
    }

    /** The git identity for the agent's commits (name, email), or null to leave the repository's. */
    String[] identity(Agent a, Project p) {
        Key k = keyForProject(a, p);
        return k == null || k.gitName.isBlank() || k.gitEmail.isBlank() ? null : new String[] {k.gitName, k.gitEmail};
    }

    /** For the preamble (design §5.1.1); blank without a key. Labels only, never tokens. */
    String describe(Agent a) {
        List<String> parts = new ArrayList<>();
        for (Key k : keys(a)) parts.add(Key.kindName(k.kind) + " on " + k.host + " with the key \"" + k.label + "\"");
        return String.join("; ", parts);
    }

    /** "No GitHub key is set for bob, so I can't push to acme/api — set one in bob's options menu" (design §5.1.1). */
    String noKeyMessage(Agent a) {
        Project p = rt.project(a);
        String host = p == null ? null : originHost(Path.of(p.folder));
        String what = p == null || p.remote.isBlank() ? "reach the host" : "reach " + p.remote;
        return "No " + Key.kindName(kindOfHost(a, host)) + " key is set for " + a.name + ", so I can't " + what
                + " — set one in " + a.name + "'s options menu.";
    }

    // ---------------------------------------------------------------- what a process gets

    /** The proxy's servers the agent may use: only with a key, and only if its profile allows any host action. */
    Map<String, String> servers(Agent a, Profile p, String proxyBase) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!HostActions.anyAllowed(p.allowedTools)) return out;
        if (key(a, Key.Kind.GITHUB) != null) out.put(GITHUB, proxyBase + GITHUB);
        if (key(a, Key.Kind.GITLAB) != null) out.put(GITLAB, proxyBase + GITLAB);
        return out;
    }

    /** Claude Code rules for the host servers an agent has, from the profile's {@code host:} rules (allowed or disallowed). */
    List<String> expand(Agent a, Iterable<String> servers, List<String> rules) {
        List<String> out = new ArrayList<>();
        for (String s : servers) {
            Key k = key(a, s.equals(GITLAB) ? Key.Kind.GITLAB : Key.Kind.GITHUB);
            if (k != null) out.addAll(HostActions.expand(s, k.kind, rules));
        }
        return out;
    }

    /** The git environment of the agent's process, with the key's author identity when it has one (never in git config). */
    Map<String, String> gitEnvironment(Agent a, Path helper, Project project) {
        List<String> hosts = keys(a).stream().map(k -> k.host).toList();
        Map<String, String> env = new LinkedHashMap<>(GitAccess.forAgent(hosts, helper == null ? "" : helper.toString()));
        String[] who = identity(a, project);
        if (who != null) {
            env.put("GIT_AUTHOR_NAME", who[0]);
            env.put("GIT_COMMITTER_NAME", who[0]);
            env.put("GIT_AUTHOR_EMAIL", who[1]);
            env.put("GIT_COMMITTER_EMAIL", who[1]);
        }
        return env;
    }

    /** What the launch notes down, to restart when the agent's keys change. */
    String fingerprint(Agent a) {
        return a.githubKeyId + "/" + a.gitlabKeyId;
    }

    /** Git's question about a host, answered from the agent's key for it; "" means no. */
    String gitCredential(String agentId, String request) {
        LiveAgent l = rt.live(agentId);
        if (l == null) return "";
        String host = GitAccess.parseHost(request);
        if (host.isEmpty()) return "";
        Key k = keyForHost(l.agent, host).orElse(null);
        if (k == null) return "";
        Optional<String> token = tokens.resolve(k.gitAndRestToken());
        return token.map(t -> "username=" + GitAccess.username(k.kind == Key.Kind.GITLAB) + "\npassword=" + t + "\n")
                .orElse("");
    }

    /** The token for git (and the GitHub Test's REST calls), if readable. */
    Optional<String> restToken(Key k) {
        return tokens.resolve(k.gitAndRestToken());
    }

    private HostApi realApi(Key k) {
        String token = restToken(k).orElse(null);
        if (token == null) return null;
        return k.kind == Key.Kind.GITLAB ? new GitLabApi(k.host, token)
                : new GitHubApi(GitHubApi.apiBase(k.host), token);
    }

    /** Juno's REST client for the key; null when its token can't be read. */
    HostApi api(Key k) {
        return apis.apply(k);
    }

    // ---------------------------------------------------------------- the proxy (design §6.3)

    private Upstream upstream(LiveAgent l, String server) {
        Key.Kind kind = server.equals(GITLAB) ? Key.Kind.GITLAB : Key.Kind.GITHUB;
        Key k = key(l.agent, kind);
        if (k == null) return null;
        synchronized (l.upstreams) {
            Upstream u = l.upstreams.get(server);
            if (u != null && u.key.id.equals(k.id)) return u;
            String token = tokens.resolve(k.token).orElse(null);
            String url = McpUrls.forKey(k).orElse(null);
            if (token == null || url == null) return null;
            u = new Upstream(k);
            u.clients.add(new McpClient(url, token));
            // The default toolset plus actions (design §6.3); not for an override, which may be anything.
            if (kind == Key.Kind.GITHUB && url.endsWith("/mcp/") && k.mcpUrlOverride.isBlank()) {
                u.clients.add(new McpClient(url + "x/actions", token));
            }
            l.upstreams.put(server, u);
            return u;
        }
    }

    /** {@code tools/list} for the agent: what the host offers, less what its profile may never do. */
    ArrayNode tools(String agentId, String server) {
        ArrayNode out = Json.MAPPER.createArrayNode();
        LiveAgent l = rt.live(agentId);
        if (l == null) return out;
        Upstream u = upstream(l, server);
        if (u == null) {
            rt.say(l.agent, "⚠ The token of " + l.agent.name + "'s " + server + " key can't be read, so it has no "
                    + server + " tools.", "warn");
            return out;
        }
        synchronized (u) {
            if (!u.listed) {
                for (McpClient c : u.clients) {
                    try {
                        for (JsonNode t : c.tools()) {
                            String name = t.path("name").asText();
                            if (u.byTool.putIfAbsent(name, c) == null) u.tools.add(t);
                        }
                    } catch (IOException e) {
                        if (c == u.clients.get(0)) {
                            rt.say(l.agent, "⚠ " + server + "'s MCP server: " + e.getMessage(), "warn");
                        }
                    }
                }
                u.listed = !u.byTool.isEmpty();
            }
            out.addAll(u.tools);
        }
        return out;
    }

    /** {@code tools/call}: forwarded as it is, with the marker on replies; Claude Code's own rules decided whether to ask you. */
    ObjectNode call(String agentId, String server, String tool, JsonNode args) {
        LiveAgent l = rt.live(agentId);
        if (l == null) return refused(AgentPrompts.result("common", "call-failed", Map.of("reason", "this agent no longer exists")));
        Agent a = l.agent;
        Upstream u = upstream(l, server);
        if (u == null) {
            String m = noKeyMessage(a);
            rt.keyStop(l, m);
            return refused(m);
        }
        Key.Kind kind = u.key.kind;
        String kindName = Key.kindName(kind);
        ObjectNode sent = args != null && args.isObject() ? ((ObjectNode) args).deepCopy() : Json.MAPPER.createObjectNode();
        String field = HostActions.commentField(kind, tool, sent);
        if (field != null && !sent.path(field).asText("").isBlank()
                && !sent.path(field).asText("").startsWith("🤖 ")) {
            sent.put(field, "🤖 " + a.name + " (" + a.profile + "): " + sent.path(field).asText());
        }
        McpClient c;
        synchronized (u) {
            if (!u.listed) tools(agentId, server);
            c = u.byTool.getOrDefault(tool, u.clients.get(0));
        }
        try {
            JsonNode r = c.callTool(tool, sent);
            if (HostActions.isOpen(kind, tool, sent) && !r.path("isError").asBoolean(false)) {
                rt.prOpened(l, kind, sent, r);
            }
            return r.isObject() ? (ObjectNode) r : refused(result("failed", Map.of("host_kind", kindName, "detail", "an empty answer")));
        } catch (IOException e) {
            String wanted = e instanceof HostApi.HostException h && !h.wanted.isBlank() ? " The host asks for " + h.wanted + "." : "";
            return refused(result("failed", Map.of("host_kind", kindName, "detail", e.getMessage() + wanted)));
        }
    }

    /** Git's own refusals that mean no key or a refused one; returns what to tell the user, or null. */
    String keyProblem(Agent a, String toolOutput) {
        if (toolOutput == null) return null;
        if (toolOutput.contains("not allowed") && toolOutput.matches("(?s).*transport '(https?|ssh|git)' not allowed.*")) {
            if (keys(a).isEmpty()) return noKeyMessage(a);
        }
        if (!keys(a).isEmpty() && (toolOutput.contains("Authentication failed for")
                || toolOutput.contains("could not read Username for")
                || toolOutput.matches("(?s).*The requested URL returned error: 40[13].*"))) {
            return a.name + "'s key was refused by the host, or doesn't cover this repository. Set another in "
                    + a.name + "'s options menu, or check the key with Test in Settings → Keys.";
        }
        return null;
    }

    private static String result(String key, Map<String, ?> values) {
        return AgentPrompts.result("host", key, values).strip();
    }

    private static ObjectNode refused(String text) {
        ObjectNode r = Json.MAPPER.createObjectNode();
        ObjectNode c = r.putArray("content").addObject();
        c.put("type", "text");
        c.put("text", text);
        r.put("isError", true);
        return r;
    }

    /** Checks a key for the Settings tab, off the UI thread: the MCP server(s), then REST (design §5.1). */
    public List<HostApi.TestLine> test(Key k, String repo) {
        List<HostApi.TestLine> out = new ArrayList<>();
        String token = tokens.resolve(k.token).orElse(null);
        String url = McpUrls.forKey(k).orElse(null);
        String mcp = "MCP server" + (k.kind == Key.Kind.GITLAB ? " (MCP token)" : "");
        if (url == null) out.add(new HostApi.TestLine(mcp, false, McpUrls.unsupportedReason(k)));
        else if (token == null) out.add(new HostApi.TestLine(mcp, false, "the token can't be read"));
        else {
            try {
                int n = new McpClient(url, token).tools().size();
                out.add(new HostApi.TestLine(mcp, n > 0, n + " tools at " + url));
            } catch (IOException e) {
                out.add(new HostApi.TestLine(mcp, false, e.getMessage()));
            }
        }
        HostApi api = api(k);
        if (api == null) out.add(new HostApi.TestLine("REST", false, k.kind == Key.Kind.GITLAB
                ? "the git token can't be read" : "the token can't be read"));
        else out.addAll(api.test(repo));
        return out;
    }
}
