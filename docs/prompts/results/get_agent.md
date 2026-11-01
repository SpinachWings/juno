# Results: get_agent_output, get_agent_context

## empty
{{name}} has no output yet.

## output
The last {{count}} lines of {{name}}'s output, newest last:
{{lines}}

## context
{{name}} ({{profile}}) · {{status}} · context {{context}}
Project: {{project}}
{{#current}}Current task{{/current}}{{^current}}Last task{{/current}}: {{task}}
Files read: {{files_read}}
Files changed: {{files_changed}}
Links: {{links}}
Last summary: {{summary}}
Conversation: {{#started}}started {{age}}, {{turns}} turns{{/started}}{{^started}}not started yet{{/started}}
