# Progress

## Milestone 6c: thin host proxy, no `read_api`. Done (2026-11-03)

- **Proxy shrunk** (`comms.HostActions`, `runtime.HostAccess`): it holds the token, marks replies and notes opened PRs/MRs;
  it no longer classifies, refuses or filters tools. Profiles keep `host:read` / `host:open` / `host:reply` (and
  `host:*`) as shorthands that Juno expands into Claude Code's own `mcp__github__<tool>` / `mcp__gitlab__<tool>` rules at
  launch (allowed and disallowed). The old blanket "allow the whole server" is gone, so a tool outside the shorthands
  (merge, close, approve…) now **asks you through the permission prompt** instead of being refused by Juno. Removed: the
  `not-allowed` / `unknown` results, `classify` / `allowed` / `listable`, the per-action refusal lines in the pane.
- **GitLab git token needs only `read_repository` and `write_repository`** (no `api`, no `read_api`): the Test button's
  GitLab check is a `git ls-remote` with the token in that one child's environment (`GitAccess.forToken`); push can't be
  tried without pushing, so it says so. The key form and messages call it "Git token" (it was "Git and REST token").
  GitHub's Test still uses REST (its fine-grained token carries the permissions it probes).
- **Tests:** `HostLogicTest` rewritten (expansion, open/marker detection, PRs entries, both Tests); 137 in all.
- **Consequences to know:** a profile of your own that had `host:review` etc. keeps losing those rules; `host:*` now
  means every tool without asking, merge included. GitLab's tool names are from documentation, so a name that is wrong
  means an extra prompt, not an open door. Not run against a real host after this change.

## Milestone 6b: changes after the notepad. Done (2026-11-03)

A change request between Milestone 6 and 7 (ten items). `docs/design.md`, `docs/prompts/`, `CLAUDE.md` and the tests were
changed with the code; the older sections below that describe removed things (reviews, worktrees, `get_agent_output`…)
are history.

### Removed
- **Reviewer and notes writer profiles**, and everything only they used: `runtime.Reviews`, `git.Snapshots`,
  `comms.Pipelines`, `start_review` / `end_review`, `Agent.Review` / `reviewOpen` / `heldReport` / `staleSnapshots`, the
  held report, the **Waiting ⏳** status, the pipeline wait limit setting, the pipeline card and inbox section, the
  proxy's posting gates and its review / resolve / approve / request-changes / merge / close / edit actions, the REST
  clients' pipeline, checks and thread code (what is left of `HostApi`, `GitHubApi`, `GitLabApi` is the key Test), the
  token permissions for Actions, Commit statuses and Administration, and their prompts, tests and screenshot driver.
- **Juno-managed worktrees and protected-branch hooks** (`git.Worktrees`, `git.GuardHook`, the Storage worktree columns
  and "Remove worktree", the per-process identity in git config, `Agent.baseBranch` / `prBases`). Every agent works in its
  project's folder.
- **Juno's** `get_agent_output`, `get_agent_context`, `/getcontextfrom` and `/broadcast`. She keeps `set_link`,
  `assign_task`, `list_agents`, `report`, `/status`, `/assign`, `/hey`; `/getcontext` (the user's own summary) stays.

### Added
- **Coder prompt** (item 3, 4, 5, 6, 9): git check first, never `git init`; one ❓ question for *source branch + new
  branch* or *an existing branch*; explicit yes before committing on the default branch; coordination rules (different
  branches in a shared folder → its own `using-git-worktrees` worktree inside the project; same branch → announce files,
  ask before touching a claimed file, small commits, pull before push); hand-over; read pasted issues first.
  `using-git-worktrees` is now one of the coder's superpowers skills.
- **`/checkformergeconflicts`** (profile command of the coder): asks the host whether the PR/MR has conflicts, merges the
  base locally, asks per conflicted file (resolve as proposed / differently / mine / theirs / abort), then builds, commits
  the merge and pushes normally. `/checkforcomments` is unchanged (it already named the host generically).
- **`hand_over(to, task)`** (coder): a linked agent that edits files and is Idle or Done with nothing queued; the task
  arrives as `[Task handed over to you by <name>]`; the result comes back by `send_message`. Same route and loop guard as
  `send_message`.
- **Coordination data:** the preamble's contacts list and `list_agents` show each agent's status, project and branch.
- **Git state in the system prompt:** the branch checked out (or "not a git repository") when the process starts.
- **Host proxy**: only read, open and reply exist (`HostActions`); reading includes issues (`issue_read` etc.), so the
  planner got `host:read` (it needs a key on its agent like any agent that uses the host).
- **PRs tab** (`ui.prs.PrsView`, `store.PrStore`, `model.PullRequestEntry`, `~/.juno/prs.json`): opening a PR/MR through the
  proxy adds an entry (repo, number, title, branch, link, agent); Open / Copy link / Close; closing never touches the host.
- **Agents whose profile is gone** wait at **Awaiting decision ⚙** with a profile picker in the pane (`Reason.PROFILE`,
  `AgentRuntime.setProfile`); nothing else about them changes. A saved status `WAITING` reads back as Idle.
  Own profiles lose the rules of removed tools and actions on load (`SettingsStore.RETIRED_RULES`).
- **Tooltips on shortened text** (item 10): one scene-wide filter (`Ui.truncationTips`, installed by `Theme.apply`, dialogs
  and the inbox popup) shows the whole text of any label, button, tab, list or tree row that JavaFX cuts with an ellipsis,
  unless it has a tooltip of its own. The cuts Juno made itself were replaced: the inbox and permission cards now hold
  the whole text in a height-limited label; the output view's cut lines (error, sub-agent task, permission detail, the
  " …" of multi-line commands) carry the full text in a hover tip (`StyledDoc.shortened` / `tip`); stored text the user
  sees (first task, summaries, failure details, gists, rule reasons, context summary lists) is kept whole on one line
  instead of cut. Text cut for the **agent** (resume recap, tool descriptions in recaps) is unchanged.

