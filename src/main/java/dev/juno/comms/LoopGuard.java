package dev.juno.comms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The loop guard (design §4.4). Every agent carries the hop count of the agent-to-agent chain it is in: a message
 * carries its sender's count plus one and gives it to the recipient, so a chain through several agents is counted as
 * one. A message beyond the hop limit is refused, and the pair is remembered as stopped. A message from the user to an
 * agent sets its count back to 0, and to 0 for every agent it was stopped with.
 */
public final class LoopGuard {
    private final Set<String> stopped = new HashSet<>();

    /** The hop a message from an agent at {@code senderHop} carries, or -1 if it would pass the limit. */
    public static int nextHop(int senderHop, int limit) {
        int hop = senderHop + 1;
        return hop > Math.max(1, limit) ? -1 : hop;
    }

    public synchronized void stop(String a, String b) {
        stopped.add(key(a, b));
    }

    public synchronized boolean isStopped(String a, String b) {
        return stopped.contains(key(a, b));
    }

    /** The user messaged {@code agent}: the agents whose count resets with it (not including itself). */
    public synchronized List<String> reset(String agent) {
        List<String> others = new ArrayList<>();
        stopped.removeIf(k -> {
            String[] p = k.split("\n");
            if (p[0].equals(agent)) others.add(p[1]);
            else if (p[1].equals(agent)) others.add(p[0]);
            else return false;
            return true;
        });
        return others;
    }

    public synchronized void forget(String agent) {
        reset(agent);
    }

    private static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + "\n" + b : b + "\n" + a;
    }
}
