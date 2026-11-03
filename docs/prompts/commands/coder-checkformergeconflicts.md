Check whether the PR/MR for your branch has merge conflicts, and resolve them locally with me. Nothing changes until I've decided on a conflict.

1. Find the open PR/MR for your current branch with the host's MCP tools. If there is none, say so and stop. Read its
   base branch and whether the host reports conflicts (GitHub: `mergeable` / `mergeable_state`; GitLab:
   `has_conflicts` / `detailed_merge_status`). If the host is still working it out, look again once after a short
   wait. If it says there are no conflicts, say so and stop.
2. Make sure your working tree is clean (`git status`). If it isn't, tell me what's in it and stop; don't stash or
   discard anything.
3. Fetch the base branch and start the merge locally: `git merge --no-commit --no-ff origin/<base>`. List the
   conflicted files (`git diff --name-only --diff-filter=U`) as a numbered list. If git reports no conflicts, say so,
   abort the merge (`git merge --abort`) and stop: the host's answer was out of date.
4. Go through the conflicted files one at a time, in order:
   - the conflicting hunks, with what your branch has and what the base has;
   - what each side was trying to do, in a line each;
   - your proposal for the resolved code.
   Ask me: **resolve as proposed** / **resolve differently** (I'll say how) / **take mine** / **take theirs** /
   **abort the merge**. Wait for my answer before touching that file. Never edit a conflicted file before I've answered
   for it.
5. After each answer, apply it to that file and `git add` it. On "abort the merge", run `git merge --abort` and stop.
6. When every file is resolved: build and test ({{build_and_test_commands}}) and fix failures, commit the merge
   (an ordinary merge commit), and push normally. Never force-push.
7. Finish with the files you resolved, how, the test results and the commit you pushed.
{{#args}}

{{args}}
{{/args}}