### Checked
- `mvn verify`: 138 tests. New or rewritten: `HostLogicTest` (proxy actions, PRs entries from GitHub and GitLab answers,
  Test lines), `CommsTest` (hand-over rules, contacts with branch, tool lists), `BoardRuntimeTest` (project folder, missing
  profile, `WAITING` read back), `PromptsAndProfilesTest`, `SettingsStoreTest` (dropped profiles and retired rules),
  `PrStoreTest`, `StyledDocTest`.
- Real window (`dev.juno.ui.ChangeScreens`, scratch home): the PRs tab with two entries and closing one (list and file
  agree), an agent with a removed profile (card and status), a cut label getting its whole text as a tooltip and losing it
  when it fits.
- Real Claude Code 2.1.314, haiku / low (`dev.juno.runtime.CoderLive`, scratch repository): in a git repository the coder
  asked for the branch and created nothing first; in a folder without git it said so, didn't run or offer `git init`, and
  created nothing; given "new branch feature_123 from main" it made the branch, the file and the commit.

### Not verified
- `/checkformergeconflicts`, `hand_over` and issue reading against real hosts or models (hand-over is tested with the fake
  provider); GitHub's `issue_read` / `mergeable_state` and everything on GitLab are from documentation. The key Test now
  also reads issues on both hosts.
- Hovering the tooltips with a real mouse (the filter was driven with a synthetic event).
- The old `docs/progress.md` text below still describes the removed parts.

### Decisions (not in the spec)
1. Branch ask is **prompt only** (your call); there is no hard guard against committing on the default branch now.
2. Agents with a removed profile **wait for you to pick another** (your call); nothing is deleted.
3. A PR/MR is listed when the host's answer to the open call comes back successfully; a missing link still gives an entry.
4. `hand_over` needs the target to edit files (a coder-like profile) and be idle; Done counts as idle. The task does not
   replace Juno's task on the target; the target reports back with `send_message`, not `report`.
5. `/diff` shows the project folder's uncommitted changes; "files changed" in reports comes from the agent's own edits.
6. Git identity for a key is passed as `GIT_AUTHOR_*` / `GIT_COMMITTER_*` in the process environment.
7. `Task.gist` and conversation summaries are now the whole text on one line; the inbox / pane shorten them with a tooltip.
8. Design sections: reviews (§6.3–§6.5) are gone; the host proxy became §6.3; the PRs tab is §7.11; §7.8 is "Git, branches
   and sharing a folder".

### Known issues
- Two coders in one folder rely on the prompt and on messages; nothing stops one from switching the other's branch.
- The ⚙ glyph is used for "profile" because the 🧩 glyph is missing from the monospace font.
- The Test button's write check still names "Comment and open PRs" for a review-write permission probe.

### For the next milestone (7)
- Read `docs/design.md` against this (it was rewritten for 6b); `docs/build-prompts.md` Milestones 3–5 are history.
- `NotesScreens`, `ChangeScreens`, `BoardScreens` (layout, crash) and `CoderLive` are the live-check drivers.

## Milestone 6: notepad. Done (2026-11-02)

### Done
- **Board / Notes toggle** (`app.MainWindow`): switching to Notes swaps the board area for `ui.notes.NotesView`; switching back
  saves every edited note and gives the lent agent pane back. **+ New agent**, and anything that asks the board to show an
  agent (inbox "Go to pane", the agent list), switch back to the board first (`BoardView.onShowRequested`).
- **Note view** (`ui.notes.NotesView`, `NoteTab`): the notes folder's tree (folders first, then notes; `.md`, `.markdown`,
  `.txt` only; dot-files and folders such as `.git` / `.obsidian` hidden), tabs, a Markdown editor (light colouring of headings,
  fences, quotes, lists, code, bold) with a live preview rendered like agent messages (`ui.pane.MarkdownPreview`, reusing
  `MarkdownText`, so code blocks have their copy button and tables work). Edit / Split / Preview toggle.
- **Create / rename / move / delete** notes and folders (`store.NotesFolder`, atomic writes, nothing outside the folder
  reachable, `..`, absolute paths and symlinks out are refused): "+ Note" / "+ Folder", right-click menu (Open, New note
  here, New folder here, Rename, Move to…, Delete…), F2 / Delete / Enter, drag a row onto a folder to move it. Open tabs
  follow a rename or move; a delete closes its tabs. Delete asks and says how many items a folder holds.
- **Saving:** a note is saved 0.7 s after typing stops, on Ctrl+S, when its tab is closed, on switching to the board and on
  closing the window. A note whose file changed on disk is never overwritten unasked: a clean tab reloads (on ⟳ or when the
  note is opened again); an edited one shows "changed on disk" with **Load the disk version** / **Keep mine**.
- **Overlap checks** (`model.Validation.overlap`, `Validation.system(s, projects)`, `Validation.project(p, others, notes)`): saving a
  notes folder inside a project folder, around one, or equal to one is refused in Settings → System; saving a project the same
  way is refused in Settings → Projects and in the first-run project step. Paths are compared as real paths (symlinks,
  `..`, case on Windows), and a folder that doesn't exist yet counts by its nearest existing parent. If it's there anyway
  (settings edited by hand), the notepad shows the reason and an "Open settings" button instead of the tree.
- **One agent beside the notes:** the picker "Agent beside the notes" (none, Juno or any agent) lends that agent's pane
  from the board (`BoardView.borrow` / `release`): the same `AgentPane`, so the same output, input, permission cards and
  ⋯ menu. Its board cell shows "<name> is beside your notes". Split / maximise / hide are hidden while it's there
  (`AgentPane.setEmbedded`). Leaving the notes, choosing another agent or deleting the agent gives it back.
