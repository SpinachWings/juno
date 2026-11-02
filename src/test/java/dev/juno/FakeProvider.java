package dev.juno;

import dev.juno.provider.Provider;
import dev.juno.provider.claude.ClaudeCodeProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Claude Code's provider, but launching {@link FakeClaude}: every agent's messages are logged to {@code <dir>/<agent>.log}. */
public final class FakeProvider implements Provider {
    private final ClaudeCodeProvider real;
    private final Path logs;

    public FakeProvider(ClaudeCodeProvider real, Path logs) {
        this.real = real;
        this.logs = logs;
    }

    public Path log(String agentId) {
        return logs.resolve(agentId + ".log");
    }

    @Override public String id() { return real.id(); }
    @Override public Path configFolder() { return real.configFolder(); }
    @Override public VersionCheck checkVersion(String executable) { return new VersionCheck(true, "9.9.9", "fake"); }
    @Override public HookStatus hookStatus() { return HookStatus.NOT_INSTALLED; }
    @Override public String installHook() { return ""; }
    @Override public String uninstallHook() { return ""; }

    @Override
    public List<String> commandLine(LaunchSpec spec) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), FakeClaude.class.getName(),
                spec.mcpConfigFile().toString(), "fake-" + spec.agentId() + "-" + System.nanoTime(),
                log(spec.agentId()).toString());
    }

    @Override public Map<String, String> environment(LaunchSpec spec) { return Map.of(); }
    @Override public List<String> environmentToRemove() { return List.of(); }
    @Override public StreamParser newParser() { return real.newParser(); }
    @Override public String userMessage(String text) { return real.userMessage(text); }
    @Override public String interrupt(String requestId) { return real.interrupt(requestId); }
    @Override public String compactMessage() { return real.compactMessage(); }
    @Override public String mcpConfig(String junoUrl, String token, Map<String, String> hostServers) { return real.mcpConfig(junoUrl, token, hostServers); }
    @Override public String permissionPromptTool() { return real.permissionPromptTool(); }
    @Override public Path writeSkills(Path dir, List<SkillSet> skills) throws IOException { return real.writeSkills(dir, skills); }
    @Override public String skillName(String packId, String skill) { return real.skillName(packId, skill); }
}
