package dev.juno.store;

import java.nio.file.Path;

/** The data folder: {@code ~/.juno}, or {@code JUNO_HOME} when set. */
public final class JunoHome {
    private final Path root;

    public JunoHome(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static JunoHome fromEnvironment() {
        String env = System.getenv("JUNO_HOME");
        if (env != null && !env.isBlank()) return new JunoHome(Path.of(env));
        return new JunoHome(Path.of(System.getProperty("user.home"), ".juno"));
    }

    public Path root() { return root; }
    public Path settingsFile() { return root.resolve("settings.json"); }
    public Path skillsDir() { return root.resolve("skills"); }
    public Path bundledSkillsDir() { return skillsDir().resolve("bundled"); }
    public Path librarySkillsDir() { return skillsDir().resolve("library"); }
    /** Where the Claude hook script appends events (read by the runtime). */
    public Path hookEventsDir() { return root.resolve("hook-events"); }
    public Path hookScriptsDir() { return root.resolve("hooks"); }
    public Path agentsDir() { return root.resolve("agents"); }
    public Path agentDir(String id) { return agentsDir().resolve(id); }
    /** An agent's private empty working folder when it has no project (design §7.7). */
    public Path agentHome(String id) { return agentDir(id).resolve("home"); }
    /** Per-process files (system prompt, mcp.json, skills plugin); deleted when the process ends. */
    public Path agentRunDir(String id) { return agentDir(id).resolve("run"); }
    public Path journalsDir(String id) { return agentDir(id).resolve("journal"); }
    public Path logsDir(String id) { return agentDir(id).resolve("logs"); }
    /** PRs/MRs agents opened, until closed in the PRs tab (design §7.11). */
    public Path prsFile() { return root.resolve("prs.json"); }
    public Path layoutFile() { return root.resolve("layout.json"); }
    /** The notepad's open tabs and view (design §9). */
    public Path notesStateFile() { return root.resolve("notes.json"); }
    /** Links and proposals held for approval (design §4.1, §4.2). */
    public Path commsFile() { return root.resolve("comms.json"); }
}
