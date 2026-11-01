# Progress

## Milestone 3: the board. Done (2026-11-01)

### Done
- **Board** (`ui.board.BoardView`, `BoardCanvas`, `model.BoardLayout`, `store.LayoutStore` → `~/.juno/layout.json`): a tree of
  splits; every split halves its space, nothing is resizable. ⇥ / ⤓ in a pane's top bar (or **+ New agent**, which splits the
  pane used last) put a setup form in the new cell (`SetupForm`: name, profile, project, colour, GitHub and GitLab key,
  or "Show a hidden agent"); Create / Show fill the cell, Cancel removes it, so there is never an empty pane. ✕ hides
  (the agent keeps running; showing it puts it back next to where it was). Drag a top bar onto another pane to swap.
  Double-click the bar or ⤢ maximises, ⤡ / Esc restores exactly. Minimum pane size 460 × 380: the board scrolls
  vertically, and horizontally only when the window is too narrow. Layout saved after every change. Juno's pane
  (`JunoPane`, a placeholder until Milestone 4) is always there: movable, maximisable, splittable, not hideable.
- **Top bar** of a pane: status + reason, unread dot, name, profile · project · branch, model · effort, resume ⟳, stop ■,
  split ⇥ ⤓, maximise, hide, ⋯. (Resume / Stop are icons now: the bar is narrow.)
- **⋯ options menu** (`ui.pane.OptionsPanel`, covers the pane): name and colour (at once), project (waits while the agent
  works), model, effort, GitHub and GitLab key, "Allowed for this agent" rules with Remove, Conversations (switch back;
  greyed out when Claude Code no longer has one), Usage, Delete (confirmation, a warning for a worktree with uncommitted
  or unpushed work, then asks about Claude Code's transcripts). Model / effort show "applies from the next message".
- **Filter list** (`FilterList`, toolbar "Agents" toggle): colour, unread dot, name, profile, status + reason, context %,
  show/hide checkbox, search, status filter incl. Unread. Click a row to show and focus a pane.
- **Inbox** (`Inbox`, toolbar): count and items derived from agents' state (permission 🔐 with Allow once / Deny, ❓
  question, 📁 folder, ⚠ failed and ⏸ interrupted with Resume, 🔑), "Go to pane", a quiet Waiting ⏳ section. Items
  vanish when answered anywhere.
- **Queued + cap** (`AgentRuntime.deliver`): when "max agents working at once" is reached a message is queued
  ("Queued · 2nd") and started in order when a slot frees; /stop drops it. Waiting agents don't count. Queued messages
  survive a restart.
