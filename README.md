# Juno

Juno is a desktop orchestrator for coding agents. You define **agent profiles** and **projects**, then run **agents**
side by side on a board of terminal panes. One agent, **Juno**, is always there: she finds idle agents, hands them
tasks (asking you first when you didn't say who), collects their reports and links agents when you ask. Everything
is driven through the [Claude Code](https://claude.com/claude-code) CLI.

The full spec is [docs/design.md](docs/design.md). What is built, and what is not yet verified, is in
[docs/progress.md](docs/progress.md).

## Requirements

- Java 27 and Maven (JavaFX 27 comes through Maven).
- Claude Code **2.1.314 or later** on `PATH`, or its full path set in Settings. Juno checks `claude --version` at
  start-up and says so if it is missing or too old.
- Optional: a GitHub or GitLab token per account you want agents to use (see *Keys*).
- Windows first; macOS and Linux should work.

## Build and run

```bash
mvn verify          # build and run the tests
mvn test            # tests only
```

Run against a scratch home while trying things out, never your real `~/.juno` (PowerShell):

```powershell
$env:JUNO_HOME = "$env:TEMP\juno-scratch"; mvn javafx:run
```

## First run

A skippable setup walks through: the Claude Code path (checked), installing the Claude hook (makes "Awaiting
decision" and "Done" exact; merged into `~/.claude/settings.json` with a backup first), the notes folder, a first
project, and your GitHub / GitLab keys. Everything can be done later in Settings.

## The board

- **Panes** hold one agent each. Split a pane right (⇥) or down (⤓); the new half is a new agent or a hidden one.
  Drag a top bar onto another pane to swap; double-click it (or ⤢) to maximise, Esc to restore. ✕ hides an agent
  (it keeps running); deleting is in its ⋯ menu.
- **Statuses:** Idle, Queued, Working, Done, and *Awaiting decision* with a reason: ❓ question, 🔐 permission,
  📁 folder, 🔑 key, ⚙ profile removed, ⚠ failed, ⏸ interrupted. A dot marks a pane with something new.
- **Inbox** lists everything waiting on you; **Agents** is the filter list; the meter shows your plan's usage limits.
- Type `/` in a pane's input for the commands available there (`/help` lists them). While an agent works, plain
  messages are refused; use `/hey<name> <text>`.

Toolbar toggles switch between **Board**, **Notes** and **PRs**.

## Workflows

- **Give a task directly:** type it in an agent's pane.
- **Through Juno:** "juno, give bob the login bug" (waits 15 s with Undo), `/assign<name> <task>` (goes at once), or
  "get someone to fix the login bug" (Juno proposes an agent; you approve, edit or choose another). A fresh start of
  an agent's conversation always needs your approval.
- **Let agents talk:** `/cantalkto<name>` and `/stoptalkingto<name>`, or ask Juno. Unlinked agents can't message
  each other. A loop guard stops long chains without a message from you.
- **Coder:** asks for a source branch and a new branch (or an existing one) before touching files, builds and tests,
  pushes and opens a PR/MR. `/checkforcomments` and `/checkformergeconflicts` handle review comments and merge
  conflicts, one decision at a time. Merge, approve and close ask you first.
- **Planner / searcher:** talk a feature through (plan stays in the pane) / search the web with sources.
- **Permissions:** an agent asking for something its profile doesn't allow shows **Allow once**, **Always allow for
  this agent**, **Always allow for `<profile>`** or **Deny**, with the rule editable first.
- **PRs tab:** PRs/MRs your agents opened; Open, Copy link, Close (never touches the host).
- **Notepad:** a Markdown editor with live preview over a notes folder; one agent's pane can sit beside it.
- **Crash recovery:** after a crash or shutdown agents show ⏸; `/resume` continues the same conversation.

## Settings

Six tabs: **Keys**, **System settings**, **Projects**, **Agent profiles**, **Skills**, **Commands**.

- **Keys:** a GitHub or GitLab token per key, stored in the OS vault (or as `env:VARIABLE`); never plain text,
  never logged or put in prompts. Keys are chosen per agent. No key means no GitHub/GitLab access at all for that
  agent. GitHub needs a fine-grained token (Contents and Pull requests read & write, Issues read); a GitLab key holds
  two tokens (a fine-grained one for the MCP server, and a git token with `read_repository` / `write_repository`).
  The **Test** button checks a key; the tab lists the permissions needed.
- **Projects:** a repository folder, an optional remote, and build & test commands for agents that edit files.
- **Profiles:** built-ins (orchestrator, coder, planner, searcher) are locked except model, effort and a few limits;
  **Duplicate** makes an editable copy.
- **Skills:** skill packs (`SKILL.md`, folder, `.zip`, `.skill`); the superpowers pack is bundled.

## Where data lives

Everything is under `~/.juno` (or `JUNO_HOME`); every write is atomic.

| Path | What |
| --- | --- |
| `settings.json` | settings, profiles, projects, keys (tokens only as `keyring:` / `env:` references) |
| `agents/<id>/` | an agent's state, journals, logs (rotated above 5 MB) and private folder |
| `layout.json`, `notes.json`, `comms.json`, `prs.json` | board layout, notepad state, links and proposals, PRs tab |
| `skills/` | bundled and imported skill packs |
| `hooks/`, `hook-events/` | the Claude hook script and the events it reports |

Juno never touches your project folders' git config, branches or worktrees. Agents never get the notes folder.
