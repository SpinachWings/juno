package dev.juno.comms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Open links between two agents other than Juno (design §4.1): two-way, by agent id, saved with {@link CommsStore}.
 * Not thread-safe; the runtime guards it.
 */
public final class Links {
    /** One open link. {@code by} is "user" or "juno". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Link {
        public String a;
        public String b;
        public String by = "user";
        public long at;

        public Link() {}

        Link(String a, String b, String by) {
            this.a = a;
            this.b = b;
            this.by = by;
            this.at = System.currentTimeMillis();
        }

        public boolean joins(String x, String y) {
            return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
        }

        public boolean has(String x) {
            return a.equals(x) || b.equals(x);
        }

        public String other(String x) {
            return a.equals(x) ? b : a;
        }
    }

    private final List<Link> links;

    public Links(List<Link> links) {
        this.links = links;
    }

    public boolean linked(String x, String y) {
        return links.stream().anyMatch(l -> l.joins(x, y));
    }

    /** Opens a link; false if it was already open. */
    public boolean open(String x, String y, String by) {
        if (x.equals(y) || linked(x, y)) return false;
        links.add(new Link(x, y, by));
        return true;
    }

    /** Closes a link; false if there was none. */
    public boolean close(String x, String y) {
        return links.removeIf(l -> l.joins(x, y));
    }

    /** Removes every link of a deleted agent; returns the agents it was linked to. */
    public List<String> remove(String x) {
        List<String> others = of(x);
        links.removeIf(l -> l.has(x));
        return others;
    }

    /** The agents linked to {@code x}, in the order the links were opened. */
    public List<String> of(String x) {
        return links.stream().filter(l -> l.has(x)).map(l -> l.other(x)).toList();
    }

    public List<Link> all() {
        return List.copyOf(links);
    }
}