- **Worktrees** (`git.Worktrees`): agents whose profile edits files and whose project is a git repository get
  `~/.juno/worktrees/<agent id>` on a new `juno/<name>` branch (numbered if taken) off the project's current branch,
  made with the first message. Protected-branch hooks: per-worktree `core.hooksPath` → `~/.juno/worktrees/<id>.hooks/`
  (one script under every client-side hook name; refuses commit, merge, push and any ref update onto the base branches,
  message from `docs/prompts/results/protected-branch.md`; chains the repository's own hook of the same name).
  Merging the base branch into your own branch stays allowed. Your checkout and other worktrees are untouched.
- **Conversations and limits** (`runtime.Conversations`): last 10 earlier conversations, none older than 30 days,
  applied at start-up and after each conversation ends; Juno's journal and log for dropped ones are deleted. `/new` and
  switching keep size and last reply in the list.
- **Storage** (Settings → System): per agent worktree / review snapshots / current conversation / earlier conversations /
  total, **Remove worktree** (idle, nothing uncommitted or unpushed; comes back on its branch), a ⚠ flag after 14 days
  untouched, **Clean up old data**.
- **Agent deletion** (`AgentRuntime.delete`): process, worktree and its hooks, journals, logs, conversations, rules,
  review folder, hook-event file; the branch stays; Claude Code transcripts only if you say yes.
- **Unit tests** (87 in all, `mvn verify`): `BoardLayoutTest` (split / hide / show / swap / reconcile / JSON),
  `BoardCanvasTest` (minimum sizes), `WorktreesTest` (real git: separate worktrees, taken names, branch-in-use, hooks
  refuse commit / merge / fast-forward / `branch -f` / push to a base branch only in the worktree, the repository's own
  hook runs and can refuse, removal safety, a removed worktree comes back), `BoardRuntimeTest` (queue and its order,
  Waiting agents, restart, worktree use, remove, project change, deletion incl. transcripts, limits, switching
  conversations, rename, rules, keys).
- **Checked against the real CLI** (Claude Code 2.1.314, haiku / low, scratch `JUNO_HOME`, scratch `CLAUDE_CONFIG_DIR`,
  scratch repository whose checkout is on `dev` so `main` is free) with `dev.juno.ui.BoardScreens` (test sources;
  scenarios `layout`, `edit2`, `crash1` + `crash2`): two editing agents in separate worktrees each made a commit on their
  own branch while the repository checkout stayed clean; with the cap at 1 the second showed "Queued · 1st" and the
  refusal text; `git switch main` then `git commit` in a worktree printed "Juno: committing to `main` isn't allowed…"
  and `main` kept one commit; hide / show / swap / maximise; three agents (one in a worktree) cut off by killing the JVM
  were ⏸ after the restart (the one that had finished stayed Done) and `/resume` finished them. `layout` (no CLI) also
  shows the setup cells, the options menu, the inbox, a narrow window with scrollbars, Settings → Storage, and that a
  reopened window has the same layout.

### Stubbed
- Juno herself in her pane, links, the other MCP tools (Milestone 4). The inbox shows only what an agent's state says;
  assignment proposals, link suggestions, fresh-start and review-comment approvals, pipelines come with Milestones 4–5.
- Keys: the agent's GitHub / GitLab key is stored (`githubKeyId`, `gitlabKeyId`) and picked in the setup form and
  options menu, but not yet used (credential helper, MCP config, git identity, 🔑): Milestone 5.
- Protected branches from open PRs/MRs: `Worktrees.setProtected` exists; Milestone 5 calls it with the PR/MR base.
- Review snapshots: counted in Storage if `~/.juno/reviews/<agent>` exists; made in Milestone 5.

### Decisions (not in the spec)
1. **Worktree folder** is `worktrees/<agent id>` (names can change). A project that is not a git repository is used
   directly by an editing agent (no worktree, no protection); a repository with no commits fails the start with a clear
   message.
2. **Protected branches** = the remote's default (`origin/HEAD`), the branch the worktree was made from (unless it is
   an agent branch) and `main` / `master` when they exist. The hook also refuses any ref update to them
   (`reference-transaction`), which catches fast-forward merges and `branch -f`, not only commits and pushes.
   `extensions.worktreeConfig` is set in the project's git config (needed for a per-worktree `core.hooksPath`).
3. **Unpushed work** = commits only on the worktree's branch (in no other local branch and no remote branch). Removal
   and a project change refuse on uncommitted or unpushed work; deleting an agent warns and then removes anyway (the
   branch stays). A project change for an editing agent removes its old worktree first.
4. **A removed worktree** comes back on its old branch; if that branch is checked out elsewhere it gets a new one and
   says so. Branch-in-use while an agent switches: git's own refusal names the place, and the coder prompt already
   tells the agent to report it.
5. **Cap counting:** every agent with an open turn counts (including one waiting on a permission answer); Waiting ⏳
   agents don't; Juno never will. FIFO queue.
6. **Setting a project** is by id from Settings → Projects; "no project" can't be chosen again once a project is set.
   The options menu's change while working is runtime-only (lost if Juno restarts first).
7. **Keys:** two pickers (GitHub, GitLab) because §5.1.1 allows one of each per agent. The "no key" warning appears
   when the project has a remote and neither is chosen (a project's host can't be told from `owner/repo`).
