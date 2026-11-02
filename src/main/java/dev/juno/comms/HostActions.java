package dev.juno.comms;

import com.fasterxml.jackson.databind.JsonNode;
import dev.juno.model.Key;
import java.util.List;
import java.util.Locale;

/**
 * What a call to a host's MCP server would do (design §6.6). On both hosts the dangerous actions are arguments of tools
 * a profile needs, so the proxy decides by action, not by tool name. A profile allows actions with {@code host:<action>}
 * rules. Anything not recognised here is refused: a new tool on the host's server does nothing until it's listed.
 */
public final class HostActions {
    public enum Action {
        READ, OPEN, REPLY, REVIEW, RESOLVE, APPROVE, REQUEST_CHANGES, MERGE, CLOSE, EDIT;

        /** The rule that allows or disallows it, e.g. {@code host:request-changes}. */
        public String rule() {
            return "host:" + name().toLowerCase(Locale.ROOT).replace('_', '-');
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
        return kind == Key.Kind.GITLAB ? gitlab(tool, args) : github(tool, args);
    }

    /** A tool that can only read, by its name; used to list tools before any arguments exist. */
    private static boolean reads(String tool) {
        return tool.startsWith("get_") || tool.startsWith("list_") || tool.startsWith("search_")
                || tool.endsWith("_read") || tool.equals("actions_list") || tool.equals("actions_get");
    }

    private static Action github(String tool, JsonNode args) {
        if (reads(tool)) return Action.READ;
        String method = args.path("method").asText("");
        String event = args.path("event").asText("");
        return switch (tool) {
            case "create_pull_request" -> Action.OPEN;
            case "add_reply_to_pull_request_comment" -> Action.REPLY;
            case "add_comment_to_pending_review", "add_issue_comment" -> Action.REVIEW;
            case "merge_pull_request" -> Action.MERGE;
            case "update_pull_request" -> args.path("state").asText("").equalsIgnoreCase("closed") ? Action.CLOSE
                    : Action.EDIT;
            case "update_pull_request_branch" -> Action.EDIT;
            case "pull_request_review_write" -> switch (method) {
                case "resolve_thread", "unresolve_thread" -> Action.RESOLVE;
                case "create", "submit_pending" -> byEvent(event);
                case "delete_pending" -> Action.REVIEW;
                default -> null;
            };
            default -> null;
        };
    }

    /** No event yet means a pending review; submitting one with no event is refused as unknown. */
    private static Action byEvent(String event) {
        return switch (event.toUpperCase(Locale.ROOT)) {
            case "", "COMMENT" -> Action.REVIEW;
            case "APPROVE" -> Action.APPROVE;
            case "REQUEST_CHANGES" -> Action.REQUEST_CHANGES;
            default -> null;
        };
    }

    private static Action gitlab(String tool, JsonNode args) {
        if (reads(tool)) return Action.READ;
        String method = args.path("method").asText("");
        return switch (tool) {
            case "create_merge_request" -> Action.OPEN;
            case "save_merge_request" -> {
                if (args.path("merge_request_iid").asText("").isBlank()) yield Action.OPEN;
                yield args.path("state_event").asText("").equalsIgnoreCase("close") ? Action.CLOSE : Action.EDIT;
            }
            case "accept_merge_request" -> Action.MERGE;
            case "save_note" -> args.path("discussion_id").asText("").isBlank() ? Action.REVIEW : Action.REPLY;
            case "save_merge_request_review" -> {
                if (!args.path("verdict").asText("").isBlank()) {
                    yield args.path("verdict").asText("").equalsIgnoreCase("comment") ? Action.REVIEW
                            : Action.REQUEST_CHANGES;
                }
                yield switch (method) {
                    case "create_diff_note", "create_note", "submit_review" -> Action.REVIEW;
                    case "reply_discussion" -> Action.REPLY;
                    case "resolve_discussion", "unresolve_discussion" -> Action.RESOLVE;
                    case "approve" -> Action.APPROVE;
                    default -> null;
                };
            }
            default -> null;
        };
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
                case "save_merge_request" -> List.of(Action.OPEN, Action.CLOSE, Action.EDIT);
                case "accept_merge_request" -> List.of(Action.MERGE);
                case "save_note" -> List.of(Action.REVIEW, Action.REPLY);
                case "save_merge_request_review" -> List.of(Action.REVIEW, Action.REPLY, Action.RESOLVE, Action.APPROVE,
                        Action.REQUEST_CHANGES);
                default -> List.of();
            };
        }
        return switch (tool) {
            case "create_pull_request" -> List.of(Action.OPEN);
            case "add_reply_to_pull_request_comment" -> List.of(Action.REPLY);
            case "add_comment_to_pending_review", "add_issue_comment" -> List.of(Action.REVIEW);
            case "merge_pull_request" -> List.of(Action.MERGE);
            case "update_pull_request" -> List.of(Action.CLOSE, Action.EDIT);
            case "update_pull_request_branch" -> List.of(Action.EDIT);
            case "pull_request_review_write" -> List.of(Action.REVIEW, Action.RESOLVE, Action.APPROVE,
                    Action.REQUEST_CHANGES);
            default -> List.of();
        };
    }

    /** The argument holding text people will read as the agent's comment, or null: it must carry the marker (§6.3). */
    public static String commentField(Key.Kind kind, String tool, Action action) {
        if (action != Action.REVIEW && action != Action.REPLY) return null;
        return kind == Key.Kind.GITLAB && tool.equals("save_merge_request_review") ? "note" : "body";
    }

    /** The argument naming the commit a review is anchored to, or null. */
    public static String commitField(Key.Kind kind) {
        return kind == Key.Kind.GITLAB ? "sha" : "commitID";
    }

    /** The argument naming the thread to resolve. */
    public static String threadField(Key.Kind kind) {
        return kind == Key.Kind.GITLAB ? "discussion_id" : "threadId";
    }

    /** The argument naming a new PR/MR's base branch. */
    public static String baseField(Key.Kind kind) {
        return kind == Key.Kind.GITLAB ? "target_branch" : "base";
    }
}
