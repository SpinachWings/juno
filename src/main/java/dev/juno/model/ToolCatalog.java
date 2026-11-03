package dev.juno.model;

import java.util.List;

/**
 * The tools a profile can allow or disallow, for the tools reference in Settings → Agent profiles (design §5.4).
 * The host (GitHub / GitLab) entries are shorthands for the host's tools, which Juno expands into Claude Code's own rules (design §6.3).
 */
public final class ToolCatalog {
    public enum Group {
        FILES("Files and search"), SHELL("Shell"), WEB("Web"), AGENTS("Sub-agents"), JUNO("Juno tools"),
        HOST("GitHub / GitLab");

        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    public record Tool(String rule, Group group, String what) {}

    private static final List<Tool> TOOLS = List.of(
            new Tool("Read", Group.FILES, "Read files."),
            new Tool("Grep", Group.FILES, "Search file contents."),
            new Tool("Glob", Group.FILES, "Find files by name pattern."),
            new Tool("Edit", Group.FILES, "Change files."),
            new Tool("Write", Group.FILES, "Create or overwrite files."),
            new Tool("NotebookEdit", Group.FILES, "Edit Jupyter notebook cells."),
            new Tool("Bash", Group.SHELL, "Any shell command. Prefer narrower rules below."),
            new Tool("Bash(git:*)", Group.SHELL, "Any git command. Pair with a disallowed rule such as "
                    + "Bash(git push --force:*)."),
            new Tool("Bash(git diff:*)", Group.SHELL, "Read-only git: diffs."),
            new Tool("Bash(git log:*)", Group.SHELL, "Read-only git: history."),
            new Tool("Bash(git show:*)", Group.SHELL, "Read-only git: show a commit."),
            new Tool("Bash(npm test:*)", Group.SHELL, "Example of a command prefix rule: anything starting with "
                    + "`npm test`. A project's build & test commands are added automatically for agents that "
                    + "edit files."),
            new Tool("WebSearch", Group.WEB, "Search the web."),
            new Tool("WebFetch", Group.WEB, "Fetch and read a web page."),
            new Tool("Task", Group.AGENTS, "Start a sub-agent (shown nested in the pane). Superpowers uses these."),
            new Tool("mcp__juno__list_agents", Group.JUNO, "Every profile. Agents the caller may talk to: name, "
                    + "profile, project, status, context use."),
            new Tool("mcp__juno__send_message", Group.JUNO, "Every profile. Message a linked agent; refused "
                    + "without a link."),
            new Tool("mcp__juno__report", Group.JUNO, "Every profile. Send a result or question to whoever "
                    + "assigned the current task."),
            new Tool("mcp__juno__request_folder", Group.JUNO, "Every profile. Ask the user to give the agent a "
                    + "project."),
            new Tool("mcp__juno__assign_task", Group.JUNO, "Orchestrator only. Hand an agent a task."),
            new Tool("mcp__juno__hand_over", Group.JUNO, "Give work to a linked agent that edits files and is idle."),
            new Tool("mcp__juno__set_link", Group.JUNO, "Orchestrator only. Open or close a link between agents."),
            new Tool("host:read", Group.HOST, "Run the host's read tools without asking: PRs/MRs, diffs, files, commits, review threads, issues."),
            new Tool("host:open", Group.HOST, "Open a PR/MR without asking."),
            new Tool("host:reply", Group.HOST, "Reply to a review thread without asking (replies carry the 🤖 marker)."),
            new Tool("host:*", Group.HOST, "Every tool of the host's server, merge and close included, without asking."));

    private ToolCatalog() {}

    public static List<Tool> all() {
        return TOOLS;
    }

    /** Text shown above the table: how rules are written. */
    public static String syntaxHelp() {
        return "Rules go one per line. A bare name (Read) allows that tool. Bash(prefix:*) matches shell commands "
                + "starting with the prefix. mcp__<server>__<tool> names a tool of an MCP server. Disallowed rules "
                + "always win over allowed ones. host:<action> rules give the agent the GitHub / GitLab tools "
                + "and let it run those actions without asking; Claude Code asks you about every other tool of the host. "
                + "Without one the agent gets no GitHub / GitLab tools.";
    }
}