8. **Layout for agents created without a setup cell** (e.g. by Juno later): placed by splitting the last pane,
   alternating right / below. Hidden agents are remembered with their neighbour and side.
9. **Inbox** is a popup under the toolbar button; the **filter list** is a left panel toggled by "Agents".
10. **`Provider.configFolder()`** was added (where the CLI keeps transcripts) for the conversations list and deletion.
11. **Storage columns:** worktree · review snapshots · current conversation (journal + log) · earlier conversations
    (journals + logs). Stale = no status change or interruption for 14 days.

### Known issues
- The drag-to-swap gesture itself wasn't driven (no way to synthesise a drag here); the swap it triggers
  (`BoardView.swap`) was, and the layout logic is unit tested.
- Narrow panes truncate the meter row ("can talk to…", turn info) with an ellipsis.
- The Delete dialogs (`Alert.showAndWait`) weren't driven; the deletion they call is unit tested.
- Bold Markdown has no bold face in Share Tech Mono; the `Notification` hook is not exercised (both from Milestone 2).

### For the next milestone (4)
- `BoardView` owns the panes; Juno's pane is `ui.board.JunoPane` (a placeholder to replace with the orchestrator
  agent's `AgentPane`; `BoardLayout.JUNO` is its id). `PaneHost` is what a pane asks of the board.
- `Inbox.items()` is the one place to add inbox kinds; `FilterList` and `Inbox` re-read agent state on every
  `AgentRuntime.Listener.changed`.
- `AgentRuntime.deliver` queues; `startTurn` starts. Juno must stay out of `workingCount()`.
- `runtime.folder(agent)` is the worktree for editing agents; see `usesWorktree`, `ensureWorktree`, `Agent.branch`.
- Per-agent keys are on `Agent.githubKeyId` / `gitlabKeyId`.

## Milestone 2: one agent, end to end. Done (2026-11-01)

### Done
- **Provider** (`provider.Provider`, `AgentEvent`): command line, environment, stream-json in/out, interrupt, compact,
  MCP config, skills plugin folder. `ClaudeCodeProvider` + `ClaudeStreamParser` turn Claude Code's output into
  events: session, message, thinking (text or token estimate), tool use + result, diff (Edit / MultiEdit at the call,
  Write once its result says create / update), sub-agent start / done (nested by `parent_tool_use_id`), to-dos,
  usage (per API call, once per message id; turn totals with the context window), result, rate limit, background
  task count, compaction (with the new context size).
- **Runtime** (`runtime.AgentRuntime`, `AgentProcess`, `Journal`, `LiveAgent`, `HookEvents`): statuses with reason
  tags and unread; status follows output events (turn opens on send or on output, closes on `result` unless
  background tasks run; a final reply whose last paragraph starts with ❓ → Awaiting ❓; errors → ⚠ failed);
  Done → Idle after 15 minutes; idle process timeout closes the process (next message resumes it); agent state saved
  as it happens (`~/.juno/agents/<id>/agent.json`), every event journalled (`journal/<conversation>.jsonl`), raw
  output logged (`logs/<conversation>.log`, rotated at 5 MB); crash or shutdown mid-turn → Awaiting ⏸ on the next
  start; `/resume` sends the resume recap built from the journal (task, last actions, pending request, `git status`).
  The hook's `Stop` event closes a turn whose `result` never arrived; output events stay primary.
