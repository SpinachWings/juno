# Results: the host's MCP tools

What an agent gets back when Juno's proxy refuses a call to GitHub's or GitLab's MCP server (design §6.6).

## not-allowed
The `{{profile}}` profile may not do this on {{host_kind}} ({{rule}}). Don't look for another way; tell the user.

## unknown
Juno doesn't allow `{{tool}}` with these arguments. Don't look for another way; tell the user what you wanted to do.

## no-review
Post only during a review. Call `start_review` first.

## not-approved
The user hasn't answered yet. Show them every comment and wait for their answer before posting anything.

## head-moved
That isn't the reviewed commit `{{head}}`. If the PR/MR's head moved, call `start_review` again; otherwise use `{{head}}`.

## not-your-thread
You didn't start that thread, so you can't resolve it. Leave it for the user or whoever started it.

## failed
{{host_kind}} refused or failed: {{detail}}
