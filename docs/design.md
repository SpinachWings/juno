# Juno — design

> Build spec for a clean rewrite. Open items are in §13.

## 1. Goal

Juno is a desktop orchestrator for coding agents. You define **agent profiles** and **projects**, then run
**agents** side by side on a board of terminal panes. One agent, **Juno**, is always there: she orchestrates the
others — finding idle agents, handing them tasks (with your approval when you didn't say who), collecting their
reports, and linking agents when you ask.

The previous version (ticket board + prototype agent mode) is archived on GitHub. Don't port its code; keep its look
and the lessons in §12.

## 2. Stack & look

- Java 27, JavaFX 27, Maven, Jackson. Windows first; macOS/Linux should work.
- **RichTextFX** for code, diffs and terminal text (selectable, copyable, syntax highlighted); **commonmark-java** for
  Markdown (agent messages, notepad preview); **java-keyring** (OS vaults, via JNA) for tokens.
- Theme: near-black greys, light blue `#6FD6FF` and magenta `#FF4FD8` accents, square corners; Share Tech Mono for
  UI text, VT323 for titles and headings (bundled, SIL OFL). Palette defined once at the top of one stylesheet.
- All data under `~/.juno` (override with `JUNO_HOME`). Every write is atomic (temp file + move).

## 3. Terms and statuses

| Term | Meaning |
| --- | --- |
| **Agent profile** | Reusable definition of who an agent is and how it runs (§5.4). |
| **Project** | A local repository folder, optionally with one GitHub or GitLab remote (§5.3). |
| **Agent** | A running instance of a profile, optionally on a project. One pane on the board, with its own name, colour and conversation. |
| **Juno** | The one orchestrator agent (built-in `orchestrator` profile). Always present; can't be deleted or hidden. |
| **Key** | A GitHub or GitLab token (§5.1). |
| **Link** | Two-way permission for two non-Juno agents to message each other (§4.1). |
| **Context** | What an agent's conversation holds, in tokens, against its model's window. |
| **Provider** | The CLI behind a profile. Only Claude Code for now (§10). |

There are no organisations.

| Status | Meaning |
| --- | --- |
| **Idle** | Nothing to do. New agents start here; Done returns here after 15 minutes (fixed). |
| **Queued** | Has work but "max agents working at once" is reached; shows its place ("Queued · 2nd"). |
| **Working** | Busy with a message or task. |
| **Awaiting decision** | Needs you, always with a reason tag: ❓ question · 🔐 permission · 📁 folder · 🔑 key · ⚙ profile (its profile was removed: pick another) · ⚠ failed · ⏸ interrupted. |
| **Done** | Just finished its task. |

Juno never counts towards "max agents working at once" and is never queued.

**Unread:** a separate dot (pane top bar and filter list) when something happened in a pane since you last looked —
finished, asked, reported, failed. Cleared by clicking into or scrolling the pane; survives Done → Idle.

## 4. Hierarchy and communication

### 4.1 Links
- Juno and every agent can always message each other (no link needed; can't be closed).
- Two other agents can message each other only while a **link** between them is open. Links are two-way, saved,
  survive restarts, and are removed with either agent.
- Opened/closed by: `/cantalkto<name>` / `/stoptalkingto<name>` in an agent's pane; asking Juno in plain words
  ("let bob and jim talk"); or Juno suggesting one — she must **ask you first** (approval card "Link bob ↔ steve? Why:
  …", **Link / Don't link**, or `/approve` / `/deny`). The server holds an unrequested link until you approve.
- Every change is announced in both agents' panes and Juno's ("winston ↔ bob linked by you" / "by Juno").
- Every agent knows each agent it can talk to by **name, profile and status**: in its preamble (§11), in each link
  announcement, and via `list_agents`. Prompts may depend on linked agents' **profile names** (e.g. the coder, §6.1).

### 4.2 Delegation
1. **Direct:** you type a task in an agent's pane. Juno isn't involved (unless "Tell Juno about direct tasks" is on:
   she gets a one-line note).
2. **Named, through Juno:** "juno, give bob the login bug" (Juno writes the task), or `/assign<name> <task>` in her
   pane (delivered by the app, no Juno tokens; she's told in one line and tracks the result). "Named" is judged from
   the words of your message, so it can be wrong ("don't give it to bob" names bob): under the default setting below,
   a task Juno writes for an agent you named waits 15 seconds on a card with **Undo / Send now** (or `/deny`,
   `/approve`) before it goes. Nothing reaches the agent during the wait. Under *Never* it goes at once, under *Always*
   it gets an approval card, and `/assign` always goes at once.
3. **Unnamed, through Juno:** "get someone to fix the login bug". Juno **proposes** an agent — approval card with the
   agent, why (profile, status, context use) and the task, **Approve / Edit / Choose another / Cancel** (or `/approve`,
   `/deny`). Held by the server until you decide. Setting **Juno asks before assigning**: *Only when I didn't name
   the agent* (default) / *Always* / *Never*.

Each task either **continues** the agent's conversation or **starts fresh**. Juno prefers fresh when the task is
unrelated or the agent's context is above the **fresh-start threshold** (default 60 %). **Starting fresh always needs
your approval**, whatever the setting: the card says what will be cleared ("Bob's conversation — 65 % context, last
active 10 min ago") with **Start fresh / Continue instead / Cancel**. The old conversation stays in the agent's
Conversations list (§7.9).

### 4.3 Keeping Juno's context small
- When an agent finishes or gets stuck on one of Juno's tasks, Juno gets a **capped report**: status, a summary of at
  most **Report length** characters (default 800), files changed, context use. To see more, you open the agent's pane.
- Reports arriving while Juno works are batched into one digest for when she's next idle.
- Agent-to-agent traffic never enters Juno's context unless she's part of it.
- Juno has her own context meter; `/compact` and `/new` work on her too.

### 4.4 Juno's MCP server
A local MCP server (HTTP on `127.0.0.1`, random token per launch) that every agent is connected to. Its tools are the
only way agents reach each other, and **the server enforces every rule in §4** — a call that breaks one is refused,
whatever a prompt says.

An agent may call a Juno tool only if its **profile's allowed tools** include it (e.g. `mcp__juno__hand_over`); the
server refuses it otherwise. Orchestrator-only tools work only for Juno.

| Tool | Allowed by default to | What |
| --- | --- | --- |
| `list_agents` | every profile | Agents the caller may talk to: name, profile, project, status, context use, colour. Juno sees all, plus links. |
| `send_message(to, text)` | every profile | Delivers a message into the target's conversation. Refused without a link. |
| `report(text)` | every profile | Sends a result or question to whoever assigned the current task. |
| `request_folder(reason)` | every profile | Asks you to give the agent a project (§7.7). |
| `assign_task(to, task, fresh)` | orchestrator only | Hands an agent a task (held for approval when required, or sent after a short wait in which you can undo it, §4.2); its result is reported back automatically. |
| `set_link(a, b, open, requested_by_user)` | orchestrator only | Opens/closes a link; an unrequested open is held for your approval. |
| `hand_over(to, task)` | coder | Gives work to a **linked** agent that edits files and is **idle** (Idle or Done, nothing queued); refused otherwise. It arrives as a task from the sender; the other agent messages its result back (§6.1). Counts as an agent-to-agent message for the loop guard. |
| `request_permission(...)` | — (Claude Code itself) | Permission prompts (§7.5). |

**Loop guard:** each agent-to-agent message carries a hop count; a chain stops after **Hop limit** hops (default 10)
without a message from you, and both agents and you are told why. A message from you to either agent resets it.

## 5. Settings (6 tabs)

### 5.1 Keys
- Any number of GitHub and GitLab keys. Each: **label**, **kind**, **host** (default `github.com` / `gitlab.com`),
  **token**, and optional **git identity** (commit author name and email, e.g. a bot account's). A key may be your
  own account's or a separate one (e.g. a bot account for coders).
- **Storage:** saving stores the token in the OS vault via java-keyring (Windows Credential Manager, macOS Keychain,
  Linux Secret Service) as `juno/key/<id>`; `settings.json` holds only `"token": "keyring:juno/key/<id>"`. Deleting
  a key deletes its vault entry. If no vault is available (e.g. Linux without a keyring daemon, WSL, SSH), the tab
  says so and offers `env:VARIABLE` (Juno stores the variable's name). **Never plain text.**
- Tokens are never shown in full after saving, logged, or put in prompts; they're read only when an agent starts.
- **Test** checks the key against the host's MCP server and REST API, and reports which of the permissions below are
  missing (read the repository, read pull requests and issues, push, comment).
- **Required token permissions** (shown in the Keys tab next to the token, per kind):

| Host | Token type | Permissions |
| --- | --- | --- |
| GitHub | **Fine-grained PAT** (resource owner = the repository's owner; select the repositories) or a classic PAT with `repo` | Repository permissions: **Metadata** read; **Contents** read & write; **Pull requests** read & write (enough to open PRs and reply to threads); **Issues** read (so agents can read issues you paste). An organisation may need to approve the token. |
| GitLab | **Two tokens** (see below) | **MCP:** a *fine-grained* personal access token with the **MCP tool** resource and **Execute** permission. Legacy PATs, project and group tokens are rejected by the MCP server. **Git and REST:** a normal personal access token (or project/group token) with `api`, `read_repository` and `write_repository`. |

- A **GitLab key holds two tokens**: the MCP token above, and the git/REST token (git over HTTPS and the Test button). Both are stored like any token (vault or `env:VARIABLE`). A GitHub key
  has one token for everything.
- The MCP URL comes from the host; **MCP URL override** under Advanced:

| Host | MCP URL |
| --- | --- |
| `github.com` | `https://api.githubcopilot.com/mcp/` |
| GitHub Enterprise Cloud, data residency (`<name>.ghe.com`) | `https://copilot-api.<name>.ghe.com/mcp` (verify) |
| GitHub Enterprise Server (self-hosted) | not supported in this version |
| `gitlab.com` | `https://gitlab.com/api/v4/mcp` |
| Self-managed GitLab | `https://<host>/api/v4/mcp` (GitLab 18.3+, MCP server enabled) |

#### 5.1.1 Which key an agent uses
Keys are set **only on agents** — not on profiles or projects: each agent can have one GitHub key and one GitLab key,
chosen when you create it (§7.10) and changeable in its options menu. The key for the host of the repository being
worked on is used for **everything** the agent does on GitHub/GitLab:
- **MCP tools** — its temporary `mcp.json` points at **Juno's host MCP proxy** (§6.3), which forwards to the host's
  MCP server with that key's token (the agent never holds the token);
- **git over the network** (fetch/pull/push) — for that process only, via a per-process credential helper (never
  written into git config or remote URLs); SSH remotes are used over HTTPS so the token applies;
- **commits** — the key's git identity, if set, is the author and committer (through the process's environment; nothing is written into your repository's git config).

**No key, no GitHub/GitLab** — enforced in the agent's process, not just its prompt: no host MCP servers in its
`mcp.json`; `GIT_ALLOW_PROTOCOL=file` (local repositories only); your credential helpers and SSH agent aren't passed
in; git can't prompt; `gh` and `glab` are disallowed. Local git (commit, branch, diff, log) still works. Anything
needing the host (fetch, push, opening a PR, reading an issue) stops at **Awaiting decision 🔑** with the reason
("No GitHub key is set for bob, so I can't push to acme/api — set one in bob's options menu").

#### 5.1.2 First run
A skippable setup: Claude Code path (checked), install the Claude hook, notes folder, a first project, and **adding
your keys** to Keys (your own and any bot accounts', per host), ready to pick when creating agents. Creating an agent
without a key for its project's host shows a warning in its setup form (§7.10).

### 5.2 System settings
- **Install / Uninstall Claude hook**, with status (installed / outdated / not installed). The hook makes "Awaiting
  decision" and "Done" exact. It must: never block or fail Claude Code (always exit 0, quickly, short timeout, even
  with Juno closed); only report for agents Juno started (checks `JUNO_AGENT_ID`); merge into
  `~/.claude/settings.json` without touching other settings or hooks; be idempotent; take a timestamped backup
  before every change.
- **Claude Code path** (default `claude` on PATH), used by profiles without their own.
- **Juno asks before assigning** (§4.2), **Tell Juno about direct tasks**, **Fresh-start threshold** (default 60 %).
- **Report length** (default 800 characters), **Hop limit** (default 10).
- **Max agents working at once**; **idle process timeout** (an idle agent's process is closed; its next message
  resumes it).
- **Notes folder** (§9).
- **Storage:** space per agent and total (journals and logs of the current and of earlier conversations) and **Clean up
  old data** (applies §7.9 limits now). Never touches branches, project folders or worktrees.

### 5.3 Projects
- **Name**, **local repository folder** (must exist), optional **remote** (GitHub `owner/repo` or GitLab
  `group/project`), optional **build & test commands** (e.g. `npm ci`, `npm test`). Projects hold no keys.
- Build & test commands are added to the allowed tools of agents that **edit files** in that project (e.g. coders),
  on top of the profile's (even locked) allowed tools.
- Checked on save: folder exists; remote well formed.

### 5.4 Agent profiles

| Setting | What | On built-in profiles |
| --- | --- | --- |
| **Name** | Unique; other prompts refer to profiles by name. | 🔒 |
| **Provider** | Claude Code only for now. | 🔒 |
| **Executable path** | Blank = Claude Code path (§5.2). | editable |
| **Model**, **Effort** | E.g. Opus 5.5 / Sonnet 5.5; low…max or CLI default. | editable |
| **Permission mode** | acceptEdits / auto / manual / dontAsk / plan / bypassPermissions. | 🔒 |
| **System prompt** | Who it is and how it works. | 🔒 |
| **Allowed tools** / **Disallowed tools** | Tool and command rules (`Bash(git:*)`, `mcp__juno__…`, host MCP tools). | 🔒 |
| **Max turns** | Per message, default 150. | editable |
| **Spend limit** | USD per process; blank = none. | editable |
| **Needs a project folder** | Its agents won't start without a project (§7.7). | 🔒 |
| **Edits files** | Its agents get the project's build & test commands allowed (§5.3), and can receive work through `hand_over` (§4.4). | 🔒 |
| **Profile commands** | Extra slash commands: name + prompt template. | built-in ones 🔒 |
| **Skills** | Assigned in the Skills tab. | bundled superpowers assignments 🔒; others editable |

Built-in profiles can't be deleted. **Duplicate** (any profile) makes a fully editable copy with all settings,
skills and commands — the way to change anything locked. Your own profiles are fully editable and deletable.

### 5.5 Skills
- Library of skill packs (`SKILL.md`, folder, or `.zip` / `.skill`), copied into `~/.juno/skills`. Each pack applies
  to the whole orchestration or chosen profiles; individual skills can be switched off globally or **per profile**.
- Agents get their skills as a session-only plugin (`--plugin-dir`); nothing is written into project folders.
- **Bundled: superpowers** (obra/superpowers, MIT). Default assignments: **coder** — every skill, including
  `using-git-worktrees` (Juno makes no worktrees; coders make their own when they need one, §7.8); **planner** — only `using-superpowers`, `brainstorming`, `writing-plans`. Bundled packs
  can't be removed, their built-in assignments are locked, and their files are refreshed from Juno's copy on start-up.
  They can be assigned to your own profiles with any skill selection.
- `/activate<skill>` (§8.1): the agent loads and follows that skill from its next message; a pack name activates its
  entry skill (`/activatesuperpowers` → `using-superpowers`).

### 5.6 Commands
Reference of every command (§8) — what, where (any pane / Juno / profile command), example — also via `/help`. Lists
the built-in prompts (§11) read-only.

## 6. Built-in profiles

| Profile | Model / effort | Permission | Needs folder | Edits files | Tools (defaults) |
| --- | --- | --- | --- | --- | --- |
| **orchestrator** | Opus 5.5 / high | default | no | no | Juno tools (all), Read, Grep, Glob |
| **coder** | Sonnet 5.5 / high | acceptEdits | **yes** | **yes** | file tools; `Bash(git:*)` except `git push --force`; project build & test commands; `hand_over`; host MCP (§6.3): read PRs/MRs, threads and issues, open PR/MR, reply. **Disallowed:** `gh`, `glab`. The proxy refuses approve, merge, close, resolve and every other host action |
| **planner** | Opus 5.5 / max | default | no | no | Read, Grep, Glob, WebSearch, WebFetch; superpowers `brainstorming`, `writing-plans`; host MCP: read only (issues, PRs/MRs). **Disallowed:** Edit, Write |
| **searcher** | Sonnet 5.5 / medium | default | no | no | WebSearch, WebFetch, Read |

Every profile also gets the "every profile" Juno tools (§4.4). Host MCP tool names are in §6.3. Profiles that existed
earlier (a reviewer, a notes writer) are gone: an agent that had one waits at **Awaiting decision ⚙** until you pick
another profile for it in its pane or the inbox; its conversation, project and keys are kept, and a profile of your own
that allowed the removed tools simply loses those rules.

### 6.3 Host MCP access
Agents never talk to GitHub's or GitLab's MCP server directly. Their `mcp.json` points at a **proxy in Juno's MCP
server**, which holds the key's token, forwards only the calls the agent's profile allows, and refuses the rest with a
clear message. Only three kinds of action exist: **read** (PRs/MRs, diffs, files, commits, review threads, issues),
**open** a PR/MR and **reply** to a thread; every other tool of the host's server (approve, merge, close, review,
resolve, edit, create branches…) is unknown to Juno and refused, whatever a profile says. A profile allows them with
`host:read`, `host:open` and `host:reply` rules.

| Capability | GitHub tool (`api.githubcopilot.com/mcp/`) | GitLab tool (`<host>/api/v4/mcp`, 18.3+; GA in 19.5) |
| --- | --- | --- |
| Open PR/MR | `create_pull_request` | `save_merge_request` (no `merge_request_iid`) |
| Read PR/MR, diff, files, commits, threads, mergeability | `pull_request_read` (`get`, `get_diff`, `get_files`, `get_commits`, `get_reviews`, `get_comments`, `get_review_comments`); `get` carries `mergeable` / `mergeable_state` | `get_merge_request` (`include`: diffs, commits, notes, discussions); `has_conflicts`, `detailed_merge_status` |
| Read an issue and its comments | `issue_read` (`get`, `get_comments`), `list_issues`, `search_issues` | `get_issue` and its notes (names from the documentation) |
| Reply to a thread | `add_reply_to_pull_request_comment` | `save_note` with `discussion_id`, or `save_merge_request_review` `reply_discussion` |

**What each profile may do through the proxy:**
- **coder:** read, open a PR/MR, reply to threads. Replies carry the marker "🤖 name (profile):", added by the proxy
  when missing, so people can tell an agent's words. Opening a PR/MR adds an entry to the PRs tab (§7.11).
- **planner:** read only (issues and PRs/MRs), for the issues you paste into its chat.
- Other profiles: no host MCP access. A user-made profile gets the same three choices in its allowed tools.
- Juno itself only calls REST for the key's Test button, with the key's (git/REST) token.

The GitHub server needs the `Mcp-Protocol-Version` header and exposes extra toolsets at `/mcp/x/<toolset>`; the proxy
asks for the default toolset plus `actions` (read-only listings). GitHub's tool names were read from the live server;
GitLab's come from its documentation and are re-checked at the first GitLab run (§13).

### 6.1 What each system prompt covers
- **orchestrator (Juno):** does five things: opens links, closes links, passes on reports from single agents, gives
  status updates on the whole board, and assigns tasks (§4.2), as self-contained tasks; asks before linking on her own;
  never does the work herself; keeps reports to you short. She has no say over git, branches or worktrees.
- **coder:**
  - **Git first.** It checks the folder is a git repository; if not, it says so and stops — it never runs `git init`
    and never offers to. Before touching a file it asks you (one ❓ question): **a new branch** (which existing branch
    to start from, e.g. `main`, and the new branch's name, e.g. `feature_123`) **or an existing branch to work on
    directly** (e.g. `main`). A task that names them counts as answered; it asks again if the task changes. Committing
    on the repository's default branch needs your explicit yes to that exact branch. It never force-pushes.
  - **Next to other agents** (§7.8): the contacts list shows each linked agent's status, project and branch. In a
    shared folder it doesn't switch branches under an agent that is working there: it makes its own git worktree with
    superpowers' `using-git-worktrees` skill (inside the project, git-ignored). On the same branch it tells linked
    agents which files it will change before it starts, asks before touching something one of them claimed, commits in
    small steps, pulls before pushing and says when it has pushed.
  - **Handing over:** when part of its task belongs elsewhere or its context is nearly full, and a linked coder is
    idle, it gives that part over with `hand_over` and tells you; the other agent messages its result back.
  - May split large tasks across superpowers sub-agents (shown nested in its pane, §7.3).
  - When done, in order: (1) superpowers' per-task and final whole-branch reviews; (2) run the project's build and
    tests locally and fix failures — if the project has no build & test commands, ask you how to test; (3) push and
    **open an ordinary PR/MR via the host MCP tools** (no remote → stop at the commit). At superpowers' finishing step
    always "push and open a PR" this way: never merge locally, never discard, never `gh`/`glab`.
  - Never approves, merges or closes a PR/MR; merging is yours.
  - **Issues:** when you paste an issue link or number (GitHub or GitLab), it reads the issue and its comments through
    the host MCP tools first and works from them.
  - Acts on review comments only via `/checkforcomments` and on merge conflicts only via `/checkformergeconflicts`
    (§6.2).
- **planner:** talks a feature through with you and writes the plan **in its pane only** — including architecture
  and the choice of frameworks, libraries and tools, with reasons and alternatives. It **never writes files**; you
  copy what you need from the pane. When superpowers is activated it uses **only `brainstorming`** (explore one
  question at a time) **and `writing-plans`** (turn the agreed design into a step-by-step plan), never starts
  implementing, and ignores those skills' instructions to save files — the plan goes in the pane. When you paste an
  issue link or number it reads the issue (read-only) and plans from it.
- **searcher:** searches the web, reads sources, answers with findings and a link for each source.
### 6.2 Coder commands
Both are built-in profile commands that work on GitHub and GitLab. **Nothing changes until you've decided.**

**`/checkforcomments`**
1. Fetch unresolved review comments on the PR/MR of its branch — people's and agents' (recognised by the marker).
2. Show the full numbered list, grouped by file.
3. One at a time: the comment, the code, whether it agrees and why, its proposal (change, or reply explaining why
   not). **Wait for your decision:** fix as proposed / fix differently (you say how) / reply only / skip.
4. When all are decided: make the changes locally, run the tests, commit, push.
5. Show the replies; post only after you approve. Skipped comments get no reply.

The coder never resolves threads (the proxy refuses it); people's threads are left for you or them.

**`/checkformergeconflicts`**
1. Find the PR/MR of its branch and ask the host whether it has conflicts (GitHub `mergeable` / `mergeable_state`,
   GitLab `has_conflicts` / `detailed_merge_status`). None → it says so and stops. It refuses to go on over uncommitted
   changes (it tells you what they are; it never stashes or discards).
2. Locally: fetch the base branch and start the merge (`git merge --no-commit --no-ff origin/<base>`); list the
   conflicted files. If git finds none, it aborts the merge and stops.
3. One conflicted file at a time: the conflicting hunks, what each side was doing, its proposal. **Wait for your
   decision:** resolve as proposed / resolve differently / take mine / take theirs / abort the merge. No conflicted
   file is edited before you've answered for it.
4. When every file is resolved: build and test, commit the merge, push normally (never forced), and report.

Both happen in the agent's own folder; nothing is resolved on the host.

## 7. Board

### 7.1 Layout
- The board is split into panes, one agent each; **no empty panes**. Juno's pane is always shown (movable, not
  closable or hideable).
- **Split** a pane right or down (top bar ⇥ / ⤓); the new half must be filled at once — a new agent (set up in the
  cell, §7.10) or a hidden one. **+ New agent** in the toolbar splits the pane you last used. Splits halve the pane;
  **panes can't be resized**.
- **Move** by dragging a pane's top bar onto another: they swap. The layout is saved.
- **Closing a pane hides the agent** (it keeps running; neighbour takes the space). Deleting is in its settings.
- Minimum pane size: the board scrolls vertically; horizontally only when the window is too narrow.
- **Maximise:** double-click the top bar (or ⤢) to fill the board; again or Esc restores the layout exactly.

### 7.2 Top bar and toolbar
- **Pane top bar:** status (+ reason tag), unread dot, name, profile, project and branch (or "no folder"), model
  and effort; resume, stop, maximise, split, **⋯ options menu** (§7.10). The agent's colour outlines the pane.
- **Toolbar:** title, **+ New agent**, board / notes / PRs toggle, **inbox**, **usage-limit meter**, Settings.
- **Inbox:** count and list of everything waiting on you — assignment proposals, tasks you can still undo, link suggestions,
  fresh-start approvals, permission requests 🔐, folder requests and repositories to clone 📁, missing keys 🔑, removed profiles ⚙,
  questions ❓ (including `/checkforcomments` and `/checkformergeconflicts` decisions), failures and interruptions ⏸.
  Each item: agent, reason, one-line summary, its answer buttons where it has them, else "go to pane". Items disappear
  when answered anywhere.
- **Usage-limit meter:** your Claude plan's limits used, from Claude Code's rate-limit events (`5h 60% · week 68%`,
  reset times on hover; amber > 80 %, red > 95 %). Dollar figures anywhere are labelled **"API-price estimate"**.

### 7.3 Rendered output
Readable blocks, not raw events:
- messages as Markdown;
- thinking: a collapsed "Thinking…" block (text if the provider exposes it, else its length); plans/to-dos as
  checklists that tick off;
- file edits as diffs (file name, red removed, green added); new files as highlighted code;
- commands and output (long output collapsed);
- web searches (query, result titles/links) and fetched pages (address, what was taken);
- messages between agents, with the sender's name and colour;
- **sub-agents** (e.g. superpowers implementers/reviewers): one collapsible block each with its task, tool calls,
  diffs and result (via Claude Code's `--forward-subagent-text`);
- questions and permission requests, highlighted until answered;
- per-turn usage and context, kept quiet.

Everything is **selectable and copyable** (drag-select across blocks; copy buttons on every message and code block —
Markdown / plain code). This is how you take planner and notes-writer output.

### 7.4 Under the terminal
- **"can talk to:"** Juno and linked agents, in their colours (Juno's pane: "everyone").
- **Context meter:** `ctx 46k / 200k · 23%` — amber above the fresh-start threshold, red near the limit — plus tokens
  of the last turn and conversation and their API-price estimate; hover for input/output/cache read/cache write.

### 7.5 Permission prompts
When an agent wants something its permission mode and allowed tools don't cover, Claude Code asks Juno
(`--permission-prompt-tool` → `request_permission`). The agent is **Awaiting decision 🔐**; the request (tool,
command or diff) shows in its pane and the inbox:

| Answer | Effect |
| --- | --- |
| **Allow once** | This call runs. Also `/approve`. |
| **Always allow for this agent** | Saves a per-agent rule, then runs the call. |
| **Always allow for `<profile>`** | Adds the rule to the profile's allowed tools (agents get it from their next start). Not offered for built-in profiles. |
| **Deny** | Doesn't run; the agent is told. Also `/deny`. |

- Before saving, the proposed rule is shown and **editable** (default: program plus first word, e.g.
  `Bash(npm install:*)`; for file tools the folder, e.g. `Edit(src/**)`), with a plain-words description ("any
  `npm install` command").
- Per-agent rules survive restarts; matches are allowed automatically with a quiet line ("✓ allowed by rule …").
  They're listed in the agent's options menu with Remove (§7.10).
- Disallowed tools always win: a matching request is refused without asking.

### 7.6 Filter list
Side list of all agents (colour, unread dot, name, profile, status + reason, context %) with a show/hide checkbox per
agent, text search and status filter (incl. "unread"). Hidden agents keep running and are flagged when they need you;
showing one puts it back where it was. Juno can't be hidden.

### 7.7 Agents without a folder
- A project is optional. Without one, an agent works in a private empty folder under `~/.juno` and can't reach your
  files.
- If its profile **needs a project folder** and it has none, its first message is held, it shows **Awaiting decision
  📁**, and its pane asks you to pick (or create) a project; then the message is sent.
- Any agent can ask for one mid-task with `request_folder` (same picker). Changing an agent's project restarts its
  process, keeping the conversation.

### 7.8 Git, branches and sharing a folder
- **Juno makes no worktrees and installs no git hooks.** Every agent works in its project's folder (or, without a
  project, in a private empty folder). Juno never switches a branch, never commits, never touches your repository's git
  config; the agent's top bar and `/getcontext` show the branch currently checked out in its folder.
- **Branch choice is the coder's first question** (§6.1): start from a source branch onto a new branch, or work
  directly on an existing one. Without git in the folder the coder says so and stops; Juno never offers `git init`.
- **Committing to the default branch has no hard guard any more:** it is the coder's prompt (explicit yes needed), your
  permission rules and the PR flow. The host proxy still refuses merge, approve and close.
- **Several agents in one project** share one working tree, so Juno tells them (§6.1): the contacts list and
  `list_agents` show each linked agent's status, project and branch; a coder that needs a different branch from an
  agent working in the folder makes its own git worktree with superpowers' `using-git-worktrees` skill (inside the
  project, git-ignored), and on the same branch coders tell each other which files they will change. They can message
  only linked agents; an unlinked one in the same folder is a reason to ask you for a link.
- The agent's work in such a worktree is under the project's folder, so its permissions and Juno's file tracking
  still apply; `/diff` shows the project folder's uncommitted changes (not a nested worktree's), while "files
  changed" in reports comes from the agent's own edits.

### 7.9 Conversations and data limits
- Starting fresh or `/new` keeps the previous conversation in the agent's **Conversations** list (started/ended, first
  task, size, last summary). Choosing one switches back (resumed by id); the current one takes its place. Not while
  working.
- Each agent keeps its **last 10** earlier conversations, none older than **30 days**; older ones drop off and Juno
  deletes its own journal and log for them. If Claude Code has already deleted a transcript, its entry is greyed out
  ("no longer available").
- The current conversation's journal and log are always kept; logs rotate above 5 MB (one old copy).
- **Deleting an agent** removes everything Juno created for it (journals, logs, conversation
  list, per-agent rules); your project folder and branches are never touched; Juno **asks** before also deleting its Claude Code transcripts (`~/.claude/projects/`).
- Limits apply on start-up and after each conversation ends.

### 7.10 Adding an agent, and its options menu
**Adding:** a split (or + New agent) shows a setup form inside the new cell — **Name** (unique; used in `/hey<name>`),
**Profile** (fixed once created), **Project** (optional; required to start if the profile needs a folder), **Colour**
(pre-filled), **GitHub/GitLab key** (optional, picked from Keys; blank = no host access; warns if the project's host
has none). **Create** turns the cell
into the terminal; **Cancel** removes the cell.

**Options menu (⋯):** editable **any time, even while working**; changes to how it runs apply from its **next
message** (never interrupting the current step; shown as "applies from the next message"):
- name, colour (at once);
- project (waits until it isn't working; process restarts, conversation kept);
- model, effort (per-agent overrides, same as `/model`, `/effort`);
- GitHub/GitLab key;
- **Allowed for this agent** rules (when/why added, Remove);
- Conversations (§7.9), Usage (as `/usage`);
- **Delete agent** (confirmation; not Juno).

### 7.11 PRs tab
The third toolbar toggle, **PRs**, lists the PRs/MRs your agents opened through Juno (§6.3), newest first: repository
and number, title, agent (in its colour), the branch it comes from and when. **Open** opens the PR/MR on the host in your
browser, **Copy link** copies it, and **Close** removes the entry. Entries stay until you close them; closing never
touches the PR/MR on GitHub or GitLab. They are kept in `~/.juno/prs.json` and survive restarts. If the host's answer
had no link, the entry is still listed without one.

## 8. Commands

**Autocomplete:** typing `/` in a pane's input opens a list of the commands available there (any-pane, Juno-only in
Juno's pane, and the agent's profile commands), filtered as you type, each with a one-line description. After a
command that takes a name (`/hey`, `/cantalkto`, `/stoptalkingto`, `/assign`) it suggests agent
names (with colour and status); after `/activate`, the agent's skills; after `/model` / `/effort`, the options. Tab or
Enter accepts, Esc closes. Both forms work: `/heybob …` and `/hey bob …`.

Names in commands ignore case and spaces. A plain message gives work or answers a question; **while an agent works,
plain messages are refused** with a hint to use `/hey<name>` (so a stray keystroke can't interrupt it). Juno's
commands take precedence over same-named Claude Code commands; any other `/command` is **not sent** ("`/init` isn't
supported in Juno — see /help").

### 8.1 Any pane
| Command | What |
| --- | --- |
| `/hey<name> <text>` | Instructions or a question for `<name>` while it works; read when its current step ends. From any pane. |
| `/cantalkto<name>` / `/stoptalkingto<name>` | Open / close a link between this agent and `<name>`. |
| `/getcontext` | This agent's context summary (§8.2); in Juno's pane, Juno's. |
| `/activate<skill>` | Load and follow one of its skills from the next message (pack name → entry skill). Pane shows it's queued; repeat to cancel. Only skills it has; not while working. |
| `/compact` | Summarise the conversation to free context. |
| `/new` | Start a new conversation from the next message. |
| `/stop` / `/resume` | Stop (conversation kept) / continue an interrupted or stopped agent. |
| `/approve` / `/deny` | Answer the pending permission request or proposal. |
| `/model <model>` / `/effort <level>` | Applies from the next message (allowed while working). |
| `/diff` | Uncommitted changes in its folder, as diffs (no tokens). |
| `/usage` | Token, cost and context history. |
| `/copy` | Copy its last reply. |
| `/clear` | Clear the pane (log kept). |
| `/help` | Commands available here. |

### 8.2 Juno's pane only
| Command | What |
| --- | --- |
| `/assign<name> <task>` | Give a task through Juno without spending her tokens; she tracks the result. |
| `/status` | Every agent: status, project, current task, context %. |

**Context summary** is built by the app from the journal — instant, no tokens, nothing added to any conversation:
status, context use, current or last task, files read and changed, links, last summary, conversation age. Shown to you
only. To learn what an agent understands, ask it with `/hey`.

### 8.3 Profile commands
Defined on profiles (§5.4), e.g. the coder's `/checkforcomments` and `/checkformergeconflicts` (§6.2); expanded from their template and sent as a
message.

## 9. Notepad
- A toggle switches between the board and **note view**: file tree of the **notes folder**, tabs, Markdown editor with
  live preview; create, rename, move, delete notes and folders.
- Agents never get the notes folder; Juno refuses a notes folder inside a project folder or vice versa.
- One agent's terminal can be shown beside your notes — the same agent (same output and input), still on the board.

## 10. Talking to Claude Code (Provider)
- No SDK (none exists for Java): Juno runs `claude -p --input-format stream-json --output-format stream-json
  --verbose …` as a child process, writes messages to stdin as JSON lines and reads events from stdout with Jackson —
  the protocol Anthropic's Agent SDKs use.
- Per launch: `--model`, `--effort`, `--permission-mode`, `--allowedTools`, `--disallowedTools`, `--max-turns`,
  `--max-budget-usd`, `--append-system-prompt-file`, `--forward-subagent-text`, `--strict-mcp-config` +
  `--mcp-config` (Juno's server, plus the host's server for the agent's key), `--plugin-dir`,
  `--permission-prompt-tool`, `--resume <session>`; environment `JUNO_AGENT_ID` and the git settings of §5.1.1.
- **Version check:** Juno declares a **minimum Claude Code version** (the one its flags were verified against during
  development). It runs `claude --version` at start-up, when the Claude Code path or a profile's executable path
  changes, and in first-run setup. Too old or not found → a clear message ("Claude Code 2.1.312 or later is needed;
  you have 2.0.9. Run `claude update`."), and agents using that executable don't start (they show **Awaiting
  decision ⚠** with the same message). Newer versions are allowed; if a launch fails on an unknown flag, the error
  names the flag and the version.
- A `Provider` interface holds everything CLI-specific (command line, output → events, resume, MCP config, skills,
  hook install, version check), so another CLI can be added without touching the rest.
- An agent's output (event list) is separate from any pane showing it (board and note view).

```
dev.juno
├── app        JavaFX entry point, main window, toolbar, board/notes toggle
├── model      profiles, projects, keys, agents, links, settings (plain data, JSON)
├── store      ~/.juno persistence: settings, agents, journals, logs
├── provider   Provider interface + AgentEvent types (Message, Thinking, ToolUse, Diff, Usage, Result…)
│   └── claude ClaudeCodeProvider: command line, stream-json parser, hook installer, MCP config
├── runtime    AgentRuntime (agents, statuses, queueing), AgentProcess (one CLI process), Journal
├── comms      JunoMcpServer, links, delegation/approvals, reports, loop guard, host MCP proxy rules
├── git        git environment and credential helper, /diff
├── prompts    built-in prompt templates (§11)
├── skills     skill library, bundled packs, per-agent plugin folders
└── ui         board, panes and output blocks, setup form/options menu, settings tabs, notepad, PRs tab, filter list
```

## 11. Built-in prompts
Besides profiles' system prompts, all natural-language text Juno sends lives as templates in `prompts/*.md`
(`{{placeholders}}`), listed read-only in Settings → Commands:
1. **Agent preamble** (before every system prompt): its name, profile, project and folder (or none); who it can talk
   to (name and profile) and how; that links change (announcements; `list_agents` is current); how to report and ask
   questions; that `[Message from …]` and `[Extra info from the user]` blocks may arrive mid-task.
2. **Juno MCP tool and parameter descriptions** (§4.4).
3. **Tool results**, esp. refusals ("You are not linked to bob. Ask Juno to link you, or ask the user.").
4. **Wrappers** for what Juno injects: agent messages, tasks, `/hey` info, reports and digests, approval outcomes, link
   announcements, loop-guard notice, hand-overs.
5. **Resume recap** after an interruption.
6. **Profile command templates** (part of profiles).

## 12. Non-negotiables (lessons from v1)
- GitHub/GitLab access only through the agent's own key (§5.1.1): temporary `mcp.json` (deleted when the
  process ends) and `--strict-mcp-config`; no key → no host access at all. Tokens never in prompts or logs.
- Session id, status, links and an event journal are saved as they happen. After a crash or shutdown an agent shows
  **Awaiting decision ⏸**, and Resume continues the same conversation with a recap of what it had done.
- Working/idle follows the agent's output events, not message counts (a message sent mid-turn can be merged into the
  current turn).
- On Windows, launch npm-installed CLIs via `cmd.exe /c`.
- Token usage and context size recorded per turn for every agent.

## 13. Open items
**To write (Milestone 0):** the built-in profiles' system prompts (§6.1) and the prompt templates (§11).

**Answered by Milestone 5a** (see §6.3 and `docs/progress.md`): the tools exist on both hosts; some dangerous actions are
options of tools a profile needs, so Juno filters per action in a proxy (now only read / open / reply are allowed);
GitLab's MCP server takes a fine-grained PAT only, so a GitLab key holds two tokens (§5.1).

**Verified live on GitHub** (Milestone 5a): opening a PR, reply, close, and reads. **Still to verify:** everything on
GitLab (names are from its documentation), including `/checkforcomments`, `/checkformergeconflicts` and reading issues;
GitHub's `issue_read` and `mergeable_state` with real data; the `<name>.ghe.com` MCP URL.
