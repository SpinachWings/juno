package dev.juno.runtime;

import dev.juno.model.Agent;
import dev.juno.store.JunoHome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Earlier conversations and the data limits on them (design §7.9). */
public final class Conversations {
    /** An agent keeps this many earlier conversations… */
    public static final int KEEP = 10;
    /** …none older than this many days. */
    public static final int MAX_DAYS = 30;

    private Conversations() {}

    /** The earlier conversations (everything but the current one), newest first. */
    public static List<Agent.Conversation> earlier(Agent a) {
        List<Agent.Conversation> out = new ArrayList<>();
        for (Agent.Conversation c : a.conversations) if (!c.id.equals(a.conversationId)) out.add(c);
        out.sort(Comparator.comparingLong((Agent.Conversation c) -> c.ended > 0 ? c.ended : c.started).reversed());
        return out;
    }

    /**
     * Drops earlier conversations beyond the limits (last 10, none older than 30 days) and deletes Juno's journal and
     * log for them. Claude Code's own transcripts are never touched. Returns the dropped ones.
     */
    public static List<Agent.Conversation> applyLimits(JunoHome home, Agent a, long now) {
        List<Agent.Conversation> dropped = new ArrayList<>();
        List<Agent.Conversation> earlier = earlier(a);
        long cutoff = now - MAX_DAYS * 86_400_000L;
        for (int i = 0; i < earlier.size(); i++) {
            Agent.Conversation c = earlier.get(i);
            long when = c.ended > 0 ? c.ended : c.started;
            if (i >= KEEP || (when > 0 && when < cutoff)) dropped.add(c);
        }
        for (Agent.Conversation c : dropped) {
            a.conversations.remove(c);
            deleteFiles(home, a.id, c.id);
        }
        return dropped;
    }

    /** Juno's journal and log (and the one rotated copy) of a conversation. */
    public static void deleteFiles(JunoHome home, String agentId, String conversationId) {
        for (Path p : List.of(home.journalsDir(agentId).resolve(conversationId + ".jsonl"),
                home.logsDir(agentId).resolve(conversationId + ".log"),
                home.logsDir(agentId).resolve(conversationId + ".log.1"))) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                System.err.println("Couldn't delete " + p + ": " + e.getMessage());
            }
        }
    }

    /** Bytes of Juno's journal and log files for one conversation. */
    public static long size(JunoHome home, String agentId, String conversationId) {
        long total = 0;
        for (Path p : List.of(home.journalsDir(agentId).resolve(conversationId + ".jsonl"),
                home.logsDir(agentId).resolve(conversationId + ".log"),
                home.logsDir(agentId).resolve(conversationId + ".log.1"))) {
            try {
                if (Files.isRegularFile(p)) total += Files.size(p);
            } catch (IOException e) {
                // gone meanwhile
            }
        }
        return total;
    }

    /** Claude Code's transcripts of a session: {@code projects/<folder>/<session>.jsonl} (and a folder of that name). */
    public static List<Path> transcripts(Path claudeDir, String sessionId) {
        List<Path> out = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("/") || sessionId.contains("\\")) return out;
        Path projects = claudeDir.resolve("projects");
        if (!Files.isDirectory(projects)) return out;
        try (Stream<Path> dirs = Files.list(projects)) {
            for (Path d : (Iterable<Path>) dirs::iterator) {
                for (Path c : List.of(d.resolve(sessionId + ".jsonl"), d.resolve(sessionId))) {
                    if (Files.exists(c)) out.add(c);
                }
            }
        } catch (IOException e) {
            // unreadable: treated as none
        }
        return out;
    }

    /** Whether Claude Code still has the conversation, so it can be resumed by id. */
    public static boolean available(Path claudeDir, Agent.Conversation c) {
        return c.sessionId != null && !c.sessionId.isBlank()
                && transcripts(claudeDir, c.sessionId).stream().anyMatch(p -> p.toString().endsWith(".jsonl"));
    }
}
