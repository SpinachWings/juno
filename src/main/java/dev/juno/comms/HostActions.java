package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.model.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Host MCP access (design §6.3). A profile lists {@code host:read}, {@code host:open} and {@code host:reply}: shorthands
 * that Juno turns into Claude Code's own rules for the host's tools ({@code mcp__github__issue_read}…) when the process
 * starts. Claude Code then runs those without asking and asks you (through Juno's permission prompt) about every other
 * tool of the host's server. The proxy itself no longer decides anything: it hides the token, marks replies and notes
 * opened PRs/MRs.
 */
public final class HostActions {
    public enum Action {
        READ, OPEN, REPLY;

        /** The rule that allows or disallows it, e.g. {@code host:reply}. */
        public String rule() {
            return "host:" + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Every tool of the host's servers. */
    public static final String ANY = "host:*";

    private HostActions() {}

    public static boolean isRule(String rule) {
        return rule != null && rule.strip().startsWith("host:");
    }

    /** Whether the profile has any host rule at all: without one it gets no host MCP servers. */
    public static boolean anyAllowed(List<String> allowed) {
        return allowed.stream().anyMatch(HostActions::isRule);
    }

    /** The tools of one host that an action covers, by their names on the host's server. */
    public static List<String> tools(Key.Kind kind, Action action) {
        if (kind == Key.Kind.GITLAB) {
            return switch (action) {
                case READ -> List.of("get_merge_request", "get_issue", "list_issues", "list_merge_requests", "get_project");
                case OPEN -> List.of("create_merge_request");
                case REPLY -> List.of("save_note");
            };
        }
        return switch (action) {
            case READ -> List.of("pull_request_read", "issue_read", "list_issues", "search_issues", "list_pull_requests",
                    "search_pull_requests", "get_file_contents", "list_commits", "get_commit", "list_branches");
            case OPEN -> List.of("create_pull_request");
            case REPLY -> List.of("add_reply_to_pull_request_comment");
        };
    }

    /**
     * Claude Code rules for one host server ({@code github} / {@code gitlab}): {@code host:*} is the whole server,
     * {@code host:read} and the others the tools above; anything else in {@code rules} is not a host rule and is ignored.
     */
    public static List<String> expand(String server, Key.Kind kind, List<String> rules) {
        List<String> out = new ArrayList<>();
        for (String r : rules) {
            String rule = r.strip();
            if (rule.equals(ANY)) out.add("mcp__" + server);
            for (Action a : Action.values()) {
                if (rule.equals(a.rule())) for (String t : tools(kind, a)) out.add("mcp__" + server + "__" + t);
            }
        }
        return out;
    }

    /** Whether the call opens a PR/MR (the PRs tab gets an entry when it succeeds). */
    public static boolean isOpen(Key.Kind kind, String tool, JsonNode args) {
        if (kind == Key.Kind.GITLAB) {
            return tool.equals("create_merge_request")
                    || (tool.equals("save_merge_request") && args.path("merge_request_iid").asText("").isBlank());
        }
        return tool.equals("create_pull_request");
    }

    /** The argument holding text people will read as the agent's reply, or null: it must carry the marker. */
    public static String commentField(Key.Kind kind, String tool, JsonNode args) {
        if (kind == Key.Kind.GITLAB) {
            if (tool.equals("save_note")) return "body";
            return tool.equals("save_merge_request_review") && "reply_discussion".equals(args.path("method").asText(""))
                    ? "note" : null;
        }
        return tool.equals("add_reply_to_pull_request_comment") ? "body" : null;
    }
}
