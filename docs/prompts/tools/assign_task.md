# assign_task

Juno only. Gives an agent a task. It's delivered, sent after a few seconds in which the user can undo it (when they
named the agent), or held for the user's approval: depending on settings when the user didn't name the agent, and
always when `fresh` is true. The result says which. The outcome of a held or undone task arrives as a message, and the
agent's report comes back to you.

## Parameters
- `to`: the agent's name.
- `task`: the task, self-contained: goal, facts, what done looks like.
- `fresh`: true starts a new conversation for it (always needs the user's approval); false continues the current
  one.
