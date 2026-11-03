package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * An agent (design §3): saved in {@code ~/.juno/agents/<id>/agent.json} as things happen (§12). Runtime-only state
 * (the process, pending requests) lives in {@code runtime}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Agent {
    public enum Status {
        @com.fasterxml.jackson.annotation.JsonEnumDefaultValue IDLE("Idle"), QUEUED("Queued"), WORKING("Working"),
        AWAITING("Awaiting decision"), DONE("Done");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /** Why an agent is Awaiting decision (design §3). */
    public enum Reason {
        QUESTION("❓", "question"), PERMISSION("🔐", "permission"), FOLDER("📁", "folder"), KEY("🔑", "key"),
        FAILED("⚠", "failed"), INTERRUPTED("⏸", "interrupted"), PROFILE("⚙", "profile");

        public final String icon;
        public final String label;

        Reason(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    public String id;
    public String name = "";
    public String colour = "#6FD6FF";
    public String profile = "";
    /** Project id; null for none (design §7.7). */
    public String projectId;
    public Status status = Status.IDLE;
    public Reason reason;
    /** One line shown with the status (e.g. the failure). */
    public String statusDetail = "";
    public long statusSince;
    public boolean unread;
    /** Claude Code's conversation id; null until the first message of a conversation. */
    public String sessionId;
    /** Juno's id for the current conversation (names its journal). */
    public String conversationId;
    public List<Conversation> conversations = new ArrayList<>();
    /** Per-agent overrides (same as /model, /effort); blank = the profile's. */
    public String modelOverride = "";
    public String effortOverride = "";
    public List<AgentRule> rules = new ArrayList<>();
    /** A skill to load with the next message (/activate), as the agent names it. */
    public String pendingSkill;
    /** A message held until the agent has a folder (design §7.7). */
    public String heldMessage;
    /** Set while a turn runs, so a crash can be told from an idle shutdown. */
    public boolean turnOpen;
    /** Stopped by the user mid-turn (or interrupted); Resume sends the recap. */
    public boolean interrupted;
    public String interruptReason = "";
    public long interruptedAt;
    public long created;
    /** GitHub and GitLab keys (Keys tab ids), one of each at most; null for none (design §5.1.1). */
    public String githubKeyId;
    public String gitlabKeyId;
    /** A message waiting for a free slot while the agent is Queued (design §3), and who sent it. */
    public String queuedMessage;
    public String queuedFrom = "user";
    /** The branch checked out in the agent's folder, as last seen. */
    public String branch = "";
    /** Place in the queue (1 = next) while Queued. */
    public int queuePlace;
    /** The task from Juno (or /assign) the agent is on; its report goes to Juno (design §4.2, §4.3). Null for none. */
    public Task task;
    /** What the agent passed to {@code report} this turn; sent to Juno when the turn ends. */
    public String reportDraft;
    /** The last summary reported to Juno, for the context summary. */
    public String lastReport = "";
    /** Notices for the agent (link changes, loop guard, notes to Juno): sent with its next message, never on their own. */
    public List<String> notices = new ArrayList<>();
    /** Tasks waiting for the agent's current turn to end. */
    public List<PendingTask> pendingTasks = new ArrayList<>();
    /** Hops of the agent-to-agent chain it is in (design §4.4 loop guard); 0 after a message from the user. */
    public int hop;
    /** Juno only: reports that arrived while she worked, sent as one digest when she is next idle (design §4.3). */
    public List<String> pendingReports = new ArrayList<>();

    /** A task from Juno or /assign (design §4.2). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Task {
        public String gist = "";
        /** Written by the user (/assign) rather than by Juno. */
        public boolean byUser;
        public long at;
        /** A report reached Juno for it (the final reply is used once when the agent never calls report). */
        public boolean reported;
    }

    /** A task to deliver once the agent's current turn ends. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PendingTask {
        public String text = "";
        public boolean fresh;
        public boolean byUser;
    }

    /** An earlier conversation (design §7.9). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Conversation {
        public String id;
        public String sessionId;
        public long started;
        public long ended;
        public String firstTask = "";
        /** Size when the conversation was left, in tokens, and the last reply, for the conversations list. */
        public long tokens;
        public String summary = "";
    }

    /** "Always allow for this agent" (design §7.5). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentRule {
        public String rule = "";
        public String description = "";
        public long added;
        /** What it was first allowed for, e.g. the command. */
        public String why = "";

        public AgentRule() {}

        public AgentRule(String rule, String description, String why) {
            this.rule = rule;
            this.description = description;
            this.why = why;
            this.added = System.currentTimeMillis();
        }
    }

    public static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) return n + "th";
        return n + switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
    }

    public String statusText() {
        if (status == Status.AWAITING && reason != null) return reason.icon + " " + status.label;
        if (status == Status.QUEUED && queuePlace > 0) return status.label + " · " + ordinal(queuePlace);
        return status.label;
    }
}