- **Remembered** in `~/.juno/notes.json` (`store.NotesState`): open tabs and the active one (dropped if the notes folder
  changed), the view mode and the agent beside the notes.
- **Unit tests** (171 in all, `mvn verify`): `NotesTest` (tree, create / rename / move / delete, name rules, escaping the
  folder, refusing a non-UTF-8 file, no temp files left, both overlap checks incl. a shared name prefix).

### Checked live (no Claude Code needed) with `dev.juno.ui.NotesScreens` (test sources), scratch `JUNO_HOME`, scratch notes folder
`java -cp "target/classes;target/test-classes;<cp>" dev.juno.ui.NotesScreens <scratch dir> <output dir>` writes a PNG per
step: the board; Notes with no folder set (reason + Open settings); the tree; a note in Split view; typing changing the
preview and the file on disk 1.6 s later; three tabs; Juno's pane beside the notes (it was the board's pane: `/help`
typed through the board's handle appeared in it) and back on the board with that output; Edit and Preview only; the
tabs and the agent coming back on the second visit; a clean note following a change on disk; the conflict banner with
the disk left alone; the overlap message.

### Not verified
- Dragging a tree row onto a folder (no way to synthesise a drag here; the move it calls is tested, and "Move to…" is the
  same call). The Rename / Move / Delete dialogs and the context menu weren't driven (their file operations are tested).
