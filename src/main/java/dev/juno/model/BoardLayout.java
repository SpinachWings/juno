package dev.juno.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.juno.store.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which pane is where (design §7.1): a binary tree of splits whose leaves are agents (or Juno's reserved pane). There
 * are no empty panes and no sizes: a split always halves its space. Hidden agents are not in the tree; each remembers
 * where it was, so showing it puts it back.
 */
public final class BoardLayout {
    /** Juno's pane: always in the tree. */
    public static final String JUNO = "juno";

    public abstract static sealed class Node permits Leaf, Split {}

    public static final class Leaf extends Node {
        public final String id;

        public Leaf(String id) {
            this.id = id;
        }
    }

    /** {@code sideBySide}: first on the left, second on the right; otherwise first on top, second below. */
    public static final class Split extends Node {
        public final boolean sideBySide;
        public Node first;
        public Node second;

        public Split(boolean sideBySide, Node first, Node second) {
            this.sideBySide = sideBySide;
            this.first = first;
            this.second = second;
        }
    }

    /** Where a hidden agent was: next to {@code neighbour}, on its {@code first} or second side. */
    public record Hint(String neighbour, boolean sideBySide, boolean first) {}

    private Node root = new Leaf(JUNO);
    private final Map<String, Hint> hidden = new LinkedHashMap<>();

    public Node root() {
        return root;
    }

    /** Ids of the panes shown, in reading order. */
    public List<String> leaves() {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node n, List<String> out) {
        if (n instanceof Leaf l) out.add(l.id);
        else if (n instanceof Split s) {
            collect(s.first, out);
            collect(s.second, out);
        }
    }

    public boolean shown(String id) {
        return leaves().contains(id);
    }

    public boolean isHidden(String id) {
        return hidden.containsKey(id);
    }

    /** Splits {@code target}'s pane: the new pane goes to its right (side by side) or below. */
    public void split(String target, boolean sideBySide, String newId) {
        hidden.remove(newId);
        root = replace(root, target, new Split(sideBySide, new Leaf(target), new Leaf(newId)));
    }

    /** Takes a pane out; its sibling takes the space. Hiding remembers where it was; removing forgets it. */
    public void hide(String id) {
        take(id, true);
    }

    public void remove(String id) {
        take(id, false);
        hidden.remove(id);
    }

    private void take(String id, boolean remember) {
        if (id.equals(JUNO) || !shown(id)) return;
        Split parent = parentOf(root, id);
        if (parent == null) return;
        boolean wasFirst = parent.first instanceof Leaf l && l.id.equals(id);
        Node sibling = wasFirst ? parent.second : parent.first;
        if (remember) hidden.put(id, new Hint(nearestLeaf(sibling, wasFirst), parent.sideBySide, wasFirst));
        root = replaceNode(root, parent, sibling);
    }

    /** Puts a hidden (or new) agent back next to where it was, else beside Juno. */
    public void show(String id) {
        if (shown(id)) return;
        Hint h = hidden.remove(id);
        if (h != null && shown(h.neighbour())) {
            Leaf self = new Leaf(id);
            Leaf there = new Leaf(h.neighbour());
            root = replace(root, h.neighbour(), new Split(h.sideBySide(), h.first() ? self : there,
                    h.first() ? there : self));
            return;
        }
        split(JUNO, true, id);
    }

    /** Turns a pane into another (a setup cell into the agent just created). */
    public void rename(String oldId, String newId) {
        hidden.remove(newId);
        root = replace(root, oldId, new Leaf(newId));
    }

    /** Exchanges two panes. */
    public void swap(String a, String b) {
        if (a.equals(b) || !shown(a) || !shown(b)) return;
        Leaf la = (Leaf) find(root, a), lb = (Leaf) find(root, b);
        root = replaceNode(replaceNode(root, la, new Leaf(b)), lb, new Leaf(a));
    }

    /** Keeps the tree true to the agents that exist: drops deleted ones, shows ones that were never placed. */
    public void reconcile(List<String> agentIds) {
        for (String id : leaves()) {
            if (!id.equals(JUNO) && !agentIds.contains(id)) remove(id);
        }
        hidden.keySet().removeIf(id -> !agentIds.contains(id));
        for (String id : agentIds) {
            if (!shown(id) && !hidden.containsKey(id)) {
                List<String> l = leaves();
                split(l.get(l.size() - 1), l.size() % 2 == 1, id);
            }
        }
    }

    private static Node find(Node n, String id) {
        if (n instanceof Leaf l) return l.id.equals(id) ? l : null;
        Split s = (Split) n;
        Node f = find(s.first, id);
        return f != null ? f : find(s.second, id);
    }

    private static Split parentOf(Node n, String id) {
        if (!(n instanceof Split s)) return null;
        if (s.first instanceof Leaf a && a.id.equals(id) || s.second instanceof Leaf b && b.id.equals(id)) return s;
        Split p = parentOf(s.first, id);
        return p != null ? p : parentOf(s.second, id);
    }

    /** A leaf of {@code n} next to the removed pane: the nearest one on the side it touched. */
    private static String nearestLeaf(Node n, boolean removedWasFirst) {
        while (n instanceof Split s) n = removedWasFirst ? s.first : s.second;
        return ((Leaf) n).id;
    }

    private static Node replace(Node n, String id, Node with) {
        if (n instanceof Leaf l) return l.id.equals(id) ? with : l;
        Split s = (Split) n;
        s.first = replace(s.first, id, with);
        s.second = replace(s.second, id, with);
        return s;
    }

    private static Node replaceNode(Node n, Node old, Node with) {
        if (n == old) return with;
        if (n instanceof Split s) {
            s.first = replaceNode(s.first, old, with);
            s.second = replaceNode(s.second, old, with);
        }
        return n;
    }

    // ---------------------------------------------------------------- JSON

    public ObjectNode toJson() {
        ObjectNode o = Json.MAPPER.createObjectNode();
        o.set("root", nodeJson(root));
        ObjectNode h = o.putObject("hidden");
        hidden.forEach((id, hint) -> {
            ObjectNode x = h.putObject(id);
            x.put("neighbour", hint.neighbour());
            x.put("sideBySide", hint.sideBySide());
            x.put("first", hint.first());
        });
        return o;
    }

    private static JsonNode nodeJson(Node n) {
        ObjectNode o = Json.MAPPER.createObjectNode();
        if (n instanceof Leaf l) {
            o.put("pane", l.id);
        } else {
            Split s = (Split) n;
            o.put("sideBySide", s.sideBySide);
            ArrayNode kids = o.putArray("children");
            kids.add(nodeJson(s.first));
            kids.add(nodeJson(s.second));
        }
        return o;
    }

    /** Reads a saved layout; anything unreadable gives the default (Juno alone). */
    public static BoardLayout fromJson(JsonNode j) {
        BoardLayout b = new BoardLayout();
        try {
            Node r = readNode(j.path("root"));
            if (r != null) b.root = r;
            j.path("hidden").fields().forEachRemaining(e -> b.hidden.put(e.getKey(), new Hint(
                    e.getValue().path("neighbour").asText(JUNO), e.getValue().path("sideBySide").asBoolean(true),
                    e.getValue().path("first").asBoolean(false))));
        } catch (RuntimeException e) {
            return new BoardLayout();
        }
        if (!b.shown(JUNO)) {
            Node old = b.root;
            b.root = new Split(true, new Leaf(JUNO), old);
        }
        return b;
    }

    private static Node readNode(JsonNode j) {
        if (j.has("pane")) return new Leaf(j.get("pane").asText());
        JsonNode kids = j.path("children");
        if (kids.size() != 2) return null;
        Node a = readNode(kids.get(0)), b = readNode(kids.get(1));
        if (a == null || b == null) return null;
        return new Split(j.path("sideBySide").asBoolean(true), a, b);
    }
}
