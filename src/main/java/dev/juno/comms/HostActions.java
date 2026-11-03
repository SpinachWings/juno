package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.model.Key;
import java.util.List;
import java.util.Locale;

/**
 * What a call to a host's MCP server would do (design §6.3). A profile allows actions with {@code host:<action>} rules.
 * Only reading (PRs/MRs, threads, issues), opening a PR/MR and replying to a thread exist; every other tool of the host's
 * server (approve, merge, close, review, resolve, edit…) is unknown here and refused, whatever the profile says.
 */
public final class HostActions {
    public enum Action {
        READ, OPEN, REPLY;

        /** The rule that allows or disallows it, e.g. {@code host:reply}. */
        public String rule() {
            return "host:" + name().toLowerCase(Locale.ROOT);
        }
    }

    public static final String ANY = "host:*";

    private HostActions() {}

    public static boolean isRule(String rule) {
        return rule != null && rule.strip().startsWith("host:");
    }

    /** Whether the rule lists let an agent do it: disallowed rules win. */
    public static boolean allowed(Action action, List<String> allowed, List<String> disallowed) {
        return !names(disallowed, action) && names(allowed, action);
    }

    private static boolean names(List<String> rules, Action action) {
        return rules.stream().map(String::strip).anyMatch(r -> r.equals(ANY) || r.equals(action.rule()));
    }

    /** Whether the profile has any host rule at all: without one it gets no host MCP servers. */
    public static boolean anyAllowed(List<String> allowed) {
        return allowed.stream().anyMatch(HostActions::isRule);
    }

    /** What the call would do; null for anything unknown, which is refused. */
    public static Action classify(Key.Kind kind, String tool, JsonNode args) {
        if (reads(tool)) return Action.READ;
        if (kind == Key.Kind.GITLAB) {
            return switch (tool) {
                case "create_merge_request" -> Action.OPEN;
                case "save_merge_request" -> args.path("merge_request_iid").asText("").isBlank() ? Action.OPEN : null;
                case "save_note" -> args.path("discussion_id").asText("").isBlank() ? null : Action.REPLY;
                case "save_merge_request_review" -> "reply_discussion".equals(args.path("method").asText("")) ? Action.REPLY : null;
                default -> null;
            };
        }
        return switch (tool) {
            case "create_pull_request" -> Action.OPEN;
            case "add_reply_to_pull_request_comment" -> Action.REPLY;
            default -> null;
        };
    }

    /** A tool that can only read, by its name; used to list tools before any arguments exist. */
    private static boolean reads(String tool) {
        return tool.startsWith("get_") || tool.startsWith("list_") || tool.startsWith("search_")
                || tool.endsWith("_read") || tool.equals("actions_list") || tool.equals("actions_get");
    }

    /** The tool could do something the agent may do, for {@code tools/list} (arguments decide the rest at call time). */
    public static boolean listable(Key.Kind kind, String tool, List<String> allowed, List<String> disallowed) {
        for (Action a : actionsOf(kind, tool)) if (allowed(a, allowed, disallowed)) return true;
        return false;
    }

    private static List<Action> actionsOf(Key.Kind kind, String tool) {
        if (reads(tool)) return List.of(Action.READ);
        if (kind == Key.Kind.GITLAB) {
            return switch (tool) {
                case "create_merge_request" -> List.of(Action.OPEN);
                case "save_merge_request" -> List.of(Action.OPEN);
                case "save_note", "save_merge_request_review" -> List.of(Action.REPLY);
                default -> List.of();
            };
        }
        return switch (tool) {
            case "create_pull_request" -> List.of(Action.OPEN);
            case "add_reply_to_pull_request_comment" -> List.of(Action.REPLY);
            default -> List.of();
        };
    }

    /** The argument holding text people will read as the agent's reply, or null: it must carry the marker. */
    public static String commentField(Key.Kind kind, String tool, Action action) {
        if (action != Action.REPLY) return null;
        return kind == Key.Kind.GITLAB && tool.equals("save_merge_request_review") ? "note" : "body";
    }

    /** The argument naming a new PR/MR's base branch. */
    public static String baseField(Key.Kind kind) {
        return kind == Key.Kind.GITLAB ? "target_branch" : "base";
    }
}
