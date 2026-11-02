package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.comms.HostApi.HostException;
import dev.juno.store.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A client for a host's MCP server (streamable HTTP, JSON or event-stream answers), with the key's token in its header.
 * Holds the session the server gives on {@code initialize}; GitHub answers an HTML "Bad request" without the protocol
 * header (docs/progress.md, Milestone 5a).
 */
public final class McpClient {
    private static final String PROTOCOL = "2025-06-18";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final String url;
    private final String token;
    private final AtomicLong ids = new AtomicLong();
    private volatile String session;
    private volatile boolean ready;

    public McpClient(String url, String token) {
        this.url = url;
        this.token = token;
    }

    /** {@code tools/list}: the tools the server offers. */
    public JsonNode tools() throws IOException {
        return call("tools/list", Json.MAPPER.createObjectNode()).path("tools");
    }

    /** {@code tools/call}: the server's result as it is. */
    public JsonNode callTool(String name, JsonNode args) throws IOException {
        ObjectNode params = Json.MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", args);
        return call("tools/call", params);
    }

    private JsonNode call(String method, JsonNode params) throws IOException {
        if (!ready) initialize();
        try {
            return exchange(method, params);
        } catch (HostException e) {
            if (e.status != 400 && e.status != 404) throw e;
            initialize(); // the session expired
            return exchange(method, params);
        }
    }

    private synchronized void initialize() throws IOException {
        session = null;
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("protocolVersion", PROTOCOL);
        p.putObject("capabilities");
        ObjectNode info = p.putObject("clientInfo");
        info.put("name", "juno");
        info.put("version", "2");
        exchange("initialize", p);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", "notifications/initialized");
        post(n.toString());
        ready = true;
    }

    private JsonNode exchange(String method, JsonNode params) throws IOException {
        ObjectNode m = Json.MAPPER.createObjectNode();
        m.put("jsonrpc", "2.0");
        long id = ids.incrementAndGet();
        m.put("id", id);
        m.put("method", method);
        m.set("params", params);
        HttpResponse<String> r = post(m.toString());
        if (r.statusCode() >= 300) {
            throw new HostException(r.statusCode(), "the MCP server answered HTTP " + r.statusCode(),
                    r.headers().firstValue("x-accepted-github-permissions").orElse(null));
        }
        JsonNode reply = parse(r);
        if (reply.has("error")) {
            throw new HostException(0, reply.at("/error/message").asText("the MCP server refused the call"), null);
        }
        return reply.path("result");
    }

    private HttpResponse<String> post(String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("Mcp-Protocol-Version", PROTOCOL);
        if (session != null) b.header("Mcp-Session-Id", session);
        try {
            HttpResponse<String> r = HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            r.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HostException(0, "interrupted", null);
        } catch (IOException e) {
            throw new HostException(0, "no answer from the MCP server (" + e.getClass().getSimpleName() + ")", null);
        }
    }

    private static JsonNode parse(HttpResponse<String> r) throws IOException {
        String type = r.headers().firstValue("Content-Type").orElse("");
        if (!type.contains("event-stream")) return Json.MAPPER.readTree(r.body());
        JsonNode last = Json.MAPPER.createObjectNode();
        for (String line : r.body().split("\r?\n")) {
            if (line.startsWith("data:")) last = Json.MAPPER.readTree(line.substring(5).strip());
        }
        return last;
    }
}