- **System prompt** = preamble + profile prompt (`--append-system-prompt-file`), rendered per launch.
- **Skills**: one session-only plugin per pack under `agents/<id>/run/plugins/<pack>` (`--plugin-dir`), so skills
  are `<pack>:<skill>` (e.g. `superpowers:brainstorming`, matching superpowers' own references).
- **Juno MCP server** (`comms.JunoMcpServer`): streamable HTTP on 127.0.0.1, one random bearer token per process
  (identifies the agent), tools `request_permission` (the `--permission-prompt-tool`) and `request_folder`; tool
  descriptions come from `docs/prompts/tools/`.
- **Permissions** (`comms.PermissionRules`, runtime): the four answers, editable rule with plain-words description,
  per-agent rules (stored on the agent, matched automatically with "✓ allowed by rule …"), "always allow for
  `<profile>`" adds to the profile's allowed tools (not offered for built-ins), disallowed tools refused without
  asking (any part of a compound command), `/approve` / `/deny`.
- **Folders**: no project → private empty folder `agents/<id>/home`; needs-a-folder profiles hold the first message
  at Awaiting 📁 with a project picker in the pane; `request_folder` likewise; picking a project restarts the process
  in it (conversation kept) and sends the held message or the folder-set notice.
- **Pane** (`ui.pane`): top bar (status + reason, unread dot, name, profile, project or "no folder", model · effort,
  Resume, Stop), rendered output in one read-only RichTextFX area (drag-select across blocks, Copy / Copy all /
  Select all menu, ⧉ copy on every message and code block): Markdown messages (commonmark, tables), collapsed
  thinking, plan checklists (earlier ones fold to "n/m done"), commands with output (long output collapsed), file
  reads collapsed, diffs (red / green, new files highlighted), `/diff` patches, collapsible nested sub-agents,
  permission requests and ❓ questions highlighted until answered, quiet per-turn usage lines; "can talk to" line;
  context meter (amber above the fresh-start threshold, red ≥ 90 %, hover for input / output / cache) with last turn,
  conversation tokens and API-price estimate; permission card and folder card above the input; input with `/`
  autocomplete (commands, then agent names, skills, models, efforts).
- **Toolbar**: usage-limit meter from rate-limit events (`5h 24% · week 18%`, resets on hover, amber > 80 %, red > 95 %).
- **Commands** (`runtime.PaneCommands`): /stop /resume /new /compact /clear /copy /diff /usage /model /effort
  /activate<skill> /approve /deny /help, profile commands (template + `{{args}}`), /hey<name> (see decisions);
  plain messages refused while working (kept in the box), unknown commands "isn't supported"; Juno-only commands
  say so.
- **Unit tests** (51, `mvn verify`): stream parser on recorded line shapes, command line flags, permission rules and
  proposals, runtime without a CLI (crash recovery, held message, missing Claude Code, names, commands, /activate,
  permissions end to end over the MCP server incl. disallowed and wrong token, tools/list), system prompts of every
  built-in profile, command parsing, recap, journal torn line, meters.
- **Checked against the real CLI** (Claude Code 2.1.314, haiku / low, scratch `JUNO_HOME`, scratch
  `CLAUDE_CONFIG_DIR`, scratch git repository) with `dev.juno.ui.AgentScreens` (test sources; drives the real window
  and saves PNGs; scenarios edit, permissions, folder, subagent, kill, crash1 + crash2), `dev.juno.ui.pane.OutputPreview`
  (made-up events, no CLI) and `dev.juno.runtime.RuntimeSmoke` (headless):
  an edit shown as a diff, `/diff`, `/usage`; a permission prompt answered each of the four ways (and the per-agent
  rule matching later); a held first message and a `request_folder`; a nested sub-agent, autocomplete, `/help`,
  `/init` refused; a killed process → ⏸ and `/resume` with the recap; Juno killed mid-turn → ⏸ after restart and
  `/resume`; `/compact`.

### Stubbed
- Board, splits, filter list, ⋯ options menu (per-agent rules list, conversations list): Milestone 3. Until then the
  toolbar has a one-pane agent switcher and **+ New agent** shows a minimal setup form (name, profile, project,
  colour) in the board area.
- Worktrees (Milestone 3): agents that edit files work **directly in the project folder** for now; use scratch
  repositories only. `branch` in the preamble is empty.
- Queued status and the concurrency cap (Milestone 3).
- Juno herself, links, `/cantalkto`, `/stoptalkingto`, `/getcontext`, the other MCP tools and tool access by allowed
  tools (Milestone 4). "can talk to" shows Juno only; the preamble's contacts list is Juno only.
- Keys in agents' `mcp.json`, host MCP servers, 🔑 (Milestone 5).

### Decisions (not in the spec)
1. **/stop** interrupts the turn (stream-json `interrupt`; killed after 10 s if it doesn't end) and leaves the agent
   **Idle** with "Stopped. /resume continues"; Awaiting ⏸ is kept for crashes and shutdowns. `/resume` also retries an
   Awaiting ⚠ failed turn. `/stop` on an agent holding a message for a folder drops the message.
2. **/hey** is built now (it's the hint of the "working" refusal): it writes the `hey` wrapper into the running
   process, which Claude Code reads when the current step ends; to an idle agent it's a normal message. Works for any
   agent by name.
3. **Async sub-agents:** Claude Code runs sub-agents in the background and its `result` can come before they finish.
   A turn stays open while background tasks run, and closes 5 s after the last one ends with no further output.
4. **Environment:** variables a parent Claude Code session sets for its children (`CLAUDECODE`,
   `CLAUDE_CODE_SESSION_ID`, `CLAUDE_CODE_ENTRYPOINT`, …; list in `ClaudeCodeProvider`) are removed, so Juno started
   from inside Claude Code doesn't make its agents part of that session. Everything else is passed on.
5. **Windows launch:** a native `.exe` found on PATH (PATHEXT) is started directly; anything else (npm `.cmd`) through
   `cmd.exe /c`. Stopping kills the process tree. A missing executable is found before running it.
6. **Permission prompt answers:** allow returns the call's input unchanged (`updatedInput`); per-agent rules are
   matched by Juno (not passed as `--allowedTools`) so each match shows its quiet line. Proposed Bash rules keep the
   first word only for subcommand-style programs (`npm install`, `git commit`), else the program (`touch:*`).
   Absolute file rules use Claude Code's `//` form.
7. **Rules for the profile** are saved to settings at once and apply from the agents' next start.
8. **Model / effort / project changes** restart the process before the next message (conversation kept via
   `--resume`); a project change during a turn waits for the turn to end.
9. **Journal** lines are appended (not temp-file-and-move; a torn last line is skipped on reading).
10. **Context** = the last top-level API call's input + cache read + cache write + output; the window comes from the
    turn's `modelUsage.contextWindow` (unknown until the first turn ends).
11. **Pre-filled colours** cycle through seven (`SetupForm.COLOURS`); any colour can be picked. They're agent data,
    not part of the palette.
12. `--permission-mode default` is accepted by 2.1.314 (not listed in its help), so built-in profiles keep `default`.
13. **Hook `Notification`** about a permission (when no request is pending) shows Awaiting 🔐; with
    `--permission-prompt-tool` it isn't expected to fire.
14. **Crash vs. shutdown:** closing Juno records "Juno was closed while you were working"; after a crash the reason
    is "Juno crashed …" and the time is the last journalled event. Either way the pane gets a ⏸ line saying so.
    The recap lists the last six top-level actions, with paths relative to the agent's folder.
15. A ❓ question stays highlighted until you reply or the agent sends a newer reply (e.g. after async sub-agents).

### Changes after review (same session)
- **Toolbar ? removed** (your call; `docs/design.md` §5.6 and §7.2 updated): the commands reference is Settings →
  Commands and `/help` in any pane.

### Known issues
- Bold Markdown has no bold face in Share Tech Mono (shown brighter instead of bold).
- The `Notification` hook event still hasn't been exercised against the real CLI.
- Rules containing a comma would split in `--allowedTools` (joined with commas).
- Three MCP protocol errors are inline English, not in `docs/prompts/` ("Unknown tool", "Unknown agent", "Juno
  couldn't handle this call"); a correctly launched agent can't reach them. The cancelled-request reason fragments
  ("the process ended", "the user stopped you") are filled into `results/request_permission.md#cancelled` from Java.
- Agents that edit files work in the project folder itself until worktrees (Milestone 3).

### For the next milestone (3)
- `ctx.runtime` (`AgentRuntime`) owns agents: `create`, `agents()`, `byName`, `live(id)` (process, pending
  permissions, meters, history), `send`, `hey`, `stop`, `resume`, `newConversation`, `setProject`, `answer`,
  listeners (`changed`, `event`, `rateLimit`, `agentsChanged`). `AgentPane` is self-contained per agent; the board
  arranges panes. `MainWindow`'s switcher and `SetupForm` are the stand-ins to replace.
- `Agent.conversations` already records each conversation (id, Claude session id, started / ended, first task);
  journals and logs are per conversation id. `Agent.rules` holds the per-agent rules for the options menu.
- `runtime.folder(agent)` is where worktrees plug in (TODO there); `AgentPrompts.systemPrompt` has the `branch` TODO.
- Queueing: `AgentRuntime.deliver` is the one place a message starts a turn.

## Milestone 1: foundation and settings. Done (2026-11-01)

### Done
- Maven project (Java 27, JavaFX 27.0.1, Jackson, RichTextFX, java-keyring), `dev.juno` packages per §10.
  `docs/prompts` is a Maven resource directory (target `dev/juno/prompts`): one copy, read from the classpath
  (`prompts.Prompts`: load, `## key` sections, `{{x}}` / `{{#x}}` / `{{^x}}` rendering, `listAll`).
- Theme: one stylesheet (`ui/juno.css`, palette at the top), bundled Share Tech Mono and VT323 (OFL licences included).
- Main window shell: toolbar (Add agent, Board / Notes toggle, usage meter, `?`, Settings; all stubs except Settings and
  `?`), Claude Code version banner.
- `model` + `store`: all settings as plain data in `~/.juno/settings.json` (or `JUNO_HOME`), atomic writes
  (`AtomicFiles`). Built-in profiles come from code on every load; only model, effort, executable path, max turns,
  spend limit and own profile commands are taken from the saved copy.
- Settings window, all six tabs working and saved:
  - **Keys:** add/edit/delete, token in the OS vault via java-keyring (`keyring:juno/key/<id>`) or `env:VARIABLE`,
    never plain text, masked, MCP URL from host with override. Test button stubbed.
  - **System settings:** everything in §5.2; Claude hook install / update / uninstall / status; Claude Code path with a
    Check button. Storage section stubbed.
  - **Projects:** fields incl. build & test commands, validated (folder exists, remote well formed, unique name).
  - **Agent profiles:** locks on built-ins, Duplicate, delete for own profiles, profile commands (built-in ones locked).
  - **Skills:** import (SKILL.md / folder / .zip / .skill), whole-orchestration or per-profile assignment, global and
    per-profile skill switches, bundled superpowers (obra/superpowers, MIT, commit 2ce8aa1) with locked coder/planner
    assignments, refreshed from the app's copy on every start-up.
  - **Commands:** table from one registry (`runtime.CommandRegistry`, which later milestones extend), plus every
    built-in prompt file read-only.
- First-run setup (§5.1.2): Claude Code check, hook, notes folder, first project, keys (opens the Keys tab).
- Claude Code version check (`ClaudeVersion`, minimum 2.1.314) at start-up, on path change, and in first-run setup.
- 21 unit tests (`mvn verify`): prompts and profiles, settings round trip and validation, MCP URLs, token masking, hook
  merge / idempotence / uninstall / backups / the hook script itself (against a scratch Claude folder), version check,
  skills import / zip safety / bundled refresh / resolver.
- Checked by eye: `ScreenshotTool` (test sources, not a unit test; scratch home, scratch Claude folder, in-memory token
  store) renders the main window, all six tabs and every first-run step to PNG; the app was also started with
  `mvn javafx:run` against a scratch `JUNO_HOME` and `CLAUDE_CONFIG_DIR`.

### Changes after review (same session)
- Profiles tab: **+ New profile** and Duplicate both save immediately; **Available tools...** opens a reference of tools
  and rule syntax (`model.ToolCatalog`, extend it in Milestone 5 with the host MCP tools) with add-to-allowed /
  disallowed buttons for non-built-in profiles.
- Skills tab: no global "Enabled" column (skills are switched per profile only; `SkillPack.disabledSkills` stays in the
  model but has no UI). Bundled packs can be assigned only to their locked profiles (coder, planner) and own
  profiles, never other built-ins; the resolver ignores such assignments. The tab refreshes when shown.

### Stubbed
- Keys: Test button (Milestone 5). Storage section, Remove worktree, Clean up old data (Milestone 3).
- Toolbar: Add agent (Milestone 3), usage meter (Milestone 2), Notes toggle (Milestone 6). The board is a placeholder.
- Notes-folder overlap check with project folders (Milestone 6, §9).
- Host MCP tools in the coder / reviewer allowed tools (Milestone 5, once names are verified, §13).
- Per-agent plugin folder from `SkillResolver` (Milestone 2); the resolver already gives the enabled skills per profile.

### Decisions (not in the spec)
1. **No `module-info`:** the app runs from the classpath through `dev.juno.app.Launcher` (a plain main class), the
   usual way to start JavaFX without a module-path launcher.
2. **Hook transport:** the hook is `~/.juno/hooks/juno-hook.sh`, registered for `Notification` and `Stop` (timeout 5 s)
   as `sh "<path>"`. It does nothing unless `JUNO_AGENT_ID` is set, otherwise appends Claude's hook JSON line to
   `~/.juno/hook-events/<agent>.jsonl` and exits 0, so it works with Juno closed. Milestone 2 tails those files.
   `sh` is used on Windows too, since Claude Code there runs hooks through Git Bash; **Verified after the Milestone 1 commit
   (Claude Code 2.1.314, Windows, haiku/low, scratch `CLAUDE_CONFIG_DIR` with only the login file copied in): the `Stop`
   hook fires through `sh` and writes the event JSON; with no `JUNO_AGENT_ID` nothing is written.** `Notification` wasn't
   exercised. Our entries are recognised by the script name; status is *outdated* when
   an event is missing, the command differs or the script text differs. Backups are
   `settings.json.juno-<timestamp>.bak` next to the settings file, only when a change is made. Claude's folder is
   `CLAUDE_CONFIG_DIR` if set, else `~/.claude`.
3. **Minimum Claude Code version** is 2.1.314, the version installed during development; flags aren't verified yet
   (Milestone 2 raises it if needed).
4. **Profile editing:** built-in profiles lock name, provider, permission mode, system prompt, allowed / disallowed
   tools, needs folder and edits files (§5.4). There is no "new blank profile": Duplicate is the way to make one.
5. **Skills assignment model:** a pack is assigned either to the whole orchestration or to chosen profiles; bundled
   packs' locked assignments live in code (`BundledPacks`), and a user can additionally assign them to own profiles.
   Importing a pack whose name exists makes a new pack with a numbered id. Imported packs default to whole
   orchestration. A pack's entry skill is `using-<id>` if present, else its first skill.
6. **Keys:** a key's host-less `Test` and MCP URL: GitHub Enterprise Server hosts have no URL unless an override is set.
   `<name>.ghe.com` uses `https://copilot-api.<name>.ghe.com/mcp` as the spec says (still "verify").
7. **Defaults not in the spec:** max agents working at once 4, idle process timeout 10 minutes.
8. **Settings in one file:** keys, projects, profiles, skill packs and system settings are all in `settings.json`.

### Known issues
- The `Notification` hook event hasn't been exercised against the real CLI (`Stop` has; see decision 2).
- The Commands table truncates long descriptions with an ellipsis (no row wrapping).
- Bundled superpowers is copied from the upstream repository's `skills/` folder only; its hooks and other plugin parts
  are not used.

### For the next milestone (2)
- `AppContext` holds `settings`, `provider` (`ClaudeCodeProvider`), `skills` and `skillResolver`; call `ctx.save()`
  after changing settings. `Provider` has only the version check and hook so far: extend it.
- Per-agent skills plugin folder: build from `SkillResolver.enabledFor(settings, profileName)` and
  `SkillLibrary.packDir(pack)/skills/<skill>`.
- Profiles: system prompts are already rendered-ready templates (`{{agent_name}}` etc.); build and test commands come
  from `Project.buildAndTestCommands`.
- Keys resolve through `TokenStore.resolve(key.token)` only when an agent starts.
- Register new slash commands in `CommandRegistry` (the Commands tab reads it).
- `ScreenshotTool` is the way to look at new screens: build the classpath with
  `mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt`, then
  `java -cp "target/classes;target/test-classes;<cp>" dev.juno.ui.ScreenshotTool <outdir>`.

## Milestone 0: prompts. Done (2026-11-01)

### Done
- All built-in natural-language text, approved by the user, in `docs/prompts/` (format and placeholders in
  `docs/prompts/README.md`):
  - system prompts of the six built-in profiles (`profiles/`);
  - the coder's `/checkforcomments` template (`commands/coder-checkforcomments.md`);
  - agent preamble, Juno MCP tool and parameter descriptions, tool results and refusals, wrappers, resume recap.
- `CLAUDE.md` and this file.

### Stubbed
Nothing (no code in this milestone).

### Decisions (not in the spec)
1. **Questions (❓):** an agent that needs the user's answer makes it the last paragraph of its reply, starting with
   `❓ `, and ends its turn. The runtime marks **Awaiting decision ❓** when a turn's final reply has such a
   paragraph. (Claude Code's `AskUserQuestion` via `request_permission` was the alternative; not chosen.)
2. **Reports:** the agent calls `report` once with its own summary. The app adds status, task, files changed and
   context use (`wrappers/report.md`) and caps the summary at Report length. If an agent ends a Juno task without
   calling `report`, the app uses its final reply, truncated.
3. **Reviewer, head moved before posting:** the reviewer checks the head commit before posting; if it moved it calls
   `start_review` again on the same target, which re-waits for pipelines and refreshes the snapshot.
4. **`/checkforcomments`:** unresolved threads whose latest comment is the coder's own reply are left out and listed
   as "already answered" (the coder can't resolve threads, so "reply only" threads would otherwise come back every
   run).
5. **Template syntax:** `{{x}}` plus Mustache-style `{{#x}}…{{/x}}` / `{{^x}}…{{/x}}` sections; no other logic.
6. **File layout:** results are one file per tool with `## <key>` sections; wrappers with variants likewise.
7. **`set_link`** has an extra optional `reason` parameter (required when `requested_by_user` is false), shown on
   the approval card's "Why: …".
8. **`get_agent_output`:** `lines` defaults to 100, max 1000.
9. **Placeholders in system prompts:** `{{agent_name}}`, `{{profile_name}}`, `{{fresh_start_threshold}}`,
   `{{build_and_test_commands}}` (`none` when the project has none); `{{args}}` for profile commands.
10. Reviewer verdicts: "Ready to merge" / "Ready after fixes" / "Needs rework". Reviews diff with
    `git diff <base>...<head>`; re-reviews with `git diff <sha>..<head>`.

### Known issues
- Host MCP tool names aren't known yet (§13, Milestone 5a). The prompts say "the host's MCP tools" generically;
  profiles' allowed/disallowed tool lists need the real names.

### For the next milestone (1)
- Built-in profiles load their system prompts from `docs/prompts/profiles/`. Suggested: add `docs/prompts` as a
  Maven resource directory (target path `dev/juno/prompts`) so there's one copy, read from the classpath.
- Settings → Commands lists these templates read-only.
- Build, test and run commands in `CLAUDE.md` are the intended ones; make them true.
