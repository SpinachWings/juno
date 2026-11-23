# Juno

Juno is a desktop app that orchestrates coding agents. You define **agent profiles** and **projects**, then run
**agents** side by side on a board of terminal panes. One agent, **Juno**, is always there: she finds idle agents,
hands them tasks (asking you first when you didn't say who), collects their reports and links agents when you ask.
Everything runs through the [Claude Code](https://claude.com/claude-code) command line.

## Requirements

- Java 27 and Maven (JavaFX comes through Maven).
- Claude Code **2.1.314 or later**, on your `PATH` or with its full path set in Settings. Juno checks the version at
  start-up and tells you if it is missing or too old.
- Optional: a GitHub or GitLab token per account you want agents to use (see *Keys*).
- Windows first; macOS and Linux should work.

## Run

```bash
mvn javafx:run
```

To try it without touching your real data, point it at a scratch folder first (PowerShell):

```powershell
$env:JUNO_HOME = "$env:TEMP\juno-scratch"; mvn javafx:run
```

`mvn verify` builds and runs the tests.

## First run

A skippable setup walks through: the Claude Code path (checked), installing the Claude hook (it makes "Awaiting
decision" and "Done" exact; it is merged into `~/.claude/settings.json`, and a copy of that file is saved first), and
your GitHub / GitLab keys. All of it can be done later in Settings. Projects are added in Settings → Projects, and
folders in the Folders view.

## The board

- **Panes** hold one agent each. Split a pane right (⇥) or down (⤓); the new half is a new agent or a hidden one.
  Drag a pane's top bar onto another to swap them; double-click it (or ⤢) to maximise, Esc to restore. ✕ hides an
  agent (it keeps running); deleting is in its ⋯ menu.
- **Statuses:** Idle, Queued, Working, Done, and *Awaiting decision* with a reason: ❓ question, 🔐 permission,
  📁 folder, 🔑 key, ⚙ profile removed, ⚠ failed, ⏸ interrupted. A dot marks a pane with something new.
- The **Inbox** lists everything waiting on you. **Agents** (board only) opens the list of all agents with search and
  filters. The meter shows your plan's usage limits.
- Type `/` in a pane's input for the commands available there (`/help` lists them). While an agent works, plain
  messages are refused; use `/hey<name> <text>`.

The toolbar switches between **Board**, **Folders** and **PRs**.

## Workflows

- **Give a task directly:** type it in an agent's pane.
- **Through Juno:** "juno, give bob the login bug" (it waits 15 seconds with Undo), `/assign<name> <task>` (goes at
  once), or "get someone to fix the login bug" (Juno proposes an agent; you approve, edit or choose another). Starting
  an agent's conversation afresh always needs your approval.
- **Let agents talk:** `/cantalkto<name>` and `/stoptalkingto<name>`, or ask Juno. Unlinked agents can't message each
  other. A loop guard stops long chains that run without a message from you.
- **Coder:** asks for a source branch and a new branch (or an existing one) before touching files, builds and tests,
  pushes and opens a PR/MR. For web apps it also starts the dev server and checks the page in Chrome through Claude in
  Chrome (install the extension and keep Chrome open). `/checkforcomments` and `/checkformergeconflicts` handle review
  comments and merge conflicts one decision at a time. Merging, approving and closing ask you first.
- **Planner / searcher:** talk a feature through (the plan stays in the pane) / search the web with sources.
- **Permissions:** when an agent wants something its profile doesn't allow you can **Allow once**, **Always allow for
  this agent**, **Always allow for `<profile>`** or **Deny**, with the rule editable first.
- **Folders:** add any folders you like (notes, a project, your whole dev directory). Each is an expandable,
  collapsible tree you can re-order; files open in tabs in a text editor (Ctrl + and Ctrl - change the text size), and
  one agent's pane can sit beside them.
- **PRs:** the PRs/MRs your agents opened, with Open, Copy link and Close (closing never touches GitHub or GitLab).
- **Crash recovery:** after a crash or shutdown agents show ⏸; `/resume` continues the same conversation.

## Settings

- **Keys:** a GitHub or GitLab token per key, stored in the OS vault (or read from an environment variable); never
  kept in a file, logged or put in prompts. A key is chosen per agent; an agent without one has no GitHub/GitLab
  access. GitHub needs a fine-grained token (Contents and Pull requests read & write, Issues read); a GitLab key holds
  two tokens (a fine-grained one for the MCP server, and a git token with `read_repository` and `write_repository`).
  **Test** checks a key, and the tab lists the permissions needed.
- **System settings:** the Claude hook, the Claude Code path, how Juno delegates, limits, and storage.
- **Projects:** a repository folder, an optional remote, and build & test commands for agents that edit files.
- **Agent profiles:** orchestrator, coder, planner and searcher are built in (mostly locked); **Duplicate** makes an
  editable copy.
- **Skills:** skill packs (`SKILL.md`, `.zip` or `.skill`); the superpowers pack is bundled.
- **Commands:** a reference of every command.

## Where data lives

Everything is under `~/.juno` (or `JUNO_HOME`), and every write is atomic.

| Path | What |
| --- | --- |
| `settings.json` | settings, profiles, projects, keys (tokens only as `keyring:` / `env:` references), listed folders |
| `agents/<id>/` | an agent's state, journals, logs (rotated above 5 MB) and private folder |
| `layout.json`, `folders.json`, `comms.json`, `prs.json` | board layout, Folders view state, links and proposals, PRs tab |
| `skills/` | bundled and imported skill packs |
| `hooks/`, `hook-events/` | the Claude hook script and the events it reports |

Juno never touches your project folders' git config or branches, and never creates worktrees.
