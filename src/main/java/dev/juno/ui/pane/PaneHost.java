package dev.juno.ui.pane;

import dev.juno.model.Agent;

/** What a pane asks of the board it sits in (design §7.1): split, hide, maximise, and being used last. */
public interface PaneHost {
    /** Splits the pane: the new cell goes to its right (side by side) or below. */
    void split(String paneId, boolean sideBySide);

    /** Closing a pane hides the agent; it keeps running. */
    void hide(String paneId);

    void toggleMaximise(String paneId);

    boolean maximised(String paneId);

    /** The agent was deleted from its options menu. */
    void deleted(Agent a);

    /** The pane was clicked or focused: "+ New agent" splits the pane used last. */
    void used(String paneId);
}
