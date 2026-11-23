# Results: list_agents

`{{status}}` is the status as the user sees it (e.g. "Idle", "❓ Awaiting decision"); `{{context}}` is e.g. "12 %",
or "unknown" before the agent's first reply.

## agent
- {{name}} · {{profile}} · {{#project}}project {{project}}{{/project}}{{^project}}no project{{/project}} · {{status}}{{#branch}} · branch {{branch}}{{/branch}} · context {{context}} · colour {{colour}}

## reachable
Agents you can message now:
{{agents}}

## everyone
Every agent:
{{agents}}
{{#links}}Open links: {{links}}.{{/links}}{{^links}}No links are open.{{/links}}

## nobody
There are no agents besides you. The user creates them; you can't.
