# Juno v2

Desktop orchestrator for coding agents. The spec is `docs/design.md` (§13 says what is verified and what is not); `README.md` is for users on GitHub only.
Built on the `v2` branch.

## Conventions
- Java 27, JavaFX 27, Maven, Jackson. RichTextFX, commonmark-java, java-keyring (design §2). Windows first;
  macOS/Linux should work.
- Package layout (design §10), root `dev.juno`:
  - `app`: JavaFX entry point, main window, toolbar, board/folders/PRs toggle
  - `model`: profiles, projects, keys, agents, links, settings (plain data, JSON)
  - `store`: `~/.juno` persistence: settings, agents, journals, logs
  - `provider`: `Provider` interface + `AgentEvent` types; `provider.claude`: `ClaudeCodeProvider` (command line,
    stream-json parser, hook installer, MCP config)
  - `runtime`: `AgentRuntime`, `AgentProcess`, `Journal`
  - `comms`: `JunoMcpServer`, links, delegation/approvals, reports, loop guard, host MCP proxy rules
  - `git`: git environment and credential helper for agents, `/diff`
  - `prompts`: built-in prompt templates (§11)
  - `skills`: skill library, bundled packs, per-agent plugin folders
  - `ui`: board, panes, output blocks, setup form/options menu, settings tabs, Folders view, PRs tab, filter list
- All data under `~/.juno`, overridable with `JUNO_HOME`. Every write is atomic (temp file + move).
- Tokens are never logged, shown in full, or put in prompts.
- All text sent to agents lives in `src/main/resources/dev/juno/prompts/` (format in its `README.md`), never inline in Java.
- Palette defined once at the top of one stylesheet.

## Build, test, run
Keep these commands true.

```bash
mvn verify
```

```bash
mvn test
```

Run against a scratch home, never the real `~/.juno` (PowerShell):

```powershell
$env:JUNO_HOME = "$env:TEMP\juno-scratch"; mvn javafx:run
```

## Working rules
- Never point agents or tests at real repositories or the real `~/.claude`; use scratch copies.
- Agent behaviour is checked against the real Claude Code CLI with haiku and low effort.
- Spec wrong, ambiguous or impossible → stop and ask. Record decisions the spec didn't make in `docs/design.md`.
- Don't push.

## Checking things (notes for Claude)
- Real window checks use the drivers in `src/test/java/dev/juno/ui` (`BoardScreens`, `JunoScreens`, `FoldersScreens`,
  `ChangeScreens`, `ScreenshotTool`): `java -cp "target/classes;target/test-classes;<mvn dependency:build-classpath>"
  dev.juno.ui.<Driver> <scratch dir> <output dir> [scenario]` writes a PNG per step. Real-agent checks run haiku at low
  effort with a scratch `JUNO_HOME` and a scratch `CLAUDE_CONFIG_DIR` (the user logs in there once with `claude` and
  `/login`; never copy their credentials).
- In Git Bash set `MSYS_NO_PATHCONV=1` when passing a leading-slash argument such as `/help` to a driver, or it is
  turned into a Windows path. `claude` may not be on the user's PowerShell `PATH`; the full path goes in Settings.
- Sandbox GitHub repository for host checks: `SpinachWings/terrible-workout-tracker`. The user supplies a throwaway token
  for the run; pass it only as an environment variable, never write it to a file, and close any PR and delete any branch
  made.
- Write Java edits with the Edit tool; inline Python or shell heredocs mangle backslash escapes in Java strings.
