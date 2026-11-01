# Results: protected branches

What an agent sees when a git hook in its worktree refuses something (design §7.8). `@@BRANCH@@` is replaced by the
hook with the protected branch's name.

## commit
Juno: committing to `@@BRANCH@@` isn't allowed. Work on your own branch and open a PR/MR.

## merge
Juno: merging onto `@@BRANCH@@` isn't allowed. Work on your own branch and open a PR/MR. Merging `@@BRANCH@@` into your own branch is fine.

## push
Juno: pushing to `@@BRANCH@@` isn't allowed. Push your own branch and open a PR/MR.

## update
Juno: changing the branch `@@BRANCH@@` isn't allowed. Work on your own branch and open a PR/MR.
