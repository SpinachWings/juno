package dev.juno;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.store.Json;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for Claude Code in tests (not a test): reads stream-json messages, answers each with one reply and a
 * result. A line {@code CALL <tool> <json>} in a message calls that Juno MCP tool with the agent's own token first; the
 * reply lists the results. A line {@code ASK} makes the reply end with a ❓ question. Every message it got is appended
 * to {@code <log>}, so tests can see exactly what reached the agent.
 * Usage: {@code FakeClaude <mcp.json> <session id> <log>}.
 */
public final class FakeClaude {
    private static final Pattern CALL = Pattern.compile("^CALL (\\w+) (\\{.*})\\s*$");

    public static void main(String[] args) throws Exception {
        JsonNode server = Json.MAPPER.readTree(Files.readString(Path.of(args[0]))).path("mcpServers").path("juno");
        String url = server.path("url").asText();
        String auth = server.path("headers").path("Authorization").asText();
        String session = args[1];
        Path log = Path.of(args[2]);
        HttpClient http = HttpClient.newHttpClient();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        boolean started = false;
        int n = 0;
        String line;
        while ((line = in.readLine()) != null) {
            JsonNode m = Json.MAPPER.readTree(line);
            if (!m.path("type").asText().equals("user")) continue;
            String text = m.path("message").path("content").asText();
            Files.writeString(log, text + "\n----\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            if (!started) {
                out("{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\".\",\"session_id\":\"" + session + "\",\"model\":\"fake\"}");
                started = true;
            }
            List<String> results = new ArrayList<>();
            boolean ask = false;
            for (String l : text.split("\n")) {
                Matcher c = CALL.matcher(l.strip());
                if (c.matches()) results.add(c.group(1) + ": " + call(http, url, auth, c.group(1), c.group(2)));
                if (l.strip().equals("ASK")) ask = true;
            }
            String reply = (results.isEmpty() ? "ok" : String.join("\n", results)) + (ask ? "\n\n❓ Which one?" : "");
            n++;
            ObjectNode a = Json.MAPPER.createObjectNode();
            a.put("type", "assistant");
            ObjectNode msg = a.putObject("message");
            msg.put("id", "m" + n);
            msg.put("model", "fake");
            msg.putArray("content").addObject().put("type", "text").put("text", reply);
            msg.putObject("usage").put("input_tokens", 1000).put("output_tokens", 10);
            a.putNull("parent_tool_use_id");
            out(a.toString());
            out("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"total_cost_usd\":0.0,"
                    + "\"num_turns\":1,\"modelUsage\":{\"fake\":{\"contextWindow\":10000}}}");
        }
    }

    private static String call(HttpClient http, String url, String auth, String tool, String args) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + args + "}}";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", auth)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode res = Json.MAPPER.readTree(r.body()).path("result");
        return res.path("content").get(0).path("text").asText();
    }

    private static void out(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
