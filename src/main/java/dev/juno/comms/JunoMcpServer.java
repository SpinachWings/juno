package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.juno.prompts.Prompts;
import dev.juno.store.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Juno's MCP server (design §4.4): streamable HTTP on {@code 127.0.0.1}, JSON responses. Each agent process gets its
 * own random bearer token, which is how a call is tied to its agent (an agent can't speak for another).
 *
 * <p>Every tool of §4.4. The app decides who may call what ({@link Calls#refusal}); {@code tools/list} shows an agent
 * only the tools it may call, and a call it may not make is refused here, whatever its prompt says.
 *
 * <p>Also, under {@code /host/<server>}, the proxy to the host's MCP servers (§6.3), and {@code /git-credential} for the
 * per-process git credential helper (§5.1.1); the same tokens identify the agent.
 */
public final class JunoMcpServer {
    /** What the server asks the app to do. Calls may block (the user answers in the pane). */
    public interface Calls {
        /** Claude Code's permission prompt; returns its decision object ({@code behavior} allow / deny). */
        ObjectNode requestPermission(String agentId, String tool, JsonNode input, String toolUseId);

        /** Why the agent may not call {@code tool}, or null if it may (design §4.4). */
        String refusal(String agentId, String tool);

        /** Runs any other tool; returns what the agent gets back. */
        Reply call(String agentId, String tool, JsonNode args);

        /** Placeholders in tool descriptions (e.g. {@code report_length}). */
        Map<String, ?> promptValues();

        /** The host MCP server's tools this agent may see. */
        ArrayNode hostTools(String agentId, String server);

        /** Forwards a call to the host's MCP server if the agent's profile allows this action. */
        ObjectNode hostCall(String agentId, String server, String tool, JsonNode args);

        /** Git's credential request, answered with this agent's key; "" for none. */
        String gitCredential(String agentId, String request);
    }

    /** A tool's answer; {@code error} marks refusals. */
    public record Reply(String text, boolean error) {
        public static Reply ok(String text) {
            return new Reply(text, false);
        }

        public static Reply refused(String text) {
            return new Reply(text, true);
        }
    }

    public static final List<String> TOOLS = List.of("list_agents", "send_message", "report", "request_folder",
            "assign_task", "hand_over", "set_link", "request_permission");
    /** Tools only Juno may call, whatever a profile allows (design §4.4). */
    public static final List<String> ORCHESTRATOR_ONLY = List.of("assign_task", "set_link");
    /** Parameter types other than string, and parameters that may be left out. */
    private static final Map<String, String> TYPES = Map.of("fresh", "boolean", "open", "boolean",
            "requested_by_user", "boolean");
    private static final List<String> OPTIONAL = List.of("set_link.reason");
    private static final Pattern PARAM = Pattern.compile("^- `(\\w+)`: (.*)$");

    private final Calls calls;
    private final Map<String, String> agentByToken = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private HttpServer http;
    private ExecutorService pool;

    public JunoMcpServer(Calls calls) {
        this.calls = calls;
    }

    public synchronized void start() throws IOException {
        if (http != null) return;
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "juno-mcp");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/mcp", this::handle);
        http.createContext("/host/", this::handleHost);
        http.createContext("/git-credential", this::handleCredential);
        http.start();
    }

    public synchronized void stop() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
        http = null;
    }

    public String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
    }

    /** The proxy's base: a server's URL is this plus its name (design §6.3). */
    public String hostBase() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/host/";
    }

    /** Where the git credential helper asks (design §5.1.1). */
    public String credentialUrl() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/git-credential";
    }

    /** A new token for one process of {@code agentId}. Never logged or shown. */
    public String register(String agentId) {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        agentByToken.put(token, agentId);
        return token;
    }

    public void unregister(String token) {
        if (token != null) agentByToken.remove(token);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String agent = auth != null && auth.startsWith("Bearer ") ? agentByToken.get(auth.substring(7).trim()) : null;
            if (agent == null) {
                send(ex, 401, null);
                return;
            }
            switch (ex.getRequestMethod()) {
                case "POST" -> post(ex, agent);
                case "DELETE" -> send(ex, 200, null);
                default -> send(ex, 405, null);
            }
        }
    }

    private String agentOf(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        return auth != null && auth.startsWith("Bearer ") ? agentByToken.get(auth.substring(7).trim()) : null;
    }

    private void handleHost(HttpExchange ex) throws IOException {
        try (ex) {
            String agent = agentOf(ex);
            if (agent == null) {
                send(ex, 401, null);
                return;
            }
            if (ex.getRequestMethod().equals("DELETE")) {
                send(ex, 200, null);
                return;
            }
            if (!ex.getRequestMethod().equals("POST")) {
                send(ex, 405, null);
                return;
            }
            String server = ex.getRequestURI().getPath().substring("/host/".length());
            JsonNode m;
            try {
                m = Json.MAPPER.readTree(ex.getRequestBody().readAllBytes());
            } catch (IOException e) {
                send(ex, 400, error(null, -32700, "Parse error"));
                return;
            }
            JsonNode id = m.get("id");
            if (id == null || id.isNull()) {
                send(ex, 202, null);
                return;
            }
            JsonNode params = m.path("params");
            ObjectNode r = switch (m.path("method").asText()) {
                case "initialize" -> {
                    ObjectNode o = Json.MAPPER.createObjectNode();
                    o.put("protocolVersion", params.path("protocolVersion").asText("2025-06-18"));
                    o.putObject("capabilities").putObject("tools");
                    ObjectNode info = o.putObject("serverInfo");
                    info.put("name", server);
                    info.put("version", "juno-proxy");
                    yield result(id, o);
                }
                case "ping" -> result(id, Json.MAPPER.createObjectNode());
                case "tools/list" -> {
                    ObjectNode o = Json.MAPPER.createObjectNode();
                    o.set("tools", calls.hostTools(agent, server));
                    yield result(id, o);
                }
                case "tools/call" -> result(id, calls.hostCall(agent, server, params.path("name").asText(), params.path("arguments")));
                default -> error(id, -32601, "Method not found");
            };
            send(ex, 200, r);
        }
    }

    private void handleCredential(HttpExchange ex) throws IOException {
        try (ex) {
            String agent = agentOf(ex);
            if (agent == null || !ex.getRequestMethod().equals("POST")) {
                send(ex, 401, null);
                return;
            }
            byte[] b = calls.gitCredential(agent, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(b);
                }
            }
        }
    }

    private void post(HttpExchange ex, String agent) throws IOException {
        JsonNode body;
        try {
            body = Json.MAPPER.readTree(ex.getRequestBody().readAllBytes());
        } catch (IOException e) {
            send(ex, 400, error(null, -32700, "Parse error"));
            return;
        }
        if (body.isArray()) {
            ArrayNode out = Json.MAPPER.createArrayNode();
            for (JsonNode m : body) {
                ObjectNode r = dispatch(m, agent);
                if (r != null) out.add(r);
            }
            if (out.isEmpty()) send(ex, 202, null);
            else send(ex, 200, out);
            return;
        }
        ObjectNode r = dispatch(body, agent);
        if (r == null) send(ex, 202, null);
        else send(ex, 200, r);
    }

    /** Null for notifications. */
    ObjectNode dispatch(JsonNode m, String agent) {
        JsonNode id = m.get("id");
        if (id == null || id.isNull()) return null;
        String method = m.path("method").asText();
        JsonNode params = m.path("params");
        return switch (method) {
            case "initialize" -> {
                ObjectNode r = Json.MAPPER.createObjectNode();
                r.put("protocolVersion", params.path("protocolVersion").asText("2025-06-18"));
                r.putObject("capabilities").putObject("tools");
                ObjectNode info = r.putObject("serverInfo");
                info.put("name", "juno");
                info.put("version", "2");
                yield result(id, r);
            }
            case "server/discover", "ping" -> result(id, Json.MAPPER.createObjectNode());
            case "tools/list" -> {
                ObjectNode r = Json.MAPPER.createObjectNode();
                ArrayNode tools = r.putArray("tools");
                for (String t : TOOLS) if (calls.refusal(agent, t) == null) tools.add(toolSchema(t, calls.promptValues()));
                yield result(id, r);
            }
            case "tools/call" -> result(id, call(agent, params.path("name").asText(), params.path("arguments")));
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private ObjectNode call(String agent, String name, JsonNode args) {
        try {
            if (!TOOLS.contains(name)) return text(common("unknown-tool", Map.of("tool", name)), true);
            String refused = calls.refusal(agent, name);
            if (refused != null) return text(refused, true);
            if (name.equals("request_permission")) {
                return text(calls.requestPermission(agent, args.path("tool_name").asText(), args.path("input"),
                        args.path("tool_use_id").asText("")).toString(), false);
            }
            Reply r = calls.call(agent, name, args.isMissingNode() || args.isNull() ? Json.MAPPER.createObjectNode() : args);
            return text(r.text(), r.error());
        } catch (RuntimeException e) {
            return text(common("call-failed", Map.of("reason", String.valueOf(e.getMessage()))), true);
        }
    }

    private static String common(String key, Map<String, ?> values) {
        return Prompts.render(Prompts.sections(Prompts.load("results/common.md")).get(key), values);
    }

    private static ObjectNode text(String text, boolean error) {
        ObjectNode r = Json.MAPPER.createObjectNode();
        ObjectNode c = r.putArray("content").addObject();
        c.put("type", "text");
        c.put("text", text);
        if (error) r.put("isError", true);
        return r;
    }

    /** A tool's description and parameters, from {@code prompts/tools/<tool>.md}. */
    static ObjectNode toolSchema(String tool, Map<String, ?> values) {
        ObjectNode t = Json.MAPPER.createObjectNode();
        t.put("name", tool);
        String file = Prompts.render(Prompts.load("tools/" + tool + ".md"), values);
        String[] parts = file.split("\n## Parameters\n", 2);
        String description = parts[0].replaceFirst("^# .*\n", "").trim();
        t.put("description", description);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        if (tool.equals("request_permission")) {
            props.putObject("tool_name").put("type", "string");
            props.putObject("input").put("type", "object");
            props.putObject("tool_use_id").put("type", "string");
            required.add("tool_name").add("input");
            return t;
        }
        Map<String, String> params = new LinkedHashMap<>();
        if (parts.length > 1) {
            for (String line : parts[1].split("\n")) {
                Matcher m = PARAM.matcher(line.trim());
                if (m.matches()) params.put(m.group(1), m.group(2));
            }
        }
        params.forEach((k, v) -> {
            ObjectNode p = props.putObject(k);
            p.put("type", TYPES.getOrDefault(k, "string"));
            p.put("description", v);
            if (!OPTIONAL.contains(tool + "." + k)) required.add(k);
        });
        return t;
    }

    private static ObjectNode result(JsonNode id, JsonNode r) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("jsonrpc", "2.0");
        o.set("id", id);
        o.set("result", r);
        return o;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.put("jsonrpc", "2.0");
        o.set("id", id);
        ObjectNode e = o.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return o;
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }
}