- A real agent working beside the notes (Juno's idle pane was used); the pane is the same object, so nothing depends on it.
- A very large note (the preview re-renders the whole note 120 ms after the last keystroke).

### Decisions (not in the spec)
1. **Which files:** `.md`, `.markdown`, `.txt` are listed and opened; other files stay on disk, unseen. A note that isn't UTF-8
   is refused with a message. New notes get `.md` unless the name has a note extension; renaming keeps a note's extension.
2. **Delete is permanent** (after a confirmation), not a trash. Names can't contain slashes or `< > : " | ? *`, can't start with a
   dot or end with a dot or space.
3. **Autosave** instead of a Save button (Ctrl+S saves at once); the tab shows ● while unsaved.
4. **The notes folder isn't created for you:** if it doesn't exist the view offers "Create it".
5. **Overlap also refuses equal folders**, and applies when saving either side; it doesn't look at `~/.juno` itself.
6. **The agent beside is the board's pane lent for the time:** only while the notepad is showing; the picker's choice is
   remembered and lent again on the next visit. Juno is in the picker (she is an agent with a pane).
7. **Edit / Split / Preview** applies to all tabs and is remembered. Split is the default.
8. `ui.notes` holds the notepad; `ui.pane.MarkdownPreview` is the only public hook into the Markdown renderer.

### Known issues
- No file watcher: changes made outside Juno are picked up on ⟳, on opening the note again and when saving.
- The editor has no undo across a reload, no search and no list auto-continuation.
- The preview doesn't follow the editor's scroll position.

### For the next milestone (7)
- The first-run setup's notes step doesn't check overlap itself (no project exists yet); the project step does.
- `ScreenshotTool` doesn't include the notes view; use `NotesScreens`.

## Milestone 5: GitHub/GitLab, PRs and reviews. Done for GitHub; GitLab not run (2026-11-02)

### Done
- **Keys on agents** (`git.GitAccess`, `runtime.HostAccess`): every process gets the git environment of §5.1.1. The user's
  credential helpers are cleared (an empty `credential.helper` through `GIT_CONFIG_*`, read last), the SSH agent and
  token variables are removed, git can't prompt. With a key: `GIT_ALLOW_PROTOCOL=file:https`, SSH remotes of the key's
  host rewritten to HTTPS, and a per-process credential helper (`run/git-credential.sh`, deleted with the process) that
  asks Juno's `/git-credential` with the process's token, so the key's token is never on disk or in the agent's
  environment. The key's git identity is set with `config --worktree`. Changing an agent's key restarts its process at
  the next message.
- **No key, no host:** `GIT_ALLOW_PROTOCOL=file`, no host MCP servers, `gh` / `glab` disallowed for any profile.
  `git` refusals in tool output, a refused `gh` / `glab` and `start_review` stop at **Awaiting 🔑** with the reason;
  setting a key answers it. Juno's own fetches follow the same rules (`GitAccess.forJuno`).
- **Keys tab:** a GitLab key's second token (field, vault entry `<id>-git`, `env:` form), and **Test** for real
  (`HostAccess.test`): MCP server, token, repository, PRs, runs, check runs, statuses, and write checks that can't succeed
  (a ref with an all-zero sha, a review on PR 0), naming the missing permission from `x-accepted-github-permissions`.
- **Host MCP proxy** (§6.6: `/host/github`, `/host/gitlab` in `JunoMcpServer`; `comms.McpClient`, `comms.HostActions`,
  `HostAccess.call`): `tools/list` shows only what the profile may do; every call is classified by tool and arguments
  and refused unless allowed. Anything unknown is refused. The token stays in Juno.
- **Coder flow:** the existing prompt now works end to end. Opening a PR/MR protects its base branch in that worktree
  (`Agent.prBases`, `Worktrees.setProtected`). `/checkforcomments` needed no code.
- **Reviews** (`runtime.Reviews`, `comms.Pipelines`, `comms.GitHubApi`, `comms.GitLabApi`, `git.Snapshots`): targets
  (number, link, agent's name), every refusal, polling (60 s for 10 minutes, then 2 minutes, up to the limit) with
  **Waiting ⏳** ("⏳ pipelines on #8 · 12 min"), every outcome of §6.3, snapshots at
  `~/.juno/review/<agent id>-<pr>-<commit>` (local commit, or a fetch under `refs/juno/review/…`; submodules, LFS, lock
  retries), the process restarting in the snapshot with its conversation kept, `end_review`, leftovers after 24 hours,
  Storage. A review survives a restart. Pipeline questions have a card in the pane and buttons in the inbox.
- **Unit tests** (159 in all, `mvn verify`): `HostLogicTest` (what the proxy lets through on both hosts, what checks mean,
  GitHub's answers, the Test lines), `GitAccessTest` (real git: no-key refusals, cleared helpers, SSH rewrite, nothing in
  git config, snapshots from a local commit and from a fetch), `ReviewsTest` (end to end with `FakeClaude`, a fake REST
  client and a fake MCP server: every outcome, refusals, held reports, the credential helper through real
  `git credential fill`, the proxy per profile, posting rules, thread ownership).

### Checked live (Claude Code 2.1.314, haiku / low, scratch `JUNO_HOME` and `CLAUDE_CONFIG_DIR`, `SpinachWings/terrible-workout-tracker`, GitHub only) with `dev.juno.ui.ReviewScreens`
- **Test button:** every line passed (46 MCP tools, public repository). A token *missing* a permission wasn't tried live.
- **No key:** `git push` refused by git, the agent stopped at 🔑 with the message, nothing reached the remote.
- **Coder:** opened PR #8 from `spike/m5` through the proxy and messaged its linked reviewer once.
- **Reviewer:** the message started the review; snapshot detached at the head, the process ran in it; comments shown
  with ❓ and nothing posted; after the user's approval one `COMMENTED` review (inline on the line, summary) anchored to the
  head, both with the marker. Snapshot and refs gone after `end_review`.
- **`/checkforcomments`:** listed the thread and changed nothing until told; after a decision: commit, push, a reply with
  the marker, thread left unresolved.
- **Re-review:** found the commit in its old summary, reviewed only what changed, replied, **resolved its own thread**
  (GraphQL shows `isResolved: true`), new summary.
- **Pipeline wait:** on a commit pushed just before, a real 65 s in ⏳ until CI passed ("2 passed"), then the snapshot. A
  review dropped by the user posted nothing.
- PR #8 is closed unmerged with its history; branch `spike/m5` deleted. The token appears nowhere in what the run wrote.
- Screens by eye: the board's review states, the pipeline card, the inbox, both Keys forms, the 🔑 stop.

### Not verified live
- **Everything on GitLab** (`GitLabApi`, its tool names and argument names such as `note` and `verdict`): from its
  documentation; no sandbox. Expect corrections at the first real run.
- GitHub: needs-a-person (`action_required`, a first-time fork), a failed pipeline, a *required* check, the wait limit, a
  private repository, `<name>.ghe.com`, submodules and LFS in a snapshot. All are unit tested with made-up answers, except
  private repositories and `ghe.com`.
- The refusal of approve / merge / close and of someone else's thread was tested through the proxy with a fake host, and
  live only in that the reviewer's and coder's tool lists left them out. The Test button's write checks were seen
  passing, not failing.
- `Bash` tool output as the trigger for 🔑 "key refused" (`Authentication failed`), as opposed to "no key".

### Changed in approved text
- `profiles/reviewer.md` has a new first line under Starting: a linked agent's "PR opened" message is the request to
  review. Without it haiku waited to be asked, twice, which contradicts §6.4. **Please read it.**
- `results/host.md` is new (the proxy's refusals). `results/start_review.md` lost `not-yet`.

### Decisions (not in the spec)
1. **Per-action rules are `host:<action>`** in a profile's allowed / disallowed tools (`host:read`, `open`, `reply`, `review`,
   `resolve`, `approve`, `request-changes`, `merge`, `close`, `edit`, or `host:*`). They're Juno's own: never passed to the
   CLI, which gets the whole server (`mcp__github`), since the proxy already decides. No host rule = no host servers.
2. **Server-side posting rules, beyond the prompt.** For profiles allowed `host:review`, the proxy refuses review, reply and
   resolve unless a review is open with its snapshot and **the user has sent a message since the snapshot**, and refuses a
   commit other than the reviewed one. This can't check *what* was approved; it stops posting before anyone answered.
   Replies by coders aren't gated: their approval is the prompt's job.
3. **The marker is added by the proxy** when a comment lacks one, so thread ownership and `/checkforcomments` can rely on it.
4. **Review-comment approval is the reviewer's ❓ question**, not a new card (it needs free text: edits, drops). The inbox's
   question item covers it. This replaces the Milestone 4 note that proposals might be the model.
5. **"No pipelines":** none on the commit and no workflows in the repository → at once. Workflows but nothing on the
   commit yet → 2 minutes first, since a run can start late; then none. Required checks that haven't reported (from
   rulesets) keep it waiting.
6. **GitLab:** a job with `allow_failure` is optional, every other job required, whatever
   `only_allow_merge_if_pipeline_succeeds` says. A manual job with `allow_failure` is ignored.
7. **Which key:** the host of the project's `origin`; with no `origin`, the agent's only key. Project remotes hold no host.
8. **Tokens are read** when a process first needs them and for each poll, not only at agent start: polls run with no process.
   The proxy keeps them only for the process's life. Juno's own fetches pass the token as an HTTP header in that one git
   child's environment.
9. **A reviewer's snapshot failure** asks (Try again / Cancel) as a fourth question. `/stop` on a Waiting agent cancels its
   review; `/new` ends it silently; `/resume` refuses while a pipeline question is open.
10. **A message from the user during a review doesn't take away Juno's task:** it's an answer about the comments.
    Found by a test: the report would have been lost.
11. **`ToolCatalog`** lists the `host:` rules; the Keys form's test repository defaults to the first project's remote.
12. `JunoHome.reviewsDir(agent)` became `reviewDir()` to match §6.3's folder names; Storage sums by name prefix.

### Known issues
- On haiku `/checkforcomments` and the reviewer's output are looser than the prompts ask (no `file:line` list, Done rather than
  ❓ after the list); nothing was posted early. Worth another look on the profiles' real models.
