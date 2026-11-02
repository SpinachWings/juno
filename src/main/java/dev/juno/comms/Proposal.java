package dev.juno.comms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Something Juno asked for that waits for the user (design §4.1, §4.2): an assignment (unnamed, or a fresh start), a
 * task for an agent the user named during its undo window, or a link she suggested. Held by the server, saved, and
 * answered from the card, the inbox, or /approve and /deny.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Proposal {
    public enum Kind { ASSIGN, LINK }

    public String id;
    public Kind kind;
    public long created;

    /** ASSIGN: the agent, the task as Juno wrote it, whether it starts fresh, and whether the assignment itself
     * needs approval (unnamed agent or "Always"), as opposed to only the fresh start. */
    public String agentId;
    public String task = "";
    public boolean fresh;
    public boolean askAssign;
    /** Why it was held, for Juno's tool result and the card. */
    public String why = "";
    /** Set when the user named the agent: it goes by itself at this time unless undone (design §4.2). 0 = waits. */
    public long sendAt;

    /** LINK: the two agents and Juno's reason. */
    public String a;
    public String b;
    public String reason = "";

    public boolean involves(String agent) {
        return agent.equals(agentId) || agent.equals(a) || agent.equals(b);
    }
}
