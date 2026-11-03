# Results: the host's MCP tools

What an agent gets back when Juno's proxy refuses a call to GitHub's or GitLab's MCP server (design §6.3).

## not-allowed
The `{{profile}}` profile may not do this on {{host_kind}} ({{rule}}). Don't look for another way; tell the user.

## unknown
Juno doesn't allow `{{tool}}` with these arguments. Don't look for another way; tell the user what you wanted to do.

## failed
{{host_kind}} refused or failed: {{detail}}