- `start_review` reports `not-found` when given a name with typos; it can't suggest one.
- The first-run setup still doesn't show the token permissions (from Milestone 5a; for Milestone 7).
- If `claude` isn't on PATH (seen in your PowerShell), agents fail unless Settings → Claude Code path has the full path.

### For the next milestone (6)
- Nothing here touches the notepad. `AgentRuntime.folder(agent)` now returns the snapshot during a review, which a note view
  showing an agent's folder should expect.
- `ReviewScreens` (test sources) drives real runs; the `tell` scenario types into a pane. It needs `JUNO_SANDBOX_TOKEN`.

## Milestone 5a: GitHub/GitLab capability check. Done for GitHub, docs-only for GitLab (2026-11-02)

Spike, no product code. **GitHub**: verified live against the sandbox `SpinachWings/terrible-dashboard` (a public
repository with no workflows; the first sandbox, `terrible-workout-tracker`, was the wrong repository for the token) with a
fine-grained PAT (Contents and Pull requests read and write). **GitLab**: docs only (no sandbox, per your call).
Scripts were throwaway and are not kept. Everything the live run created was removed or closed (PR #11 stays, closed,
with its review history; branch `spike/5a` deleted).

### Live run (GitHub, 2026-11-02), all through the MCP server
`create_branch` (spike/5a) → `create_or_update_file` → `create_pull_request` (#11) → `pull_request_review_write`
`create` with `commitID` → `add_comment_to_pending_review` (path, line 2, RIGHT) → `pull_request_review_write`
`submit_pending` (`event: COMMENT`, body = summary) → `pull_request_read` `get_review_comments` →
`add_reply_to_pull_request_comment` → `pull_request_review_write` `resolve_thread` → `get_check_runs` / `get_status` →
`update_pull_request` `state: closed`. Every step worked. What the table does not say:
- The inline comment is anchored to the commit given as `commitID` (the review's `commit_id` shows it).
- `get_review_comments` returns the **thread** id (`PRRT_…`, for `resolve_thread`) but the reply tool wants the
  **numeric comment id**, which is in the comment's `html_url` (`…#discussion_r<id>`). Juno parses it.
- The reply and the review summary are posted as the key's account; Juno's marker ("🤖 name (profile):") is just text.
- A PR with no checks and no statuses answers `get_check_runs` `{"total_count":0}` and `get_status`
  `{"state":"pending","total_count":0}`. **"pending" with zero statuses means no pipelines**, not "waiting": Juno must
  look at `total_count` (and at `actions_list` for runs) before it treats `pending` as waiting.
- Branch deletion has no MCP tool (REST `DELETE /git/refs/heads/<branch>`); not needed by the product.
- `main` here has no ruleset (`/rules/branches/main` answers `[]`) and no workflow runs, so required checks and
  `action_required` were read but not observed with real data.

### Pipelines, live (GitHub, `terrible-workout-tracker`, PR #7, workflow `CI` on `push` and `pull_request`)
- Pushing the branch and opening the PR each started a run, so a PR head has **two workflow runs and two check runs
  with the same name** (`hello`, one per event). Juno must treat a check by (name, event) and count a commit as passed
  only when every run on the head sha has finished successfully, not by name.
- `get_check_runs` showed `in_progress` (no `conclusion`) within 10 s, then `completed` / `success` by 20 s; its items
  carry only `id, name, status, conclusion, started_at, completed_at, html_url, details_url` (**no head sha, no
  required flag, no app**).
- `get_status` stayed `{"state":"pending","total_count":0}` the whole time, even after the checks succeeded: it only
  covers legacy commit statuses. **Never use it for GitHub Actions.**
- `actions_list` `list_workflow_runs` (toolset `/mcp/x/actions`, filter `branch`) gives event, status, conclusion and
  `head_sha` per run; that is how runs are tied to the head commit.
- **Required checks:** GraphQL `commit.statusCheckRollup.contexts{... on CheckRun{name isRequired(pullRequestNumber:N)}}`
  works with the fine-grained token and answered `false` for both (no ruleset: `/rules/branches/main` is `[]`). That is
  the call for "required" (design §6.3); REST rulesets / branch protection are the fallback.
- Not seen: `action_required` ("approve and run"; needs a fork PR), a failing job, a required check, a private repo.
- Review flow repeated on this PR (pending review, inline comment, submit): worked. PR closed unmerged, branch
  `spike/5a` deleted.

### Capability table

| Capability | GitHub (`https://api.githubcopilot.com/mcp/`) | GitLab (`https://gitlab.com/api/v4/mcp`, docs, GitLab 19.5) |
| --- | --- | --- |
| Open PR/MR | `create_pull_request` (head, base, title, body, draft) | `save_merge_request` (create when no `merge_request_iid`; aliases `create_merge_request`) |
| Read PR/MR, files, diff, commits | `pull_request_read` `method`: get, get_diff, get_files, get_commits, get_reviews, get_comments | `get_merge_request` with `include`: diffs, commits, notes, pipelines, discussions, approvals |
| Review threads (list, with ids) | `pull_request_read` `get_review_comments` (threads with `PRRT_` node ids, outdated flags) | `get_merge_request` `include: discussions` |
| Inline comment anchored to a commit | `pull_request_review_write` `create` (pending review, `commitID`) + `add_comment_to_pending_review` (path, line, side, startLine) + `pull_request_review_write` `submit_pending` (`event: COMMENT`, body = summary) | `save_merge_request_review` `create_diff_note` (old/new path and line, `sha`), then `submit_review` |
| Summary comment | the submitted review's `body`, or `add_issue_comment` | `save_note`, or `save_merge_request_review` `create_note` / `submit_review` `summary` |
| Reply to a thread | `add_reply_to_pull_request_comment` (numeric comment id, not the thread id) | `save_note` with `discussion_id`, or `save_merge_request_review` `reply_discussion` |
| Resolve a thread | `pull_request_review_write` `method: resolve_thread` (needs `threadId`) | `save_merge_request_review` `method: resolve_discussion` |
| Pipeline / check status per commit | `pull_request_read` `get_check_runs`, `get_status` (head commit; verified to answer, 0 on a PR with no runs); workflow runs: separate toolset `actions` (`actions_list` `list_workflow_runs`, `actions_get`, `get_job_logs`) | `get_merge_request` `include: pipelines`; `list_pipelines`, `get_pipeline` (`include: jobs`) |
| Required checks | **missing → GraphQL (verified)**: `isRequired(pullRequestNumber:N)` on a check-run context; also REST `GET /repos/{r}/rules/branches/{branch}` (rulesets; answers 200 with a PAT), branch protection `.../protection/required_status_checks` (403 for this PAT: needs Administration read), or GraphQL `isRequired` on check contexts | **missing → REST**: project setting `only_allow_merge_if_pipeline_succeeds`; MR `detailed_merge_status` |
| "Waiting for approval" | **missing in MCP → REST**: workflow runs with `status: action_required` (also `waiting`) | job `status: manual`, pipeline `manual` / `blocked` |
| Approve | `pull_request_review_write` `event: APPROVE` | `save_merge_request_review` `method: approve` |
| Request changes | `pull_request_review_write` `event: REQUEST_CHANGES` | `save_merge_request_review` `verdict` (unverified) |
| Merge | `merge_pull_request` | `accept_merge_request` |
| Close | `update_pull_request` `state: closed` | `save_merge_request` `state_event: close` |
| Token type | fine-grained or classic PAT as `Authorization: Bearer` (verified) | **fine-grained PAT** with the MCP tool resource + Execute; OAuth with `mcp` scope. **Legacy PATs, project and group tokens are rejected (403).** |

Other GitHub facts (verified): the server is stateless apart from a session id on `initialize`; `tools/list` needs the
`Mcp-Protocol-Version` header (without it GitHub answers an HTML "Bad request"). Default toolset has 46 tools.
Extra toolsets by URL path `/mcp/x/<toolset>` (e.g. `/mcp/x/actions` adds 4 tools; `/mcp/x/all` has 113). The
`X-MCP-Toolsets` header form did not work in my calls. Tools can be limited per call with `X-MCP-Tools` /
`X-MCP-Exclude-Tools` (header names listed in the server's CORS headers; not tested).

### Still not verified
- Everything on GitLab (names are from a mirror of the official tools page, GitLab 19.5; no sandbox).
- `action_required` runs, failed jobs and a *required* check were not seen with data (the sandbox has no ruleset,
  and `action_required` needs a first-time fork contributor).
- Whether check runs on a **private** repository are readable with Contents / Pull requests / Actions / Commit
  statuses read (the sandbox repository is public). The Test button should find out.
- The `<name>.ghe.com` URL.

### Spec changes (applied to `docs/design.md` on your OK, 2026-11-02)
1. **Host MCP proxy** (new §6.6): agents' `mcp.json` points at a proxy in Juno's MCP server, which holds the key's token
   and allows only the actions a profile may use (approve / request changes / resolve / merge / close are arguments of
   tools the profiles need, so tool-name denies can't express them). §5.1.1 and §6 table updated.
2. **Required checks and "needs approval" via REST** (§6.6 table, §13).
3. **Token permissions documented** in §5.1 and in the app: Settings → Keys → "Required token permissions" (text in
   `model.TokenPermissions`, switches with the key's kind). Not yet shown in the first-run setup.
4. **A GitLab key holds two tokens** (MCP fine-grained PAT; git/REST token) in §5.1. **Stub:** `model.Key` still has one
   token and the Keys form one token field; the second token field, its vault entry and the Test button are
   Milestone 5 (TODO there).
5. `<name>.ghe.com` URL still unverified.

### Token problems on the way (for the Keys help)
Four fine-grained tokens failed every write with `403 Resource not accessible by personal access token` (reads
worked): they were scoped to `terrible-dashboard`, not the repository being tested, so writes were refused while reads
of a public repository still worked. A read that works therefore proves nothing about write access; the Test button
should try a harmless write-check, and the 403's `x-accepted-github-permissions` header names the missing permission
(e.g. `contents=write`), which is worth showing in the Test result. Rewrite to the right repository fixed it.

## Milestone 4: Juno and communication. Done (2026-11-01)

### Done
- **Juno** is an agent with the fixed id `juno` (`Orchestration.JUNO`, `agents/juno/`), name Juno, the `orchestrator`
  profile, magenta, no project (private folder). Made on the first start, back with her conversation after a restart.
  Her pane is an ordinary `AgentPane` (the `JunoPane` placeholder is gone): movable, splittable, maximisable, no ✕; her
  ⋯ menu has model, effort, rules, conversations and usage only. She can't be deleted or renamed, is never queued and
  never counts towards the cap. `runtime.agents()` is everyone but Juno; `all()` includes her; `juno()`.
- **Juno MCP server** (`comms.JunoMcpServer`): every §4.4 tool. Access by the profile's allowed tools
  (`mcp__juno__<tool>`, or `mcp__juno` / `mcp__juno__*`), disallowed tools win, orchestrator-only tools only for Juno
  herself; `tools/list` shows a caller only what it may call; parameters are typed (booleans, `lines` integer, optional
  `lines` / `reason`). Refusals come back as tool errors with the `docs/prompts/results/` text.
- **Rules** (`runtime.Orchestration`, all under the runtime's lock): links (`comms.Links`, saved with proposals in
  `~/.juno/comms.json` by `comms.CommsStore`), `send_message` refused without a link (Juno ↔ anyone always), self,
  unknown names; `list_agents` (Juno: everyone plus links; others: Juno plus linked agents); `set_link` (Juno, same
  agent, already, no reason; requested → at once; suggested → held); `assign_task` (not to Juno; held when unnamed /
  "Always" / fresh); `report`; `get_agent_output`; `get_agent_context`; `end_review`.
- **Links**: `/cantalkto<name>` / `/stoptalkingto<name>`, via Juno, Juno's suggestions held for approval (card
  "Link a ↔ b? Why: …" with Link / Don't link, inbox, `/approve` / `/deny`). Every change is a line in both panes and
  Juno's ("🔗 bob ↔ jim linked by you"), a `[Link opened]` / `[Link closed]` notice to both agents, and a `[Link]` note to
  Juno when you made it. Deleting an agent removes its links and tells the other agent. The preamble's contacts list
  names who the agent can talk to with profile and status; the pane's "can talk to" line shows them in their colours.
- **Delegation**: direct (with "Tell Juno about direct tasks" → a `[Note]`), named through Juno, `/assign<name> <task>`
  (delivered by the app, `[Task from Juno, written by the user]`, Juno gets a note), unnamed with an approval card
  (`ui.pane.ProposalCard`: Approve / Edit / Choose another / Cancel; fresh: Start fresh / Continue instead / Cancel).
  Fresh starts always ask; the old conversation stays in the list. Setting **Juno asks before assigning** is the
  three-way choice of §4.2 (Settings → System).
- **Reports**: capped at Report length, wrapped with status, task, files changed and context; batched into one digest
  while Juno works; the review exception (held while `reviewOpen`, sent by `end_review`).
- **Loop guard** (`comms.LoopGuard`): hop counts carried along chains, refused past the Hop limit with a ⛔ line in both
  panes and a `[Stopped by Juno]` notice; a message from you to either agent resets it.
- **Commands**: `/hey` from any pane (also to Juno), `/getcontext`, and in Juno's pane `/getcontextfrom<name>`,
  `/assign`, `/status`, `/broadcast`; `/approve` / `/deny` in Juno's pane answer a permission request first, then her
  oldest proposal. Autocomplete suggests names after `/getcontextfrom` and `/assign`. Context summaries are built from
  the journal (status, context, task, files read / changed, links, last summary, conversation age and turns).
- **Inbox**: Juno's proposals with their answers, and Juno's own permission requests. The filter list shows Juno's real
  status (still can't be hidden).
- **Panes**: tasks, agent messages and reports are shown open ("› Juno → bob: task", "message from jim (tester)");
  notices fold into one "notices" block; broadcasts show as yours. Cards scroll once they'd take half the pane.
- **Unit tests** (110 in all, `mvn verify`): `CommsTest` (end to end through the MCP server with `FakeClaude`, a
  test-only stand-in for Claude Code that speaks stream-json, logs what reached it and can call Juno's tools):
  Juno herself and after a restart, tool access, links and their announcements, contacts in the system prompt, links
  after a restart and on deletion, Juno's link rules and suggestions, the loop guard and its reset, an unnamed task
  approved with an edit and its report, named / fresh / continue-instead, the approval setting, choose another,
  cancel, a fresh start with nothing to clear, a task waiting for a turn, `/assign` and its note, direct-task notes,
  capped reports, digests, the review hold, context summaries, output, `/status`, `/broadcast`.
- **Checked against the real CLI** (Claude Code 2.1.314, haiku / low for Juno and the agents, scratch `JUNO_HOME`,
  scratch `CLAUDE_CONFIG_DIR`) with `dev.juno.ui.JunoScreens` (test sources; `ui` needs no CLI, `live` runs the four
  checks): an unnamed task (Juno looked with `list_agents`, chose bob, `assign_task` was held with the card; Approve
  delivered it, bob called `report`, the report started Juno's turn and she passed the poem on); a fresh start (named,
  `fresh: true` → card → Start fresh → new conversation, the old one in the list); bob's `send_message` to jim refused
  ("You are not linked to jim…"), then "Let bob and jim talk to each other" → Juno's `set_link` → linked and announced
  in all three panes, and the message delivered (jim got the `[Link opened]` notice with it); a counting game between
  bob and jim with the hop limit at 3 stopped at the fourth message with the refusal text and ⛔ in both panes. `ui` (no
  CLI) shows the three kinds of card, the inbox, `/status`, `/getcontextfrom` and `/help` in Juno's pane, the
  can-talk-to line, Juno's options menu and the delegation settings.

### Stubbed
- `start_review` answers "not available yet" (`results/start_review.md#not-yet`); Milestone 5 sets `Agent.reviewOpen`
  and makes the snapshot. `end_review` and the report hold already work.
- Keys, host MCP tools, pipelines, review-comment approvals in the inbox: Milestone 5.

### Decisions (not in the spec)
1. **What reaches Juno, and how.** Reports start her turn when she's idle (one digest when several arrived while she
   worked). Notes (direct tasks, `/assign`), approval outcomes and link changes are **notices**: sent along with her
   next message, never a turn of their own, so `/assign` and approvals cost her nothing. Link announcements and the
   loop-guard notice reach other agents the same way (`Agent.notices`, saved). In the pane they fold into one
   "notices" block before the message they came with.
2. **"Named"** (for "Only when I didn't name the agent") = the agent's name appears as a word in your latest message to
   Juno (typed or `/hey`; case ignored, spaces between name parts optional). It's checked by the server, not taken
   from Juno.
3. **Setting**: `SystemSettings.assignApproval` (UNNAMED / ALWAYS / NEVER) replaces Milestone 1's boolean
   `junoAsksBeforeAssigning`; an old saved value is ignored (default UNNAMED).
4. **A fresh start for an agent with no conversation** is treated as not fresh: there's nothing to clear, so nothing
   to approve (Juno on haiku asked for one in the live check).
5. **Unnamed and fresh** is one card: Start fresh / Continue instead / Edit / Choose another / Cancel. Choose another
   keeps `fresh`. `/approve` takes the oldest proposal as proposed. Outcome texts: unnamed → `assign-approved`
   (`fresh-continue` if you continued instead), another agent → `assign-other`, fresh only → `fresh-approved` /
   `fresh-continue`, cancel → `assign-cancelled` / `fresh-cancelled`.
6. **Reports**: `report` stores the text; it's sent when the agent's turn ends, so its status and files changed are
   final. If an agent never calls `report` for a task, its final reply is used once. A task stays the agent's until a
   new task, a plain message from you while it's Idle or Done (a direct task; an answer to an agent that's asking
   keeps it), `/new`, or deletion. No report after `/stop` or a crash (the ⏸ is in the inbox; `/resume` carries on and
   reports at the end). Files changed = diffs since the task arrived.
7. **A task for an agent that's working or queued** waits for its turn to end (`Agent.pendingTasks`, saved); Juno's
   result says "queued, after its current turn".
8. **Delivery of `send_message`**: into the running turn when the target works (read when its step ends, like `/hey`);
   otherwise it's the target's next message (may queue, or wait for a folder).
9. **Loop guard**: each agent carries the hop count of its chain; a message carries the sender's count + 1 and the
   recipient keeps the larger. Past the limit the message is refused; the first refusal of a pair shows ⛔ in both
   panes and gives the recipient `[Stopped by Juno]`. A message from you (typed, `/hey`, `/broadcast`, `/assign`, an
   approved task) sets that agent back to 0, and everyone it was stopped with. Tasks reset the count. Juno's messages
   count too.
10. **Link by an approved suggestion** is announced as "by Juno". `/cantalkto` in Juno's pane says she can always
    talk to everyone.
11. **New prompt text** (in `docs/prompts/`): `results/list_agents.md`; `get_agent.md` `output` and `context`;
    `assign_task.md` `why-*`, `place`, `after-turn`; `common.md` `missing`; `start_review.md` `not-yet`.
12. **Context summary** "last summary" = the last report Juno got from the agent, else the first line of its last
    reply. `/status` and pane lines are UI text, not prompts.
13. **Storage** lists Juno last. Shutting down drops output still arriving from dying processes (found by the tests).
14. **Replaced processes are ignored.** Events from a process that is no longer the agent's current one (after a
    restart or fresh start) are dropped. Before, the old process's exit cleaned the run folder and unregistered the
    MCP token of its successor, which then had no Juno tools ("Available MCP tools: none") and hung; seen in the live
    check after a fresh start, latent since Milestone 2 for any restart. Shutdown also waits up to 3 s for every
    process it started to end.

### Known issues
- The "named" check can still be fooled ("don't give it to bob" names bob); the undo window below is what catches it,
  and only if you see the card within the 15 seconds.
- If you undo while Juno is still finishing her turn, her reply can still say the task is on its way: she hears about
  the undo with her next message.
- The regression check for decision 14 in `CommsTest` passes with or without the fix (`FakeClaude` doesn't hit the
  same timing); the fix was confirmed with the real CLI.
- Notes to Juno wait for her next message: if nothing reaches her, she doesn't see them (by design, decision 1).
- From earlier milestones: drag-to-swap gesture not driven, Delete dialogs not driven, no bold face in Share Tech
  Mono, `Notification` hook not exercised.

### Changes after review (same session)
- **Undo window for named agents** (your call; `docs/design.md` §4.2 updated). A task Juno writes for an agent the
  server judged you named now waits **15 seconds** on a card with Undo / Send now (`/deny`, `/approve`, or the inbox)
  before it goes, because "named" is only a guess from your words. Nothing reaches the agent during the wait, so an
  undo costs it nothing; Juno gets the existing `[Cancelled]` notice. It's a `Proposal` with `sendAt` set. Decisions:
  - The window applies only under "Only when I didn't name the agent". **Never** stays immediate (your own choice),
    `/assign` goes at once (you typed the task), and anything else already has a card.
  - 15 seconds is fixed, not a setting (`Orchestration.UNDO_MS`).
  - After a restart a task still counting down doesn't go by itself: it becomes an ordinary approval card.
  - New prompt text: `results/assign_task.md` `undo-window`; `tools/assign_task.md` and `profiles/orchestrator.md`
    mention the wait.
  - Checked with the real CLI (`JunoScreens undo`, haiku / low): the first task undone and nothing reached bob after
    the window passed; the second went by itself and its report reached Juno.
  - Also: one test (`/assign`'s note) raced with the report that delivers the note; it now checks what reached Juno.

### For the next milestone (5)
- `start_review` is in `Orchestration.call` (TODO there). Set `Agent.reviewOpen` when a review starts; `report` and
  `end_review` already hold and release the report. Waiting ⏳ agents already don't count towards the cap.
- `AgentRuntime.inject` puts text into an agent's conversation (running turn or next message); `notify` queues a
  notice for its next message. Pipeline outcomes (`wrappers/pipeline.md`) fit `inject`.
- Proposals (`comms.Proposal`, `Orchestration.proposals()`) are the model for review-comment approvals; cards are in
  `ui.pane.ProposalCard`, inbox items in `Inbox.items()`.
- `FakeClaude` / `FakeProvider` (test sources) run the runtime end to end without Claude Code.

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
