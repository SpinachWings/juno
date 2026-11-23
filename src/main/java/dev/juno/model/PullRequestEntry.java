package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.store.Json;
import java.util.UUID;

/** A PR/MR an agent opened, as listed in the PRs tab (design §7.11). It stays until you close the entry. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PullRequestEntry {
    public String id = UUID.randomUUID().toString();
    public String agentId = "";
    public String agentName = "";
    /** {@code owner/repo} or {@code group/project}; blank when unknown. */
    public String repo = "";
    /** {@code #42} or {@code !42}; blank when the host's answer had none. */
    public String number = "";
    public String title = "";
    /** The branch the PR/MR comes from. */
    public String branch = "";
    public String url = "";
    public long openedAt;

    /**
     * Reads an entry from what the agent asked for and what the host answered. The host's answer wins; the arguments and
     * the agent's project fill what it lacks. Never null.
     */
    public static PullRequestEntry fromOpen(Key.Kind kind, JsonNode args, JsonNode result, String projectRemote, Agent agent) {
        JsonNode answer = answer(result);
        PullRequestEntry e = new PullRequestEntry();
        e.agentId = agent.id;
        e.agentName = agent.name;
        e.openedAt = System.currentTimeMillis();
        boolean gitlab = kind == Key.Kind.GITLAB;
        String n = first(answer.path(gitlab ? "iid" : "number"), answer.path("number"), answer.path("iid"));
        e.url = first(answer.path("html_url"), answer.path("web_url"), answer.path("url"));
        if (n.isBlank()) { // GitHub's MCP answer can carry only the link
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(?:pull|merge_requests)/(\\d+)").matcher(e.url);
            if (m.find()) n = m.group(1);
        }
        e.number = n.isBlank() ? "" : (gitlab ? "!" : "#") + n;
        e.title = first(answer.path("title"), args.path("title"));
        e.branch = first(args.path(gitlab ? "source_branch" : "head"), answer.at("/head/ref"), answer.path("source_branch"));
        String repo = gitlab ? first(args.path("project_id")) : first(args.path("owner")) + "/" + first(args.path("repo"));
        e.repo = repo.equals("/") || repo.isBlank() ? projectRemote : repo;
        if (e.repo.matches("[0-9]+")) e.repo = projectRemote; // a numeric GitLab project id isn't a name
        return e;
    }

    /** The host's answer is JSON inside the first text block of the tool result (or the result itself). */
    private static JsonNode answer(JsonNode result) {
        String text = result == null ? "" : result.at("/content/0/text").asText("");
        if (!text.isBlank()) {
            try {
                return Json.MAPPER.readTree(text);
            } catch (java.io.IOException e) {
                // not JSON: fall through
            }
        }
        return result == null ? Json.MAPPER.createObjectNode() : result;
    }

    private static String first(JsonNode... nodes) {
        for (JsonNode n : nodes) if (n != null && !n.isMissingNode() && !n.isNull() && !n.asText("").isBlank()) return n.asText();
        return "";
    }
}
