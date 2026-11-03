package dev.juno.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** System settings (design §5.2). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemSettings {
    /** Blank = {@code claude} on PATH. */
    public String claudePath = "";
    /** Juno asks before assigning (design §4.2). */
    public AssignApproval assignApproval = AssignApproval.UNNAMED;
    public boolean tellJunoAboutDirectTasks = true;
    /** Percent of the context window after which a fresh start is proposed. */
    public int freshStartThreshold = 60;
    public int reportLength = 800;
    public int hopLimit = 10;
    public int maxAgentsWorking = 4;
    public int idleTimeoutMinutes = 10;
    public String notesFolder = "";
    public boolean firstRunDone = false;

    public enum AssignApproval {
        UNNAMED("Only when I didn't name the agent"), ALWAYS("Always"), NEVER("Never");

        public final String label;

        AssignApproval(String label) {
            this.label = label;
        }
    }

    public String effectiveClaudePath() {
        return claudePath == null || claudePath.isBlank() ? "claude" : claudePath.trim();
    }
}
