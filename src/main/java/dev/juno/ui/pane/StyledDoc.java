package dev.juno.ui.pane;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Text being rendered for the output view: styled spans, one paragraph style per line, and clickable ranges
 * (toggles, copy buttons). Offsets are relative to the start of this document.
 */
final class StyledDoc {
    record Span(int start, int end, List<String> styles) {}
    record Action(int start, int end, Runnable run) {}

    /** Hovering this range shows {@code text}: what a shortened piece of text said in full. */
    record Tip(int start, int end, String text) {}

    final StringBuilder text = new StringBuilder();
    final List<Span> spans = new ArrayList<>();
    /** One entry per line (paragraph), its style classes. */
    final List<List<String>> lineStyles = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();
    final List<Tip> tips = new ArrayList<>();
    private List<String> currentLine = new ArrayList<>();

    StyledDoc add(String s, String... styles) {
        if (s.isEmpty()) return this;
        // Keep lines intact: newlines inside a span end paragraphs with the current line style.
        String[] parts = s.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) newline();
            if (!parts[i].isEmpty()) {
                int start = text.length();
                text.append(parts[i]);
                spans.add(new Span(start, text.length(), List.of(styles)));
            }
        }
        return this;
    }

    StyledDoc action(String s, Runnable run, String... styles) {
        int start = text.length();
        add(s, styles);
        actions.add(new Action(start, text.length(), run));
        return this;
    }

    /**
     * The first line of {@code full}, cut at {@code max} characters (or where it has more lines) and ending in an
     * ellipsis then, with the whole text in a tooltip over it.
     */
    StyledDoc shortened(String full, int max, String... styles) {
        String t = full == null ? "" : full.strip();
        int nl = t.indexOf('\n');
        boolean cut = nl >= 0 || t.length() > max;
        String shown = nl >= 0 ? t.substring(0, Math.min(nl, max)) : t.substring(0, Math.min(t.length(), max));
        if (cut) shown += "…";
        int start = text.length();
        add(shown, styles);
        if (cut) tips.add(new Tip(start, text.length(), t));
        return this;
    }

    /** Puts {@code full} in a tooltip over what is written by {@code write}. */
    StyledDoc tip(String full, Runnable write) {
        int start = text.length();
        write.run();
        if (text.length() > start) tips.add(new Tip(start, text.length(), full));
        return this;
    }

    /** Sets the style classes of the line being written (e.g. indentation, code block background). */
    StyledDoc line(Collection<String> styles) {
        currentLine = new ArrayList<>(styles);
        return this;
    }

    StyledDoc newline() {
        text.append('\n');
        lineStyles.add(currentLine);
        currentLine = new ArrayList<>(currentLine);
        return this;
    }

    List<String> currentLineStyles() {
        return currentLine;
    }

    /** Appends another document, shifting its offsets. */
    void append(StyledDoc other) {
        int base = text.length();
        // A partial current line can't be merged; callers end blocks with newline().
        text.append(other.text);
        for (Span s : other.spans) spans.add(new Span(s.start() + base, s.end() + base, s.styles()));
        for (Action a : other.actions) actions.add(new Action(a.start() + base, a.end() + base, a.run()));
        for (Tip t : other.tips) tips.add(new Tip(t.start() + base, t.end() + base, t.text()));
        lineStyles.addAll(other.lineStyles);
    }

    int length() {
        return text.length();
    }
}
